package com.trailblazer.fabric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.trailblazer.api.PathData;
import com.trailblazer.api.Vector3d;
import com.trailblazer.fabric.networking.payload.c2s.StartRecordingPayload;
import com.trailblazer.fabric.networking.payload.c2s.StopRecordingPayload;
import com.trailblazer.fabric.persistence.PathPersistenceManager;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Manages client-side path storage and recording.
 */
public class ClientPathManager {
    private static final double TRAIL_Y_OFFSET = 1.2; // Offset to raise trail above ground level to prevent it from being hidden inside blocks
    
    public enum PathOrigin {
        LOCAL,
        SERVER_OWNED,
        SERVER_SHARED
    }
    private final Map<UUID, PathData> myPaths = new HashMap<>();
    private final Map<UUID, PathData> sharedPaths = new HashMap<>();
    private final Map<UUID, PathOrigin> pathOrigins = new HashMap<>();
    private final Set<UUID> visiblePaths = new HashSet<>();
    private PathData livePath = null;
    private PathData localRecording = null;
    private boolean recording = false;
    // Server recording info (when recording is delegated to server)
    private UUID serverRecordingPathId = null;
    private String serverRecordingPathName = null;
    private Vector3d lastCapturedPoint = null;
    private PathPersistenceManager persistence;
    private int maxPointsPerPath = 5000;
    private UUID localPlayerUuid;
    private int nextPathIndex = 1;

    public void attachPersistence(PathPersistenceManager persistence, int maxPointsPerPath) {
        this.persistence = persistence;
        this.maxPointsPerPath = maxPointsPerPath;
        recalculateNextPathIndex();
    }

    public void addMyPath(PathData path) {
        putPath(path, PathOrigin.LOCAL);
    }

    public void addSharedPath(PathData path) {
        putPath(path, PathOrigin.SERVER_SHARED);
        setPathVisible(path.getPathId()); // Make shared paths visible by default
    }

    public void removePath(UUID pathId) {
        myPaths.remove(pathId);
        sharedPaths.remove(pathId);
        visiblePaths.remove(pathId);
        pathOrigins.remove(pathId);
    }

    public void setPathVisible(UUID pathId) {
        if (myPaths.containsKey(pathId) || sharedPaths.containsKey(pathId)) {
            visiblePaths.add(pathId);
        }
    }

    public void setPathHidden(UUID pathId) {
        visiblePaths.remove(pathId);
    }

    public void togglePathVisibility(UUID pathId) {
        if (visiblePaths.contains(pathId)) {
            setPathHidden(pathId);
        } else {
            setPathVisible(pathId);
        }
    }

    public void deletePath(UUID pathId) {
        PathOrigin origin = pathOrigins.getOrDefault(pathId, PathOrigin.LOCAL);
        myPaths.remove(pathId);
        visiblePaths.remove(pathId);
        pathOrigins.remove(pathId);
        if (origin == PathOrigin.LOCAL && persistence != null) {
            persistence.deleteLocal(pathId);
        }
        recalculateNextPathIndex();
    }

    public void removeSharedPath(UUID pathId) {
        sharedPaths.remove(pathId);
        visiblePaths.remove(pathId);
        pathOrigins.remove(pathId);
    }

    public void removeServerPath(UUID pathId) {
        PathOrigin origin = pathOrigins.get(pathId);
        if (origin == PathOrigin.SERVER_OWNED) {
            myPaths.remove(pathId);
        } else if (origin == PathOrigin.SERVER_SHARED) {
            sharedPaths.remove(pathId);
        }
        visiblePaths.remove(pathId);
        pathOrigins.remove(pathId);
    }

    public boolean isPathVisible(UUID pathId) {
        return visiblePaths.contains(pathId);
    }

    public void hideAllPaths() {
        visiblePaths.clear();
    }

    /**
     * Returns the path currently being recorded, if any.
     * @return The live PathData object, or null if not recording.
     */
    public PathData getLivePath() { return livePath; }
    public PathData getLocalRecordingPath() { return localRecording; }

    /**
     * Updates the points for the live path. This is called when a {@code LivePathUpdatePayload} is received.
     * @param points The new list of points for the path.
     */
    public void updateLivePath(List<Vector3d> points) {
        if (livePath == null) {
            // If this is the first update, create a new PathData object to represent the live path.
            // We use a fixed UUID for the live path so we can easily identify it.
            UUID livePathId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            livePath = new PathData(livePathId, "LiveRecording", UUID.randomUUID(), "Me", 0L, "", points);
        } else {
            // Otherwise, just update the points.
            livePath.getPoints().clear();
            livePath.getPoints().addAll(points);
        }
        // Receiving live updates implies we are in a recording session.
        recording = true;
    }

    /**
     * Clears the live path data. Called when a {@code StopLivePathPayload} is received.
     */
    public void stopLivePath() { livePath = null; }

    // --- Recording state helpers (client-side optimistic) ---
    public boolean isRecording() {
        return recording;
    }

    public void startRecordingLocal() {
        if (recording) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            TrailblazerFabricClient.LOGGER.warn("Cannot start recording: client not ready");
            return;
        }
        TrailblazerFabricClient.LOGGER.info("Starting LOCAL recording");
        recording = true;
        UUID id = UUID.randomUUID();
        UUID ownerUuid = localPlayerUuid != null ? localPlayerUuid : (client.getUser() != null ? client.getUser().getProfileId() : UUID.randomUUID());
        String ownerName = resolveLocalPlayerName("Player");
        String dimension = client.level != null
            ? client.level.dimension().identifier().toString()
            : "minecraft:overworld";
        localRecording = new PathData(id, "Path-" + indexToLetters(nextPathIndex), ownerUuid, ownerName, System.currentTimeMillis(), dimension, new ArrayList<>());
        addMyPath(localRecording);
        setPathVisible(localRecording.getPathId());
        lastCapturedPoint = null;
        if (persistence != null) persistence.markDirty(localRecording.getPathId());
        nextPathIndex++;
    }

    public void stopRecordingLocal() {
        if (!recording) return;
        recording = false;
        if (localRecording != null && persistence != null) {
            persistence.markDirty(localRecording.getPathId());
        }
        localRecording = null;
        lastCapturedPoint = null;
    }

    public void cancelRecordingLocal() {
        if (!recording) return;
        recording = false;
        if (localRecording != null) {
            // Remove the partially recorded path (do not persist)
            UUID id = localRecording.getPathId();
            myPaths.remove(id);
            visiblePaths.remove(id);
            pathOrigins.remove(id);
            localRecording = null;
            lastCapturedPoint = null;
            recalculateNextPathIndex();
        }
    }

    // --- Server recording methods ---
    
    /**
     * Checks if recording should be delegated to the server instead of being local.
     * Returns true when connected to a multiplayer server with the Trailblazer plugin.
     * Note: We don't check canSend() here because channels might not be immediately available
     * after connection, but if server is plugin-enabled, it should support all our channels.
     */
    public boolean shouldUseServerRecording() {
        Minecraft client = Minecraft.getInstance();
        
        boolean hasBridge = ServerIntegrationBridge.SERVER_INTEGRATION != null;
        boolean serverSupported = hasBridge && ServerIntegrationBridge.SERVER_INTEGRATION.isServerSupported();
        boolean isMultiplayer = client == null || client.getSingleplayerServer() == null;
        
        // Try to check if we can send, but don't require it - if server is plugin-enabled,
        // it should support the channel even if canSend() returns false due to timing
        boolean canSendPayload = ClientPlayNetworking.canSend(StartRecordingPayload.TYPE);
        
        // If server is plugin-enabled and we're in multiplayer, use server recording
        // We trust that if handshake succeeded, server supports our channels
        boolean result = hasBridge && serverSupported && isMultiplayer;
        
        TrailblazerFabricClient.LOGGER.info("shouldUseServerRecording check: bridge={}, serverSupported={}, multiplayer={}, canSendPayload={}, finalResult={}", 
            hasBridge, serverSupported, isMultiplayer, canSendPayload, result);
        
        if (result && !canSendPayload) {
            TrailblazerFabricClient.LOGGER.warn("Server is plugin-enabled but canSend() returned false - this may be a timing issue, attempting server recording anyway");
        }
        
        return result;
    }
    
    /**
     * Sends a request to the server to start recording a path.
     * @param pathName Optional name for the path, or null for server auto-naming
     */
    public void sendStartRecordingRequest(String pathName) {
        try {
            // Always try to send - if channel isn't available, the send will handle it gracefully
            // Fabric's networking will queue or handle errors appropriately
            ClientPlayNetworking.send(new StartRecordingPayload(pathName));
            TrailblazerFabricClient.LOGGER.info("Sent start recording request to server (pathName: {})", pathName != null ? pathName : "auto");
        } catch (Exception e) {
            TrailblazerFabricClient.LOGGER.error("Failed to send start recording request to server", e);
            // Fall back to local recording if server request fails
            TrailblazerFabricClient.LOGGER.warn("Falling back to local recording due to server communication failure");
            startRecordingLocal();
        }
    }
    
    /**
     * Sends a request to the server to stop recording the current path.
     * @param save Whether to save the path (true) or discard it (false)
     */
    public void sendStopRecordingRequest(boolean save) {
        try {
            ClientPlayNetworking.send(new StopRecordingPayload(save));
            TrailblazerFabricClient.LOGGER.info("Sent stop recording request to server (save: {})", save);
        } catch (Exception e) {
            TrailblazerFabricClient.LOGGER.error("Failed to send stop recording request to server", e);
        }
    }
    
    /**
     * Called when the server notifies the client that recording has started.
     * Updates the recording state to show UI feedback without actually recording locally.
     * @param pathId The path ID assigned by the server
     * @param pathName The name of the path
     * @param dimension The dimension where recording started
     */
    public void setRecordingFromServer(UUID pathId, String pathName, String dimension) {
        recording = true;
        serverRecordingPathId = pathId;
        serverRecordingPathName = pathName;
        // Don't create localRecording - the server is handling the actual recording
        // The live path updates will come from the server via LivePathUpdatePayload
        TrailblazerFabricClient.LOGGER.info("Server recording started: {} in {}", pathName, dimension);
    }
    
    /**
     * Called when server recording stops. Clears the recording flag.
     */
    public void stopServerRecording() {
        recording = false;
        serverRecordingPathId = null;
        serverRecordingPathName = null;
        // Live path is cleared by StopLivePathPayload handler
    }
    
    /**
     * Gets the current recording path (works for both local and server recordings).
     * @return PathData for local recording, or info about server recording, or null if not recording
     */
    public PathData getRecordingPath() {
        if (localRecording != null) {
            return localRecording;
        }
        if (serverRecordingPathName != null) {
            // Server recording: return info with current points from livePath if available
            List<Vector3d> points = livePath != null ? new ArrayList<>(livePath.getPoints()) : new ArrayList<>();
            return new PathData(serverRecordingPathId != null ? serverRecordingPathId : UUID.randomUUID(), 
                serverRecordingPathName, UUID.randomUUID(), "Server", System.currentTimeMillis(), "", points);
        }
        return null;
    }

    public void clearLocalPaths() {
        recording = false;
        localRecording = null;
        lastCapturedPoint = null;
        stopLivePath();
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, PathOrigin> entry : pathOrigins.entrySet()) {
            PathOrigin origin = entry.getValue();
            if (origin == PathOrigin.LOCAL) {
                toRemove.add(entry.getKey());
            }
        }
        for (UUID id : toRemove) {
            myPaths.remove(id);
            visiblePaths.remove(id);
            pathOrigins.remove(id);
        }
        recalculateNextPathIndex();
    }

    /** Called each client tick to append points when recording locally. */
    public void tickRecording(Minecraft client) {
        if (!recording || localRecording == null) return;
        if (client == null || client.player == null) return;
        Player player = client.player;
        Vector3d current = new Vector3d(player.getX(), player.getY() + TRAIL_Y_OFFSET, player.getZ());
        List<Vector3d> pts = localRecording.getPoints();
        if (pts.isEmpty()) {
            pts.add(current);
            lastCapturedPoint = current;
            if (persistence != null) persistence.markDirty(localRecording.getPathId());
            return;
        }
        if (lastCapturedPoint == null) {
            lastCapturedPoint = current;
        }
        double dx = current.getX() - lastCapturedPoint.getX();
        double dy = current.getY() - lastCapturedPoint.getY();
        double dz = current.getZ() - lastCapturedPoint.getZ();
        double distSq = dx*dx + dy*dy + dz*dz;
        if (distSq >= 0.04) { // moved >= ~0.2 blocks
            pts.add(current);
            lastCapturedPoint = current;
            if (pts.size() > maxPointsPerPath && persistence != null) {
                persistence.enforcePointLimit(localRecording);
            }
            if (persistence != null) persistence.markDirty(localRecording.getPathId());
        }
    }

    public Collection<PathData> getVisiblePaths() {
        List<PathData> result = new ArrayList<>();
        for (UUID id : visiblePaths) {
            PathData path = myPaths.get(id);
            if (path == null) {
                path = sharedPaths.get(id);
            }
            if (path != null) {
                result.add(path);
            }
        }
        return result;
    }

    public Collection<PathData> getMyPaths() {
        return myPaths.values();
    }

    public Collection<PathData> getSharedPaths() {
        return new ArrayList<>(sharedPaths.values());
    }

    public PathOrigin getPathOrigin(UUID pathId) {
        return pathOrigins.getOrDefault(pathId, PathOrigin.LOCAL);
    }

    public boolean isServerBacked(UUID pathId) {
        PathOrigin origin = getPathOrigin(pathId);
        return origin == PathOrigin.SERVER_OWNED || origin == PathOrigin.SERVER_SHARED;
    }

    public boolean isLocalPath(UUID pathId) {
        PathOrigin origin = getPathOrigin(pathId);
        return origin == PathOrigin.LOCAL;
    }

    public void setLocalPlayerUuid(UUID uuid) {
        this.localPlayerUuid = uuid;
    }

    public UUID getLocalPlayerUuid() {
        return localPlayerUuid;
    }

    public void onPathUpdated(PathData path) {
        UUID id = path.getPathId();
        if (myPaths.containsKey(id)) {
            myPaths.put(id, path);
            if (isLocalPath(id) && persistence != null) {
                persistence.markDirty(id);
            }
        } else if (sharedPaths.containsKey(id)) {
            sharedPaths.put(id, path);
        }
    }

    public void applyServerSync(Collection<PathData> serverPaths) {
        // When receiving sync from a plugin-enabled server, clear all local paths
        // This prevents path bleeding when the client had previously stored paths locally
        // (e.g., from old plugin-less sessions or different worlds)
        Minecraft client = Minecraft.getInstance();
        boolean isMultiplayer = client == null || client.getSingleplayerServer() == null;
        if (isMultiplayer && ServerIntegrationBridge.SERVER_INTEGRATION != null 
            && ServerIntegrationBridge.SERVER_INTEGRATION.isServerSupported()) {
            TrailblazerFabricClient.LOGGER.info("Clearing local paths for plugin-enabled server");
            clearLocalPaths();
        }
        
        Set<UUID> preserveVisible = new HashSet<>();
        Set<UUID> previouslyKnown = new HashSet<>();
        for (UUID id : pathOrigins.keySet()) {
            if (isServerBacked(id)) {
                previouslyKnown.add(id);
                if (visiblePaths.contains(id)) {
                    preserveVisible.add(id);
                }
            }
        }

        // remove previous server-backed paths
        for (UUID id : new ArrayList<>(previouslyKnown)) {
            myPaths.remove(id);
            sharedPaths.remove(id);
            visiblePaths.remove(id);
            pathOrigins.remove(id);
        }

        for (PathData path : serverPaths) {
            PathOrigin origin = determineServerOrigin(path);
            putPath(path, origin);
            UUID id = path.getPathId();
            boolean known = previouslyKnown.contains(id);
            if (preserveVisible.contains(id)) {
                visiblePaths.add(id);
            } else if (!known) {
                // default visibility for brand new server paths
                visiblePaths.add(id);
            }
        }
        recalculateNextPathIndex();
    }

    public void applyServerShare(PathData path) {
        if (path == null) {
            return;
        }
        // Use server's path ID and data directly - server is authoritative
        // Server already sets recipient as owner and preserves origin info
        // Mark as SERVER_SHARED to distinguish from player's own paths
        putPath(path, PathOrigin.SERVER_SHARED);
        setPathVisible(path.getPathId());
        if (persistence != null) {
            persistence.markDirty(path.getPathId());
        }
        recalculateNextPathIndex();
    }

    public void loadDummyPath() {
        UUID dummyId = UUID.randomUUID();
        List<Vector3d> points = Arrays.asList(
                new Vector3d(0, 100, 0),
                new Vector3d(10, 105, 5),
                new Vector3d(20, 100, 15),
                new Vector3d(15, 98, 25),
                new Vector3d(5, 100, 20),
                new Vector3d(0, 100, 0)
        );
    PathData dummyPath = new PathData(dummyId, "DummyPath", UUID.randomUUID(), "TestPlayer", 0L, "minecraft:overworld", points);

        addMyPath(dummyPath);
        setPathVisible(dummyId);
        TrailblazerFabricClient.LOGGER.info("Loaded dummy path for rendering test.");
    }

    private void putPath(PathData path, PathOrigin origin) {
        if (origin == PathOrigin.SERVER_SHARED) {
            sharedPaths.put(path.getPathId(), path);
        } else {
            myPaths.put(path.getPathId(), path);
        }
        pathOrigins.put(path.getPathId(), origin);
    }

    private PathOrigin determineServerOrigin(PathData path) {
        // Check if this is a shared path by examining origin info
        // If originOwnerUUID exists and differs from ownerUUID, it's a shared path
        UUID originOwnerUUID = path.getOriginOwnerUUID();
        if (originOwnerUUID != null && !originOwnerUUID.equals(path.getOwnerUUID())) {
            return PathOrigin.SERVER_SHARED;
        }
        
        // If owner matches local player and no origin info (or origin matches owner), it's owned
        if (localPlayerUuid != null && localPlayerUuid.equals(path.getOwnerUUID())) {
            return PathOrigin.SERVER_OWNED;
        }
        
        // Fallback: if owner doesn't match local player, treat as shared
        return PathOrigin.SERVER_SHARED;
    }

    /**
     * Recalculate the next index to use for auto-named "Path-X" paths (using letters A, B, ... Z, AA, etc.).
     * This inspects currently-loaded local paths and sets the internal counter so
     * newly-created paths won't collide with existing ones.
     */
    public void recalculateNextPathIndex() {
        int maxIndex = 0;
        for (PathData path : myPaths.values()) {
            if (path.getPathName().startsWith("Path-")) {
                String suffix = path.getPathName().substring(5);
                int index = lettersToIndex(suffix);
                if (index > maxIndex) {
                    maxIndex = index;
                }
            }
        }
        nextPathIndex = maxIndex + 1;
    }

    /**
     * Convert a 1-based index to letter sequence (1=A, 2=B, ..., 26=Z, 27=AA, 28=AB, ...).
     */
    private static String indexToLetters(int index) {
        StringBuilder sb = new StringBuilder();
        while (index > 0) {
            index--;
            sb.insert(0, (char) ('A' + (index % 26)));
            index /= 26;
        }
        return sb.toString();
    }

    /**
     * Convert a letter sequence to 1-based index (A=1, B=2, ..., Z=26, AA=27, AB=28, ...).
     * Returns 0 if the string is empty or contains non-letter characters.
     */
    private static int lettersToIndex(String letters) {
        if (letters == null || letters.isEmpty()) {
            return 0;
        }
        int result = 0;
        for (char c : letters.toCharArray()) {
            if (c < 'A' || c > 'Z') {
                return 0; // Invalid character, not a letter sequence
            }
            result = result * 26 + (c - 'A' + 1);
        }
        return result;
    }

    private String resolveLocalPlayerName(String fallback) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.getUser() != null) {
            String username = client.getUser().getName();
            if (username != null && !username.isBlank()) {
                return username;
            }
        }
        return fallback != null && !fallback.isBlank() ? fallback : "Player";
    }

    private String uniquePathName(String proposed) {
        String base = (proposed == null || proposed.isBlank()) ? "Shared Path" : proposed.trim();
        String candidate = base;
        int index = 2;
        while (pathNameExists(candidate)) {
            candidate = base + " (" + index++ + ")";
        }
        return candidate;
    }

    private boolean pathNameExists(String name) {
        for (PathData path : myPaths.values()) {
            if (path.getPathName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        for (PathData path : sharedPaths.values()) {
            if (path.getPathName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public void replaceLocalWithServerCopy(PathData serverCopy) {
        if (serverCopy == null || serverCopy.getOriginPathId() == null) {
            return;
        }

        // The originPathId of the server copy holds the original client-side UUID.
        UUID originalClientId = serverCopy.getOriginPathId();

        // Remove the old local path.
        deletePath(originalClientId);

        // Add the new server-authoritative path.
        putPath(serverCopy, PathOrigin.SERVER_OWNED);
        setPathVisible(serverCopy.getPathId());

        // Trigger a persistence save.
        if (persistence != null) {
            persistence.markDirty(serverCopy.getPathId());
        }
    }
}