package com.trailblazer.fabric.networking.payload.c2s;

import com.trailblazer.fabric.TrailblazerFabricClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sends a locally recorded PathData JSON to the server for persistence.
 */
public record SavePathRequestPayload(String pathJson) implements CustomPacketPayload {

    public static final Type<SavePathRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(TrailblazerFabricClient.MOD_ID, "save_path"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SavePathRequestPayload> CODEC = StreamCodec.of(
            SavePathRequestPayload::write,
            SavePathRequestPayload::read
    );

    private static void write(RegistryFriendlyByteBuf buf, SavePathRequestPayload payload) {
        buf.writeUtf(payload.pathJson());
    }

    private static SavePathRequestPayload read(RegistryFriendlyByteBuf buf) {
        String json = buf.readUtf();
        return new SavePathRequestPayload(json);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
