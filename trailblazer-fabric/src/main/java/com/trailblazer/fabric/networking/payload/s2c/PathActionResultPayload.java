package com.trailblazer.fabric.networking.payload.s2c;

import com.google.gson.Gson;
import com.trailblazer.api.PathData;
import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generic acknowledgement / result packet for path actions initiated by the client.
 * Provides: action name, path id (if applicable), success flag, message, optional updated PathData,
 * and reliability metadata (sequence number + highest acknowledged sequence).
 */
public record PathActionResultPayload(String action, UUID pathId, boolean success, String message, PathData updatedPath,
                                     long sequenceNumber, Long acknowledgedSequence) implements CustomPacketPayload {
    public static final Type<PathActionResultPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "path_action_result"));
    private static final Gson GSON = new Gson();

    public static final StreamCodec<RegistryFriendlyByteBuf, PathActionResultPayload> CODEC = StreamCodec.of(
            PathActionResultPayload::write,
            PathActionResultPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, PathActionResultPayload value) {
        writeUtf(buf, value.action);
        buf.writeBoolean(value.pathId != null);
        if (value.pathId != null) buf.writeUUID(value.pathId);
        buf.writeBoolean(value.success);
        writeUtf(buf, value.message == null ? "" : value.message);
        buf.writeBoolean(value.updatedPath != null);
        if (value.updatedPath != null) {
            String json = GSON.toJson(value.updatedPath);
            writeUtf(buf, json);
        }
        buf.writeLong(value.sequenceNumber);
        buf.writeBoolean(value.acknowledgedSequence != null);
        if (value.acknowledgedSequence != null) {
            buf.writeLong(value.acknowledgedSequence);
        }
    }

    private static PathActionResultPayload read(RegistryFriendlyByteBuf buf) {
        String action = readUtf(buf);
        UUID pid = null;
        if (buf.readBoolean()) pid = buf.readUUID();
        boolean success = buf.readBoolean();
        String message = readUtf(buf);
        PathData updated = null;
        if (buf.readBoolean()) {
            String json = readUtf(buf);
            try { updated = GSON.fromJson(json, PathData.class); } catch (Exception ignored) {}
        }
        long sequence = buf.readLong();
        Long ack = null;
        if (buf.readBoolean()) {
            ack = buf.readLong();
        }
        return new PathActionResultPayload(action, pid, success, message, updated, sequence, ack);
    }

    private static void writeUtf(RegistryFriendlyByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }
    private static String readUtf(RegistryFriendlyByteBuf buf) {
        int len = buf.readVarInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
