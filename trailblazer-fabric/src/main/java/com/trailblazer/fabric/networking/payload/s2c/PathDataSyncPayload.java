package com.trailblazer.fabric.networking.payload.s2c;

import java.nio.charset.StandardCharsets;

import com.trailblazer.fabric.TrailblazerFabricClient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Payload carrying JSON representing a list of PathData entries.
 * This adapts older identifier-based custom packet to the new Fabric payload API (1.20.5+/1.21).
 */
public record PathDataSyncPayload(String json) implements CustomPacketPayload {

    public static final Type<PathDataSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "sync_path_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PathDataSyncPayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeUtf(value.json),
        (buf) -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new PathDataSyncPayload(new String(bytes, StandardCharsets.UTF_8));
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
