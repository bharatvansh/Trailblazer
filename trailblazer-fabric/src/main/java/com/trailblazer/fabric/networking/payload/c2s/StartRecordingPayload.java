package com.trailblazer.fabric.networking.payload.c2s;

import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-Server payload requesting to start recording a path on the server.
 */
public record StartRecordingPayload(String pathName) implements CustomPacketPayload {
    public static final Type<StartRecordingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "start_recording_request"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, StartRecordingPayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            boolean hasName = value.pathName != null && !value.pathName.isEmpty();
            buf.writeBoolean(hasName);
            if (hasName) {
                buf.writeUtf(value.pathName);
            }
        },
        (buf) -> {
            boolean hasName = buf.readBoolean();
            String pathName = hasName ? buf.readUtf() : null;
            return new StartRecordingPayload(pathName);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

