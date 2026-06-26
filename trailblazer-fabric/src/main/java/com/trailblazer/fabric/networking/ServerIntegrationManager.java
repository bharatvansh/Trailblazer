package com.trailblazer.fabric.networking;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Lightweight detection of whether the connected server supports Trailblazer.
 * Currently heuristic: if we can send the handshake payload after join, mark supported.
 * Future: read a dedicated S2C capability advertisement.
 */
public class ServerIntegrationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("trailblazer-serverint");
    private final AtomicBoolean serverSupported = new AtomicBoolean(false);
    private volatile int capabilityMask = 0;

    public void registerLifecycle() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverSupported.set(ClientPlayNetworking.canSend(com.trailblazer.fabric.networking.payload.c2s.HandshakePayload.TYPE));
            LOGGER.info("Trailblazer server support detected? {}", serverSupported.get());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverSupported.set(false);
            capabilityMask = 0;
        });
    }

    public boolean isServerSupported() { return serverSupported.get(); }
    public int getCapabilityMask() { return capabilityMask; }
    public void setCapabilityMask(int mask) { this.capabilityMask = mask; }
}
