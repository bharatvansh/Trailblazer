package com.trailblazer.fabric.networking.payload.s2c;

import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StopLivePathPayload() implements CustomPacketPayload {
    public static final Type<StopLivePathPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "stop_live_path"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StopLivePathPayload> CODEC = StreamCodec.of(
        (value, buf) -> {},
        (buf) -> new StopLivePathPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}