package com.trailblazer.fabric.networking.payload.c2s;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.trailblazer.fabric.TrailblazerFabricClient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UpdatePathMetadataPayload(UUID pathId, String name, int colorArgb) implements CustomPacketPayload {
    public static final Type<UpdatePathMetadataPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "update_path_metadata"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePathMetadataPayload> CODEC = StreamCodec.of(
            UpdatePathMetadataPayload::write,
            UpdatePathMetadataPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, UpdatePathMetadataPayload value) {
        buf.writeUUID(value.pathId());
        buf.writeInt(value.colorArgb());
        byte[] nameBytes = value.name().getBytes(StandardCharsets.UTF_8);
        buf.writeVarInt(nameBytes.length);
        buf.writeBytes(nameBytes);
    }

    private static UpdatePathMetadataPayload read(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int color = buf.readInt();
        int length = buf.readVarInt();
        byte[] nameBytes = new byte[length];
        buf.readBytes(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        return new UpdatePathMetadataPayload(id, name, color);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
