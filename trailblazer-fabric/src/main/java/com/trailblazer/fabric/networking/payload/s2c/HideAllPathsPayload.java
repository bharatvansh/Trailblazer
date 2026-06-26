package com.trailblazer.fabric.networking.payload.s2c;

import com.trailblazer.fabric.TrailblazerFabricClient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A simple signal payload sent from the server to the client to instruct
 * it to hide all currently rendered paths.
 */
public record HideAllPathsPayload() implements CustomPacketPayload {

    public static final Type<HideAllPathsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "hide_all_paths"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HideAllPathsPayload> CODEC = new StreamCodec<RegistryFriendlyByteBuf, HideAllPathsPayload>() {
        @Override
        public HideAllPathsPayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > 0) {
                buf.readByte();
            }
            return new HideAllPathsPayload();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, HideAllPathsPayload value) {
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
