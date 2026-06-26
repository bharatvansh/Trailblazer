package com.trailblazer.fabric.networking.payload.c2s;

import java.util.UUID;

import com.trailblazer.fabric.TrailblazerFabricClient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DeletePathPayload(UUID pathId) implements CustomPacketPayload {
    public static final Type<DeletePathPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "delete_path"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePathPayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeUUID(value.pathId()),
        buf -> new DeletePathPayload(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
