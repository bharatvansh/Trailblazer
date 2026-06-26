package com.trailblazer.fabric.networking.payload.c2s;

import com.trailblazer.fabric.TrailblazerFabricClient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server acknowledgement for {@code PathActionResultPayload} messages.
 * Carries the highest sequence number the client has fully processed so the server
 * can stop retrying older messages.
 */
public record PathActionAckPayload(long acknowledgedSequence) implements CustomPacketPayload {

    public static final Type<PathActionAckPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "path_action_ack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PathActionAckPayload> CODEC = StreamCodec.of(
            PathActionAckPayload::write,
            PathActionAckPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, PathActionAckPayload payload) {
        buf.writeLong(payload.acknowledgedSequence());
    }

    private static PathActionAckPayload read(RegistryFriendlyByteBuf buf) {
        return new PathActionAckPayload(buf.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
