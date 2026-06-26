package com.trailblazer.fabric.networking;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.trailblazer.api.PathData;
import com.trailblazer.api.Vector3d;
import com.trailblazer.fabric.ClientPathManager;
import com.trailblazer.fabric.TrailblazerFabricClient;
import com.trailblazer.fabric.networking.payload.c2s.PathActionAckPayload;
import com.trailblazer.fabric.networking.payload.s2c.HideAllPathsPayload;
import com.trailblazer.fabric.networking.payload.s2c.LivePathUpdatePayload;
import com.trailblazer.fabric.networking.payload.s2c.PathDataSyncPayload;
import com.trailblazer.fabric.networking.payload.s2c.PathDeletedPayload;
import com.trailblazer.fabric.networking.payload.s2c.SharedPathPayload;
import com.trailblazer.fabric.networking.payload.s2c.StopLivePathPayload;
import com.trailblazer.fabric.networking.payload.s2c.PathActionResultPayload;
import com.trailblazer.fabric.networking.payload.s2c.StartRecordingPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Handles the logic for receiving packets on the client.
 */
public class ClientPacketHandler {

    private static final Gson GSON = new Gson();
    private static long highestActionResultSequence = 0L;

    public static void resetReliableActionState() {
        highestActionResultSequence = 0L;
    }

    public static void registerS2CPackets(ClientPathManager pathManager) {
        ClientPlayNetworking.registerGlobalReceiver(PathDataSyncPayload.TYPE, (payload, context) -> {
            String json = payload.json();

            final List<PathData> receivedPaths;
            try {
                Type listType = new TypeToken<List<PathData>>() {}.getType();
                List<PathData> parsed = GSON.fromJson(json, listType);
                if (parsed == null) {
                    parsed = Collections.emptyList();
                }
                receivedPaths = parsed;
            } catch (Exception e) {
                TrailblazerFabricClient.LOGGER.error("Failed to parse PathData JSON from server:", e);
                return;
            }

            context.client().execute(() -> pathManager.applyServerSync(receivedPaths));
        });

        ClientPlayNetworking.registerGlobalReceiver(HideAllPathsPayload.TYPE, (payload, context) -> {
            context.client().execute(pathManager::hideAllPaths);
        });

        ClientPlayNetworking.registerGlobalReceiver(LivePathUpdatePayload.TYPE, (payload, context) -> {
            String json = payload.json();
            final List<Vector3d> points;
            try {
                Type listType = new TypeToken<List<Vector3d>>() {}.getType();
                points = GSON.fromJson(json, listType);
            } catch (Exception e) {
                TrailblazerFabricClient.LOGGER.error("Failed to parse live path points from server:", e);
                return;
            }

            if (points != null) {
                context.client().execute(() -> pathManager.updateLivePath(points));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(StopLivePathPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                pathManager.stopLivePath();
                pathManager.stopServerRecording();
                try {
                    if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                            com.trailblazer.fabric.networking.payload.c2s.HandshakePayload.TYPE)) {
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                                new com.trailblazer.fabric.networking.payload.c2s.HandshakePayload());
                    }
                } catch (Exception e) {
                    TrailblazerFabricClient.LOGGER.error("Failed to request path resync after recording stop", e);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(StartRecordingPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                pathManager.setRecordingFromServer(payload.pathId(), payload.pathName(), payload.dimension());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(SharedPathPayload.TYPE, (payload, context) ->
            context.client().execute(() -> pathManager.applyServerShare(payload.path()))
        );

        ClientPlayNetworking.registerGlobalReceiver(PathDeletedPayload.TYPE, (payload, context) ->
            context.client().execute(() -> pathManager.removeServerPath(payload.pathId()))
        );

        ClientPlayNetworking.registerGlobalReceiver(PathActionResultPayload.TYPE, (payload, context) -> {
            long sequence = payload.sequenceNumber();
            context.client().execute(() -> {
                var client = context.client();
                boolean requiresAck = sequence > 0;
                boolean isDuplicate = requiresAck && sequence <= highestActionResultSequence;

                if (!isDuplicate) {
                    if (requiresAck) {
                        highestActionResultSequence = sequence;
                    }

                    try {
                        // Handle the special case for a successful "save" action
                        if ("save".equals(payload.action()) && payload.success() && payload.updatedPath() != null) {
                            pathManager.replaceLocalWithServerCopy(payload.updatedPath());
                        } else if (payload.updatedPath() != null) {
                            // Handle generic updates for other actions like rename, color, etc.
                            pathManager.onPathUpdated(payload.updatedPath());
                        }

                        if (client.player != null && payload.message() != null && !payload.message().isEmpty()) {
                            net.minecraft.network.chat.Style style = payload.success() ? net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.GREEN) : net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED);
                            client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(payload.message()).setStyle(style));
                        }
                    } catch (Exception ex) {
                        TrailblazerFabricClient.LOGGER.error("Failed to handle path action result payload", ex);
                    }
                }

                if (requiresAck) {
                    sendActionAck(highestActionResultSequence);
                }
            });
        });
    }

    private static void sendActionAck(long ackSequence) {
        if (ackSequence <= 0) {
            return;
        }

        if (ClientPlayNetworking.canSend(PathActionAckPayload.TYPE)) {
            ClientPlayNetworking.send(new PathActionAckPayload(ackSequence));
        } else {
            TrailblazerFabricClient.LOGGER.debug("Server does not accept Trailblazer action acknowledgments yet.");
        }
    }
}