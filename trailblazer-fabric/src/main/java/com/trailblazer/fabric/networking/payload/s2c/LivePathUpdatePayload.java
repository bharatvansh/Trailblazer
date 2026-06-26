package com.trailblazer.fabric.networking.payload.s2c;

import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

public record LivePathUpdatePayload(String json) implements CustomPacketPayload {
    public static final Type<LivePathUpdatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "live_path_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LivePathUpdatePayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeUtf(value.json),
        (buf) -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new LivePathUpdatePayload(new String(bytes, StandardCharsets.UTF_8));
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}