package com.trailblazer.fabric.networking.payload.s2c;

import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

public record PathDeletedPayload(UUID pathId) implements CustomPacketPayload {
    public static final Type<PathDeletedPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "path_deleted"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PathDeletedPayload> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUUID(value.pathId),
            buf -> new PathDeletedPayload(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
