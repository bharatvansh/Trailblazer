package com.trailblazer.fabric.networking.payload.s2c;

import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trailblazer.api.PathData;
import com.trailblazer.fabric.TrailblazerFabricClient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SharedPathPayload(PathData path) implements CustomPacketPayload {
    public static final Type<SharedPathPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "share_path"));
    private static final Gson GSON = new GsonBuilder().create();

    // Defensive cap: shared paths can be large, but should never be unbounded.
    private static final int MAX_JSON_BYTES = 1_048_576;

    public static final StreamCodec<RegistryFriendlyByteBuf, SharedPathPayload> CODEC = StreamCodec.of(
            SharedPathPayload::write,
            SharedPathPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, SharedPathPayload payload) {
        String json = GSON.toJson(payload.path());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static SharedPathPayload read(RegistryFriendlyByteBuf buf) {
        int length = buf.readVarInt();
        if (length < 0 || length > MAX_JSON_BYTES || length > buf.readableBytes()) {
            throw new IllegalStateException("Invalid shared path payload length: " + length);
        }
        byte[] data = new byte[length];
        buf.readBytes(data);
        PathData path = GSON.fromJson(new String(data, StandardCharsets.UTF_8), PathData.class);
        if (path == null) {
            throw new IllegalStateException("Received empty shared path payload");
        }
        return new SharedPathPayload(path);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
