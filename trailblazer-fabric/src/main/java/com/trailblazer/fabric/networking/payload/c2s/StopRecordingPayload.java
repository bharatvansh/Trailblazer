package com.trailblazer.fabric.networking.payload.c2s;

import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-Server payload requesting to stop recording a path on the server.
 */
public record StopRecordingPayload(boolean save) implements CustomPacketPayload {
    public static final Type<StopRecordingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "stop_recording_request"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, StopRecordingPayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeBoolean(value.save),
        (buf) -> new StopRecordingPayload(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

