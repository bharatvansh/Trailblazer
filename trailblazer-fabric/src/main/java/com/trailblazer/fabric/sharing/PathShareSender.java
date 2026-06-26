package com.trailblazer.fabric.sharing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.trailblazer.api.PathData;
import com.trailblazer.fabric.TrailblazerFabricClient;
import com.trailblazer.fabric.networking.payload.c2s.SharePathRequestPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Utility responsible for packaging a path into a share payload and sending it to the relay server.
 */
public final class PathShareSender {

    private static final Gson GSON = new Gson();

    // Keep aligned with SharePathRequestPayload's cap.
    private static final int MAX_JSON_BYTES = 1_048_576;

    private PathShareSender() {
    }

    public static void sharePath(PathData path, Collection<UUID> recipients) {
        if (path == null || recipients == null || recipients.isEmpty()) {
            return;
        }
        List<UUID> resolvedRecipients = (recipients instanceof List<UUID> list)
                ? list
                : new ArrayList<>(recipients);
        String json = GSON.toJson(path);

        // Avoid throwing from the payload codec (which can disconnect the client).
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > MAX_JSON_BYTES) {
            TrailblazerFabricClient.LOGGER.warn("Refusing to share path '{}' because payload is too large: {} bytes", path.getPathName(), jsonBytes.length);
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.player != null) {
            client.player.sendSystemMessage(
                Component.literal("Path is too large to share (" + jsonBytes.length + " bytes). Try reducing points / splitting the path.")
                    .withStyle(ChatFormatting.RED)
            );
            }
            return;
        }
        ClientPlayNetworking.send(new SharePathRequestPayload(resolvedRecipients, json));
    }
}
