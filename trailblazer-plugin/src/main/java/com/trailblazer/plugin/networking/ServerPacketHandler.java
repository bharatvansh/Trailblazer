package com.trailblazer.plugin.networking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.trailblazer.api.PathData;
import com.trailblazer.api.Vector3d;
import com.trailblazer.plugin.PathDataManager;
import com.trailblazer.plugin.TrailblazerPlugin;
import com.trailblazer.plugin.networking.payload.c2s.HandshakePayload;
import com.trailblazer.plugin.networking.payload.c2s.StartRecordingPayload;
import com.trailblazer.plugin.networking.payload.c2s.StopRecordingPayload;
import com.trailblazer.plugin.networking.payload.s2c.HideAllPathsPayload;
import com.trailblazer.plugin.networking.payload.s2c.LivePathUpdatePayload;
import com.trailblazer.plugin.networking.payload.s2c.PathDataSyncPayload;
import com.trailblazer.plugin.networking.payload.s2c.PathDeletedPayload;
import com.trailblazer.plugin.networking.payload.s2c.SharePathPayload;
import com.trailblazer.plugin.networking.payload.s2c.StopLivePathPayload;
import com.trailblazer.plugin.networking.payload.s2c.PathActionResultPayload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ServerPacketHandler implements Listener, PluginMessageListener {

    private final TrailblazerPlugin plugin;
    private final Gson gson = new Gson();
    private final Set<UUID> moddedPlayers = new HashSet<>();
    private static final String UPDATE_METADATA_CHANNEL = "trailblazer:update_path_metadata";
    private static final String SHARE_PATH_WITH_PLAYERS_CHANNEL = "trailblazer:share_path_with_players";
    private static final String SHARE_REQUEST_CHANNEL = "trailblazer:share_request";
    private static final String SAVE_PATH_CHANNEL = "trailblazer:save_path";
    private static final String ACTION_ACK_CHANNEL = "trailblazer:path_action_ack";
    private static final long RESEND_INTERVAL_TICKS = 40L;
    private static final long RESEND_INTERVAL_MS = 2000L;
    private static final int MAX_RETRY_ATTEMPTS = 5;

    /** Defensive cap against malicious/buggy clients attempting huge recipient fan-outs. */
    private static final int MAX_SHARE_TARGETS = 64;

    private final PathDataManager dataManager;
    private final Map<UUID, ReliableMessageState> reliableStates = new ConcurrentHashMap<>();

    public ServerPacketHandler(TrailblazerPlugin plugin) {
        this.plugin = plugin;
        this.dataManager = plugin.getPathDataManager();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PathDataSyncPayload.CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, HideAllPathsPayload.CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, LivePathUpdatePayload.CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, StopLivePathPayload.CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SharePathPayload.CHANNEL_NAME);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PathDeletedPayload.CHANNEL_NAME);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PathActionResultPayload.CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, com.trailblazer.plugin.networking.payload.s2c.StartRecordingPayload.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, HandshakePayload.CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SHARE_REQUEST_CHANNEL, this);
    plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SAVE_PATH_CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "trailblazer:delete_path", this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, UPDATE_METADATA_CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SHARE_PATH_WITH_PLAYERS_CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ACTION_ACK_CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, StartRecordingPayload.CHANNEL, this);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, StopRecordingPayload.CHANNEL, this);

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::resendPendingActionResults, RESEND_INTERVAL_TICKS, RESEND_INTERVAL_TICKS);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (channel.equalsIgnoreCase(ACTION_ACK_CHANNEL)) {
            handleActionAck(player, message);
            return;
        }

        plugin.getLogger().info("Received plugin message on channel: " + channel + " from player: " + player.getName());

        if (channel.equalsIgnoreCase(HandshakePayload.CHANNEL)) {
            moddedPlayers.add(player.getUniqueId());
            plugin.getLogger().info("Received HandshakePayload from " + player.getName() + " - modded client detected");
            plugin.getLogger().info("Modded player " + player.getName() + " can now use server-side recording features");

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                java.util.UUID worldUid = player.getWorld().getUID();
                plugin.getLogger().info("Loading paths for " + player.getName() + " in world " + worldUid);
                List<PathData> allPaths = dataManager.loadPaths(worldUid, player.getUniqueId());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sendAllPathData(player, allPaths);
                    if (!allPaths.isEmpty()) {
                        TrailblazerPlugin.getPluginLogger().info("Synced " + allPaths.size() + " existing path(s) to " + player.getName());
                    } else {
                        TrailblazerPlugin.getPluginLogger().info("No existing paths for " + player.getName() + " in world " + worldUid);
                    }
                });
            });
            return;
        }

        if (channel.equalsIgnoreCase("trailblazer:delete_path")) {
            try {
                UUID pathId;
                if (message.length == 16) {
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(message);
                    long msb = bb.getLong();
                    long lsb = bb.getLong();
                    pathId = new UUID(msb, lsb);
                } else {
                    String raw = new String(message, java.nio.charset.StandardCharsets.UTF_8).trim();
                    pathId = UUID.fromString(raw);
                }
                java.util.UUID worldUid = player.getWorld().getUID();
                List<PathData> paths = dataManager.loadPaths(worldUid, player.getUniqueId());
                boolean owned = paths.stream().anyMatch(p -> p.getPathId().equals(pathId) && p.getOwnerUUID().equals(player.getUniqueId()));
                if (owned) {
                    boolean removed = dataManager.deletePath(worldUid, player.getUniqueId(), pathId);
                    if (removed) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sendPathDeleted(player, pathId);
                            sendActionResult(player, "delete", pathId, true, "Path deleted successfully.", null);
                        });
                    } else {
                        sendActionResult(player, "delete", pathId, false, "Failed to delete path.", null);
                    }
                } else {
                    sendActionResult(player, "delete", pathId, false, "You do not own this path.", null);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to process delete path payload from " + player.getName() + ": " + e.getMessage());
                sendActionResult(player, "delete", null, false, "An error occurred while deleting the path.", null);
            }
            return;
        }

        if (channel.equalsIgnoreCase(UPDATE_METADATA_CHANNEL)) {
            handleMetadataUpdate(player, message);
            return;
        }

        if (channel.equalsIgnoreCase(SHARE_PATH_WITH_PLAYERS_CHANNEL)) {
            handleSharePathWithPlayers(player, message);
            return;
        }

        if (channel.equalsIgnoreCase(SHARE_REQUEST_CHANNEL)) {
            handleShareRequest(player, message);
            return;
        }

        if (channel.equalsIgnoreCase(SAVE_PATH_CHANNEL)) {
            handleSaveRequest(player, message);
            return;
        }

        if (channel.equalsIgnoreCase(StartRecordingPayload.CHANNEL)) {
            handleStartRecording(player, message);
            return;
        }

        if (channel.equalsIgnoreCase(StopRecordingPayload.CHANNEL)) {
            handleStopRecording(player, message);
            return;
        }
    }

    /**
     * Convenience method to send a single path. Wraps it in a list.
     * Used by the /path show command.
     * @param player The player to send data to.
     * @param path The single path to send.
     */
    public void sendPathData(Player player, PathData path) {
        sendAllPathData(player, Collections.singletonList(path));
    }

    /**
     * The core sending method. Serializes a list of paths to JSON and sends it to the client.
     * @param player The player to send data to.
     * @param paths The list of paths to send.
     */
    public void sendAllPathData(Player player, List<PathData> paths) {
        if (!isModdedPlayer(player)) {
            return;
        }

        List<PathData> safePaths = paths != null ? paths : Collections.emptyList();
        String json = gson.toJson(safePaths);
        PathDataSyncPayload payload = new PathDataSyncPayload(json);
        player.sendPluginMessage(plugin, PathDataSyncPayload.CHANNEL, payload.toBytes());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        moddedPlayers.remove(playerId);
        reliableStates.remove(playerId);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        moddedPlayers.remove(playerId);
        reliableStates.remove(playerId);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline() || !isModdedPlayer(player)) {
            return;
        }
        // Immediately hide any client-rendered paths to avoid cross-world visuals lingering
        sendHideAllPaths(player);

        // Load and sync paths scoped to the new world's UUID
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID worldUid = player.getWorld().getUID();
            List<PathData> allPaths = dataManager.loadPaths(worldUid, player.getUniqueId());
            plugin.getServer().getScheduler().runTask(plugin, () -> sendAllPathData(player, allPaths));
        });
    }

    public boolean isModdedPlayer(Player player) {
        return moddedPlayers.contains(player.getUniqueId());
    }

    /**
     * Sends a signal to the client to hide all currently visible paths.
     * @param player The player to send the signal to.
     */
    public void sendHideAllPaths(Player player) {
        if (!isModdedPlayer(player)) return;
        player.sendPluginMessage(plugin, HideAllPathsPayload.CHANNEL, new HideAllPathsPayload().toBytes());
    }

    /**
     * Sends the current list of points for a path being recorded.
     * @param player The player to send the update to.
     * @param points The list of points to send.
     */
    public void sendLivePathUpdate(Player player, List<Vector3d> points) {
        if (!isModdedPlayer(player) || points == null) {
            return;
        }
        LivePathUpdatePayload payload = new LivePathUpdatePayload(points);
        player.sendPluginMessage(plugin, LivePathUpdatePayload.CHANNEL, payload.toBytes());
    }

    /**
     * Sends a signal to the client to stop rendering the live path.
     * @param player The player to send the signal to.
     */
    public void sendStopLivePath(Player player) {
        if (!isModdedPlayer(player)) {
            return;
        }
        StopLivePathPayload payload = new StopLivePathPayload();
        player.sendPluginMessage(plugin, StopLivePathPayload.CHANNEL, payload.toBytes());
    }

    public void sendPathDeleted(Player player, UUID pathId) {
        if (!isModdedPlayer(player)) {
            return;
        }
        PathDeletedPayload payload = new PathDeletedPayload(pathId);
        player.sendPluginMessage(plugin, PathDeletedPayload.CHANNEL_NAME, payload.toBytes());
    }

    public void sendSharePath(Player targetPlayer, PathData pathData) {
        // The check for whether the player is modded is now handled in PathCommand.
        // This method is now only responsible for creating and sending the packet to modded clients.
        SharePathPayload payload = new SharePathPayload(pathData);
        targetPlayer.sendPluginMessage(plugin, SharePathPayload.CHANNEL_NAME, payload.toBytes());
    }

    private void sendActionResult(Player player, String action, UUID pathId, boolean success, String message, PathData updated) {
        if (player == null || !player.isOnline() || !isModdedPlayer(player)) {
            return;
        }

        ReliableMessageState state = reliableStates.computeIfAbsent(player.getUniqueId(), id -> new ReliableMessageState());
        long sequence = state.nextSequence.getAndIncrement();
        PendingActionResult pending = new PendingActionResult(sequence, action, pathId, success, message, updated);
        state.pending.put(sequence, pending);
        dispatchPendingResult(player, state, pending);
    }

    private void dispatchPendingResult(Player player, ReliableMessageState state, PendingActionResult pending) {
        long now = System.currentTimeMillis();
        byte[] bytes = pending.toBytes(state.lastAck);
        player.sendPluginMessage(plugin, PathActionResultPayload.CHANNEL, bytes);
        pending.markDispatched(now);
    }

    private void resendPendingActionResults() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, ReliableMessageState> entry : reliableStates.entrySet()) {
            Player target = plugin.getServer().getPlayer(entry.getKey());
            if (target == null || !target.isOnline() || !isModdedPlayer(target)) {
                continue;
            }

            ReliableMessageState state = entry.getValue();
            if (state.pending.isEmpty()) {
                continue;
            }

            for (PendingActionResult pending : new ArrayList<>(state.pending.values())) {
                if (now - pending.getLastSentAtMs() < RESEND_INTERVAL_MS) {
                    continue;
                }

                if (pending.getAttempts() >= MAX_RETRY_ATTEMPTS) {
                    state.pending.remove(pending.getSequence());
                    plugin.getLogger().warning("Dropping path action result " + pending.getSequence() + " for " + target.getName() + " after " + pending.getAttempts() + " attempts without acknowledgment.");
                    continue;
                }

                byte[] bytes = pending.toBytes(state.lastAck);
                target.sendPluginMessage(plugin, PathActionResultPayload.CHANNEL, bytes);
                pending.markDispatched(now);
            }

            if (state.pending.isEmpty()) {
                reliableStates.remove(entry.getKey(), state);
            }
        }
    }

    private void handleActionAck(Player player, byte[] message) {
        if (message == null || message.length < Long.BYTES) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(message);
        long acknowledged = buffer.getLong();
        if (acknowledged <= 0) {
            return;
        }

        ReliableMessageState state = reliableStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        state.lastAck = Math.max(state.lastAck, acknowledged);
        state.pending.entrySet().removeIf(entry -> entry.getKey() <= acknowledged);
        if (state.pending.isEmpty()) {
            reliableStates.remove(player.getUniqueId(), state);
        }
    }

    private static final class ReliableMessageState {
        private final AtomicLong nextSequence = new AtomicLong(1L);
        private final ConcurrentSkipListMap<Long, PendingActionResult> pending = new ConcurrentSkipListMap<>();
        private volatile long lastAck = 0L;
    }

    private static final class PendingActionResult {
        private final long sequence;
        private final String action;
        private final UUID pathId;
        private final boolean success;
        private final String message;
        private final PathData updated;
        private volatile long lastSentAtMs;
        private volatile int attempts;

        private PendingActionResult(long sequence, String action, UUID pathId, boolean success, String message, PathData updated) {
            this.sequence = sequence;
            this.action = action;
            this.pathId = pathId;
            this.success = success;
            this.message = message;
            this.updated = updated;
            this.lastSentAtMs = 0L;
            this.attempts = 0;
        }

        private byte[] toBytes(long acknowledgedSequence) {
            Long ackField = acknowledgedSequence > 0 ? acknowledgedSequence : null;
            PathActionResultPayload payload = new PathActionResultPayload(action, pathId, success, message, updated, sequence, ackField);
            return payload.toBytes();
        }

        private void markDispatched(long timestampMs) {
            this.attempts++;
            this.lastSentAtMs = timestampMs;
        }

        private long getSequence() {
            return sequence;
        }

        private int getAttempts() {
            return attempts;
        }

        private long getLastSentAtMs() {
            return lastSentAtMs;
        }
    }

    private void handleSharePathWithPlayers(Player sender, byte[] message) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(message);
            UUID pathId = new UUID(buffer.getLong(), buffer.getLong());
            int playerCount = readVarInt(buffer);
            if (playerCount < 0 || playerCount > MAX_SHARE_TARGETS) {
                throw new IllegalArgumentException("Invalid recipient count: " + playerCount);
            }
            List<UUID> playerIds = new ArrayList<>();
            for (int i = 0; i < playerCount; i++) {
                playerIds.add(new UUID(buffer.getLong(), buffer.getLong()));
            }

            // Shared copies are scoped to the *source* world. The recipient may be elsewhere (or offline),
            // but persistence must follow the world the path actually belongs to.
            java.util.UUID senderWorldUid = sender.getWorld().getUID();
            List<PathData> paths = dataManager.loadPaths(senderWorldUid, sender.getUniqueId());
            paths.stream()
                .filter(p -> p.getPathId().equals(pathId) && p.getOwnerUUID().equals(sender.getUniqueId()))
                .findFirst()
                .ifPresentOrElse(serverPath -> {
                    List<String> newlyShared = new ArrayList<>();
                    List<String> alreadyHad = new ArrayList<>();

                    for (UUID targetPlayerId : playerIds) {
                        if (targetPlayerId == null || targetPlayerId.equals(sender.getUniqueId())) {
                            continue;
                        }

                        Player targetOnline = plugin.getServer().getPlayer(targetPlayerId);
                        String targetName = (targetOnline != null && targetOnline.isOnline())
                                ? targetOnline.getName()
                                : resolvePlayerName(targetPlayerId);

                        if (serverPath.isSharedWith(targetPlayerId)) {
                            alreadyHad.add(targetName);
                            continue;
                        }

                        serverPath.addShare(targetPlayerId);
                        newlyShared.add(targetName);

                        if (targetOnline != null && targetOnline.isOnline()) {
                            java.util.UUID targetWorldUid = targetOnline.getWorld().getUID();
                            if (!senderWorldUid.equals(targetWorldUid)) {
                                targetOnline.sendMessage(Component.text(sender.getName() + " shared a path with you: " + serverPath.getPathName() + ". It will appear when you join their world.", NamedTextColor.AQUA));
                                continue;
                            }

                            boolean targetIsModded = isModdedPlayer(targetOnline);
                            if (targetIsModded) {
                                sendSharePath(targetOnline, serverPath);
                                targetOnline.sendMessage(Component.text(sender.getName() + " has shared a path with you: " + serverPath.getPathName(), NamedTextColor.AQUA));
                            } else {
                                plugin.getPathRendererManager().startRendering(targetOnline, serverPath);
                                targetOnline.sendMessage(Component.text(sender.getName() + " has shared a path with you: " + serverPath.getPathName(), NamedTextColor.AQUA));
                                targetOnline.sendMessage(Component.text("It is now being displayed. Use '/path hide' to hide it or '/path view " + serverPath.getPathName() + "' to see it again.", NamedTextColor.GRAY));
                            }
                        }
                    }

                    if (!newlyShared.isEmpty()) {
                        dataManager.savePath(senderWorldUid, serverPath);
                    }

                    boolean success = !newlyShared.isEmpty();
                    StringBuilder response = new StringBuilder();
                    if (success) {
                        response.append("Shared path with ").append(String.join(", ", newlyShared)).append('.');
                    }
                    if (!alreadyHad.isEmpty()) {
                        if (response.length() > 0) {
                            response.append(' ');
                        }
                        response.append(String.join(", ", alreadyHad)).append(" already had access.");
                    }
                    if (response.length() == 0) {
                        response.append("Selected players already have this path.");
                    }

                    sendActionResult(sender, "share", pathId, success, response.toString(), null);
                }, () -> {
                    sendActionResult(sender, "share", pathId, false, "You do not own this path.", null);
                });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process share path with players payload from " + sender.getName() + ": " + e.getMessage());
            sendActionResult(sender, "share", null, false, "An error occurred while sharing the path.", null);
        }
    }

    private void handleMetadataUpdate(Player player, byte[] message) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(message);
            UUID pathId = new UUID(buffer.getLong(), buffer.getLong());
            int color = buffer.getInt();
            int nameLength = readVarInt(buffer);
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            String newName = new String(nameBytes, StandardCharsets.UTF_8);

            java.util.UUID worldUid = player.getWorld().getUID();
            List<PathData> updatedPaths = dataManager.updateMetadata(worldUid, player.getUniqueId(), pathId, newName, color);
            if (updatedPaths != null) {
                PathData updatedPath = updatedPaths.stream().filter(p -> p.getPathId().equals(pathId)).findFirst().orElse(null);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sendAllPathData(player, updatedPaths);
                    sendActionResult(player, "update_metadata", pathId, true, "Path updated successfully.", updatedPath);
                });
            } else {
                sendActionResult(player, "update_metadata", pathId, false, "Failed to update path. You may not be the owner or the name is taken.", null);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process update metadata payload from " + player.getName() + ": " + e.getMessage());
            sendActionResult(player, "update_metadata", null, false, "An error occurred while updating the path.", null);
        }
    }

    private void handleShareRequest(Player sender, byte[] message) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(message);
            int targetCount = readVarInt(buffer);
            if (targetCount < 0 || targetCount > MAX_SHARE_TARGETS) {
                throw new IllegalArgumentException("Invalid recipient count: " + targetCount);
            }
            List<UUID> targets = new ArrayList<>(targetCount);
            for (int i = 0; i < targetCount; i++) {
                targets.add(new UUID(buffer.getLong(), buffer.getLong()));
            }
            String json = readString(buffer);
            PathData clientPath = gson.fromJson(json, PathData.class);
            if (clientPath == null || !PathDataManager.isValidPathData(clientPath)) {
                throw new IllegalArgumentException("Received invalid shared path data");
            }

            // Trust boundary: a client may spoof owner fields in JSON. For sharing, require the sender to be the owner
            if (!sender.getUniqueId().equals(clientPath.getOwnerUUID())) {
                sendActionResult(sender, "share", null, false, "You can only share paths that you own.", null);
                return;
            }

            java.util.UUID senderWorldUid = sender.getWorld().getUID();
            List<PathData> allPaths = dataManager.loadPaths(senderWorldUid, sender.getUniqueId());
            PathData serverPath = allPaths.stream().filter(p -> p.getPathId().equals(clientPath.getPathId())).findFirst().orElse(null);
            
            if (serverPath == null) {
                sendActionResult(sender, "share", null, false, "Path not found on server.", null);
                return;
            }

            List<String> newlyShared = new ArrayList<>();
            List<String> alreadyHad = new ArrayList<>();
            int moddedDelivered = 0;

            for (UUID targetId : targets) {
                if (targetId == null || targetId.equals(sender.getUniqueId())) {
                    continue;
                }

                Player targetOnline = plugin.getServer().getPlayer(targetId);
                String targetName = (targetOnline != null && targetOnline.isOnline())
                        ? targetOnline.getName()
                        : resolvePlayerName(targetId);

                // Update the sharedWith list on the single server path
                if (serverPath.isSharedWith(targetId)) {
                    alreadyHad.add(targetName);
                    continue;
                }

                serverPath.addShare(targetId);
                newlyShared.add(targetName);

                if (targetOnline != null && targetOnline.isOnline()) {
                    java.util.UUID targetWorldUid = targetOnline.getWorld().getUID();
                    if (!senderWorldUid.equals(targetWorldUid)) {
                        targetOnline.sendMessage(Component.text(sender.getName() + " shared a path with you: " + serverPath.getPathName() + ". It will appear when you join their world.", NamedTextColor.AQUA));
                        continue;
                    }

                    boolean targetIsModded = isModdedPlayer(targetOnline);
                    if (targetIsModded) {
                        // Deliver to modded clients via payload
                        sendSharePath(targetOnline, serverPath);
                        targetOnline.sendMessage(Component.text(sender.getName() + " has shared a path with you: " + serverPath.getPathName(), NamedTextColor.AQUA));
                        moddedDelivered++;
                    } else {
                        // Fallback: start server-side particle rendering and notify
                        plugin.getPathRendererManager().startRendering(targetOnline, serverPath);
                        targetOnline.sendMessage(Component.text(sender.getName() + " has shared a path with you: " + serverPath.getPathName(), NamedTextColor.AQUA));
                        targetOnline.sendMessage(Component.text("It is now being displayed. Use '/path hide' to hide it or '/path view " + serverPath.getPathName() + "' to see it again.", NamedTextColor.GRAY));
                    }
                }
            }

            // Save the updated path if any new shares were added
            if (!newlyShared.isEmpty()) {
                dataManager.savePath(senderWorldUid, serverPath);
            }

            boolean success = !newlyShared.isEmpty() || moddedDelivered > 0;
            StringBuilder response = new StringBuilder();
            if (!newlyShared.isEmpty()) {
                response.append("Shared path with ").append(String.join(", ", newlyShared)).append('.');
            }
            if (!alreadyHad.isEmpty()) {
                if (response.length() > 0) response.append(' ');
                response.append(String.join(", ", alreadyHad)).append(" already had access.");
            }
            if (response.length() == 0) {
                response.append(moddedDelivered > 0 ? ("Shared path with " + moddedDelivered + " player(s).") : "Selected players already have this path.");
            }

            sendActionResult(sender, "share", serverPath.getPathId(), success, response.toString(), null);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to process share request payload from " + sender.getName() + ": " + ex.getMessage());
            sendActionResult(sender, "share", null, false, "An error occurred while sharing the path.", null);
        }
    }

    private void handleSaveRequest(Player sender, byte[] message) {
        try {
            // The incoming message for a save request is the raw JSON string
            String json = new String(message, StandardCharsets.UTF_8);
            PathData clientPath = gson.fromJson(json, PathData.class);
            if (clientPath == null || !PathDataManager.isValidPathData(clientPath)) {
                sendActionResult(sender, "save", null, false, "Invalid path data.", null);
                return;
            }

            // Server is authoritative: generate a new UUID for the persistent copy.
            UUID serverPathId = UUID.randomUUID();
            // The owner is always the player who sent the request.
            UUID ownerId = sender.getUniqueId();
            String ownerName = sender.getName();

            // Create the new authoritative path data.
            PathData serverCopy = new PathData(
                serverPathId,
                clientPath.getPathName(),
                ownerId,
                ownerName,
                System.currentTimeMillis(),
                clientPath.getDimension(),
                new ArrayList<>(clientPath.getPoints()),
                clientPath.getColorArgb()
            );

            // Preserve the original client-side ID as the origin ID for lineage tracking.
            serverCopy.setOrigin(clientPath.getPathId(), clientPath.getOwnerUUID(), clientPath.getOwnerName());

            // Persist the new server-authoritative copy.
            java.util.UUID worldUid = sender.getWorld().getUID();
            dataManager.savePath(worldUid, serverCopy);

            // Send a success result back to the client with the new, authoritative path data.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sendActionResult(sender, "save", serverCopy.getPathId(), true, "Path saved successfully.", serverCopy);
            });

        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to process save path payload from " + sender.getName() + ": " + ex.getMessage());
            sendActionResult(sender, "save", null, false, "An error occurred while saving the path.", null);
        }
    }

    private static int readVarInt(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            throw new IllegalStateException("Buffer underflow reading VarInt");
        }
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = buffer.get();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) {
                throw new IllegalStateException("VarInt too big");
            }
        } while ((read & 0x80) != 0);

        return result;
    }

    private String readString(ByteBuffer buffer) {
        int length = readVarInt(buffer);
        if (length < 0 || length > 1_048_576) {
            throw new IllegalStateException("Invalid string length: " + length);
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private String resolvePlayerName(UUID playerId) {
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerId);
        if (offline != null && offline.getName() != null && !offline.getName().isBlank()) {
            return offline.getName();
        }
        return "Player";
    }

    /**
     * Removes duplicate paths with the same origin lineage from the provided list.
     * <p>
     * This method removes all but the first occurrence of any path (owned or shared copy)
     * that shares the same originPathId, regardless of whether the player owns the original
     * or a shared copy. This is broader than the previous implementation, which only removed
     * shared copies if the player already owned the origin lineage.
     * <p>
     * Assumes all paths in the list are owned by the player (as filtered by loadPaths).
     * For each origin, keeps the first path encountered and removes subsequent duplicates.
     * This prevents multiple copies of the same path lineage from appearing in the player's path list.
     * <p>
     * IMPORTANT: This method also deletes duplicate path files from disk to prevent them from


    /**
     * Handles a client request to start recording a path on the server.
     */
    private void handleStartRecording(Player player, byte[] data) {
        plugin.getLogger().info("Received start recording request from " + player.getName());
        try {
            StartRecordingPayload payload = StartRecordingPayload.fromBytes(data);
            String pathName = payload.getPathName();
            plugin.getLogger().info("Parsed start recording payload: pathName=" + (pathName != null ? pathName : "null"));
            
            boolean started = plugin.getRecordingManager().startRecording(player, pathName);
            if (started) {
                // Notify the client that recording has started for UI feedback
                var activeRecording = plugin.getRecordingManager().getActive(player.getUniqueId());
                plugin.getLogger().info("Recording started successfully for " + player.getName() + 
                    " with name: " + (activeRecording != null ? activeRecording.getName() : "(unknown)"));
                
                if (activeRecording != null && isModdedPlayer(player)) {
                    plugin.getLogger().info("Sending StartRecordingPayload to modded client " + player.getName());
                    sendRecordingStarted(player, activeRecording);
                } else {
                    plugin.getLogger().info("Not sending StartRecordingPayload: activeRecording=" + (activeRecording != null) + ", isModded=" + isModdedPlayer(player));
                }
            } else {
                plugin.getLogger().warning("Failed to start recording for " + player.getName() + " - may already be recording");
                player.sendMessage(Component.text("Failed to start recording. You may already be recording.", NamedTextColor.RED));
            }
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to process start recording request from " + player.getName(), e);
            player.sendMessage(Component.text("An error occurred while starting recording.", NamedTextColor.RED));
        }
    }

    /**
     * Handles a client request to stop recording a path on the server.
     */
    private void handleStopRecording(Player player, byte[] data) {
        try {
            StopRecordingPayload payload = StopRecordingPayload.fromBytes(data);
            boolean save = payload.shouldSave();
            
            PathData result = plugin.getRecordingManager().stopRecording(player, save);
            if (result != null) {
                // Send stop signal to client
                if (isModdedPlayer(player)) {
                    sendStopLivePathPayload(player);
                }
                
                // Reload and sync all paths to include the new one
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    UUID worldUid = player.getWorld().getUID();
                    List<PathData> allPaths = dataManager.loadPaths(worldUid, player.getUniqueId());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sendAllPathData(player, allPaths);
                        player.sendMessage(Component.text("Path saved successfully!", NamedTextColor.GREEN));
                    });
                });
                plugin.getLogger().info("Stopped and saved recording for " + player.getName());
            } else {
                if (save) {
                    player.sendMessage(Component.text("No active recording to save or path was too short.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text("Recording cancelled.", NamedTextColor.GRAY));
                }
                if (isModdedPlayer(player)) {
                    sendStopLivePathPayload(player);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process stop recording request from " + player.getName() + ": " + e.getMessage());
            player.sendMessage(Component.text("An error occurred while stopping recording.", NamedTextColor.RED));
        }
    }

    /**
     * Sends a StartRecordingPayload to the client to notify them that recording has started.
     * This updates the client's UI state to show recording indicators.
     */
    public void sendRecordingStarted(Player player, com.trailblazer.plugin.RecordingManager.ActiveRecording recording) {
        if (!isModdedPlayer(player) || recording == null) {
            plugin.getLogger().warning("sendRecordingStarted called but player is not modded or recording is null");
            return;
        }
        
        UUID pathId = recording.getPathId(); // Use the actual pathId from the recording
        // Get dimension key - try to use the proper dimension string
        String dimension;
        try {
            dimension = recording.getWorld().key().asString();
        } catch (Throwable t) {
            dimension = recording.getWorld().getName();
        }
        
        plugin.getLogger().info("Sending StartRecordingPayload: pathId=" + pathId + ", name=" + recording.getName() + ", dimension=" + dimension);
        
        com.trailblazer.plugin.networking.payload.s2c.StartRecordingPayload payload = 
            new com.trailblazer.plugin.networking.payload.s2c.StartRecordingPayload(
                pathId, recording.getName(), dimension);
        player.sendPluginMessage(plugin, com.trailblazer.plugin.networking.payload.s2c.StartRecordingPayload.CHANNEL, payload.toBytes());
        plugin.getLogger().info("StartRecordingPayload sent successfully to " + player.getName());
    }

    /**
     * Sends a stop live path signal to the client.
     */
    private void sendStopLivePathPayload(Player player) {
        if (!isModdedPlayer(player)) {
            return;
        }
        // Send empty payload (0 bytes) to match client's CODEC expectation
        player.sendPluginMessage(plugin, StopLivePathPayload.CHANNEL, new StopLivePathPayload().toBytes());
    }
}