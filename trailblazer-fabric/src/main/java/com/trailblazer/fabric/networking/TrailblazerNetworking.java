package com.trailblazer.fabric.networking;

import com.trailblazer.fabric.networking.payload.c2s.DeletePathPayload;
import com.trailblazer.fabric.networking.payload.c2s.HandshakePayload;
import com.trailblazer.fabric.networking.payload.c2s.PathActionAckPayload;
import com.trailblazer.fabric.networking.payload.c2s.SharePathRequestPayload;
import com.trailblazer.fabric.networking.payload.c2s.UpdatePathMetadataPayload;
import com.trailblazer.fabric.networking.payload.s2c.HideAllPathsPayload;
import com.trailblazer.fabric.networking.payload.s2c.LivePathUpdatePayload;
import com.trailblazer.fabric.networking.payload.s2c.PathDataSyncPayload;
import com.trailblazer.fabric.networking.payload.s2c.PathDeletedPayload;
import com.trailblazer.fabric.networking.payload.s2c.SharedPathPayload;
import com.trailblazer.fabric.networking.payload.s2c.StopLivePathPayload;
import com.trailblazer.fabric.networking.payload.s2c.PathActionResultPayload;
import com.trailblazer.fabric.networking.payload.c2s.StartRecordingPayload;
import com.trailblazer.fabric.networking.payload.c2s.StopRecordingPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Contains identifiers for all custom network packets used by the mod.
 */
public class TrailblazerNetworking {

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().register(PathDataSyncPayload.TYPE, PathDataSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HideAllPathsPayload.TYPE, HideAllPathsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LivePathUpdatePayload.TYPE, LivePathUpdatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StopLivePathPayload.TYPE, StopLivePathPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SharedPathPayload.TYPE, SharedPathPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PathDeletedPayload.TYPE, PathDeletedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PathActionResultPayload.TYPE, PathActionResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(com.trailblazer.fabric.networking.payload.s2c.StartRecordingPayload.TYPE, com.trailblazer.fabric.networking.payload.s2c.StartRecordingPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DeletePathPayload.TYPE, DeletePathPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PathActionAckPayload.TYPE, PathActionAckPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SharePathRequestPayload.TYPE, SharePathRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UpdatePathMetadataPayload.TYPE, UpdatePathMetadataPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StartRecordingPayload.TYPE, StartRecordingPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StopRecordingPayload.TYPE, StopRecordingPayload.CODEC);
    }
}
