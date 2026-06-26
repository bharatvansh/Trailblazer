package com.trailblazer.fabric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trailblazer.fabric.commands.TrailblazerCommand;
import com.trailblazer.fabric.config.TrailblazerClientConfig;
import com.trailblazer.fabric.networking.ClientPacketHandler;
import com.trailblazer.fabric.networking.ServerIntegrationManager;
import com.trailblazer.fabric.networking.TrailblazerNetworking;
import com.trailblazer.fabric.networking.payload.c2s.HandshakePayload;
import com.trailblazer.fabric.persistence.PathPersistenceManager;
import com.trailblazer.fabric.rendering.PathRenderer;
import com.trailblazer.fabric.ui.RecordingOverlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.level.storage.LevelResource;


public class TrailblazerFabricClient implements ClientModInitializer {

    public static final String MOD_ID = "trailblazer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ClientPathManager clientPathManager;
    private PathRenderer pathRenderer;
    private RenderSettingsManager renderSettingsManager;
    private TrailblazerClientConfig config;
    private PathPersistenceManager persistence;
    private ServerIntegrationManager serverIntegration;
    private long lastAutosaveMs = 0L;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Trailblazer client...");
        LOGGER.info("Trailblazer loaded and fixed");

        this.clientPathManager = new ClientPathManager();
        this.renderSettingsManager = new RenderSettingsManager();
        this.pathRenderer = new PathRenderer(clientPathManager, renderSettingsManager);
        this.config = TrailblazerClientConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        this.persistence = new PathPersistenceManager(clientPathManager, config);
        this.serverIntegration = new ServerIntegrationManager();
        ServerIntegrationBridge.SERVER_INTEGRATION = serverIntegration;
        serverIntegration.registerLifecycle();
        clientPathManager.attachPersistence(persistence, config.maxPointsPerPath);

        pathRenderer.initialize();
        KeyBindingManager.initialize(renderSettingsManager, clientPathManager);
        TrailblazerCommand.register(clientPathManager, renderSettingsManager);
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            TrailblazerCommand.register(clientPathManager, renderSettingsManager);
        });
        TrailblazerNetworking.registerPayloadTypes();
        LOGGER.info("Registered Trailblazer networking payload types (including recording payloads)");
        ClientPacketHandler.registerS2CPackets(clientPathManager);
        if (config.recordingOverlayEnabled) {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "recording_overlay"),
                new RecordingOverlay(clientPathManager)
            );
        }

        registerWorldLifecycle();
        registerClientTick();

        registerHandshakeSender();

        LOGGER.info("Trailblazer client initialized.");
    }

    private void registerHandshakeSender() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                if (ClientPlayNetworking.canSend(HandshakePayload.TYPE)) {
                    LOGGER.info("Sending Trailblazer handshake to server...");
                    ClientPlayNetworking.send(new HandshakePayload());
                }
            });
        });
    }

    private void registerWorldLifecycle() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                ClientPacketHandler.resetReliableActionState();
                clientPathManager.setLocalPlayerUuid(client.getUser() != null ? client.getUser().getProfileId() : null);
                clientPathManager.applyServerSync(java.util.Collections.emptyList());
                clientPathManager.clearLocalPaths();
                if (client.getSingleplayerServer() != null) {
                    // Use the authoritative save path from the integrated server to avoid folder/display name mismatches
                    java.nio.file.Path worldPath = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
                    persistence.setWorldDirectory(worldPath);
                    // Singleplayer: local paths are part of the same world; keep visible on load
                    persistence.setDefaultVisibleOnLoad(true);
                    persistence.loadAll();
                } else {
                    String serverKey = handler.getConnection().getRemoteAddress().toString().replaceAll("[^a-zA-Z0-9-_]", "_");
                    java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                            .resolve("trailblazer_client_servers")
                            .resolve(serverKey);
                    persistence.setWorldDirectory(dir);
                    // Multiplayer: avoid auto-showing local paths from previous sessions/other worlds
                    persistence.setDefaultVisibleOnLoad(false);
                    persistence.loadAll();
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                ClientPacketHandler.resetReliableActionState();
                clientPathManager.applyServerSync(java.util.Collections.emptyList());
                clientPathManager.setLocalPlayerUuid(null);
                persistence.saveAll();
                clientPathManager.clearLocalPaths();
                persistence.setWorldDirectory(null);
                config.save(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
            });
        });
    }

    private void registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (clientPathManager != null) {
                clientPathManager.tickRecording(client);
            }
            // periodic autosave
            if (config.autosaveIntervalSeconds > 0) {
                long now = System.currentTimeMillis();
                if (now - lastAutosaveMs >= config.autosaveIntervalSeconds * 1000L) {
                    lastAutosaveMs = now;
                    persistence.saveDirty();
                }
            }
        });
    }
}