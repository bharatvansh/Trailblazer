package com.trailblazer.plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.trailblazer.api.PathData;
import com.trailblazer.api.Vector3d;
import com.trailblazer.api.PathNameSanitizer;

public class PathDataManager {

    private final File basePathsFolder;
    private final Gson gson;
    private final AtomicInteger nextServerPathNumber = new AtomicInteger(1);
    public static final int MAX_POINTS_PER_PATH = 5000;

    public PathDataManager(TrailblazerPlugin plugin) {
        this.basePathsFolder = new File(plugin.getDataFolder(), "paths");
        if (!this.basePathsFolder.exists() && !this.basePathsFolder.mkdirs()) {
            TrailblazerPlugin.getPluginLogger().severe("Could not create data folder!");
        }
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    // Per-path locks so concurrent operations on different paths do not contend.
    private final ConcurrentHashMap<UUID, ReentrantLock> pathLocks = new ConcurrentHashMap<>();
    
    // Locks for sharing operations to prevent race conditions in duplicate detection.
    // Key format: "targetUuid:originPathId" to ensure only one sharing operation
    // for the same recipient + origin path combination can proceed at a time.
    private final ConcurrentHashMap<String, ReentrantLock> sharingLocks = new ConcurrentHashMap<>();

    public void savePath(UUID worldUid, PathData path) {
        if (path == null || path.getPathId() == null) {
            throw new IllegalArgumentException("Path and pathId must not be null");
        }
        File worldFolder = resolveWorldFolder(worldUid);
        File pathFile = new File(worldFolder, path.getPathId().toString() + ".json");
        ReentrantLock lock = acquireLock(path.getPathId());
        try {
            try (FileWriter writer = new FileWriter(pathFile)) {
                gson.toJson(path, writer);
            }
        } catch (IOException e) {
            TrailblazerPlugin.getPluginLogger().log(java.util.logging.Level.SEVERE, "Failed to save path " + path.getPathName(), e);
        } finally {
            releaseLock(path.getPathId(), lock);
        }
    }

    public String getNextServerPathName() {
        int current = nextServerPathNumber.getAndIncrement();
        return "Path-" + current;
    }

    public List<PathData> loadPaths(UUID worldUid, UUID playerUUID) {
        List<PathData> playerPaths = new ArrayList<>();
        File worldFolder = resolveWorldFolder(worldUid);
        File[] pathFiles = worldFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (pathFiles == null) {
            return playerPaths;
        }

        for (File pathFile : pathFiles) {
            UUID pathId = extractPathId(pathFile.getName());
            if (pathId == null) {
                TrailblazerPlugin.getPluginLogger().warning("Skipping path file with invalid name: " + pathFile.getName());
                continue;
            }

            ReentrantLock lock = acquireLock(pathId);
            try (FileReader reader = new FileReader(pathFile)) {
                PathData pathData = gson.fromJson(reader, PathData.class);
                if (pathData == null || !isValidPathData(pathData)) {
                    TrailblazerPlugin.getPluginLogger().warning("Skipping invalid path data file: " + pathFile.getName());
                    continue;
                }

                // Normalize loaded data to a safe, constructor-built instance to recover from missing fields
                // in older/tampered JSON while keeping backwards compatibility.
                PathData normalized = normalizeLoadedPath(pathData);
                if (normalized != pathData) {
                    pathData = normalized;
                    savePath(worldUid, pathData);
                }

                // Only check ownership - sharedWith is no longer used for access control
                // All shared paths are now owned copies created via ensureSharedCopy()
                // UPDATE: The copy-on-share pattern has been removed. Access is now granted
                // if the player is the owner OR if they are in the sharedWith list.
                if (pathData.getOwnerUUID().equals(playerUUID) || pathData.isSharedWith(playerUUID)) {
                    // Sanitize name post-deserialization to harden against tampered JSON
                    String original = pathData.getPathName();
                    String sanitized = PathNameSanitizer.sanitize(original);
                    if (!sanitized.equals(original)) {
                        pathData.setPathName(sanitized);
                        // Persist corrected name asynchronously (reuse save logic)
                        savePath(worldUid, pathData);
                    }
                    playerPaths.add(pathData);
                }
            } catch (IOException e) {
                TrailblazerPlugin.getPluginLogger().log(java.util.logging.Level.SEVERE, "Failed to load a path file for " + playerUUID + ": " + pathFile.getName(), e);
            } finally {
                releaseLock(pathId, lock);
            }
        }
        return playerPaths;
    }

    /**
     * Gson can deserialize into this class without running the constructor, leaving finals/collections null
     * if fields are missing in JSON. This method rebuilds a safe instance and fills sensible defaults.
     */
    private PathData normalizeLoadedPath(PathData loaded) {
        boolean needsRepair = false;

        String ownerName = loaded.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) {
            ownerName = "Player";
            needsRepair = true;
        }

        String dimension = loaded.getDimension();
        if (dimension == null || dimension.isBlank()) {
            // Best-effort fallback: world folder implies a world, but we don't have a World object here.
            // Use overworld as a safe default to avoid NPEs.
            dimension = "minecraft:overworld";
            needsRepair = true;
        }

        List<Vector3d> points = loaded.getPoints();
        if (points == null) {
            points = new ArrayList<>();
            needsRepair = true;
        }

        List<UUID> sharedWith = loaded.getSharedWith();
        if (sharedWith == null) {
            sharedWith = new ArrayList<>();
            needsRepair = true;
        }

        UUID originPathId = loaded.getOriginPathId();
        UUID originOwnerUuid = loaded.getOriginOwnerUUID();
        String originOwnerName = loaded.getOriginOwnerName();
        if (originPathId == null || originOwnerUuid == null || originOwnerName == null || originOwnerName.isBlank()) {
            // Default lineage to "self" if missing.
            originPathId = loaded.getPathId();
            originOwnerUuid = loaded.getOwnerUUID();
            originOwnerName = ownerName;
            needsRepair = true;
        }

        if (!needsRepair) {
            return loaded;
        }

        PathData repaired = new PathData(
                loaded.getPathId(),
                loaded.getPathName(),
                loaded.getOwnerUUID(),
                ownerName,
                loaded.getCreationTimestamp(),
                dimension,
                new ArrayList<>(points),
                loaded.getColorArgb(),
                new ArrayList<>(sharedWith)
        );
        repaired.setOrigin(originPathId, originOwnerUuid, originOwnerName);
        return repaired;
    }

    public boolean deletePath(UUID worldUid, UUID playerUUID, UUID pathId) {
        if (pathId == null) {
            return false;
        }
        File worldFolder = resolveWorldFolder(worldUid);
        File pathFile = new File(worldFolder, pathId.toString() + ".json");
        ReentrantLock lock = acquireLock(pathId);
        try {
            if (!pathFile.exists()) {
                return false;
            }

            PathData pathData;
            try (FileReader reader = new FileReader(pathFile)) {
                pathData = gson.fromJson(reader, PathData.class);
            } catch (IOException e) {
                TrailblazerPlugin.getPluginLogger().log(java.util.logging.Level.SEVERE, "Failed to read path for deletion: " + pathId, e);
                return false;
            }

            if (pathData == null) {
                return false;
            }

            // Only allow deletion if player owns the path
            // Shared paths are now owned copies, so recipients delete their own copy, not remove from sharedWith
            if (pathData.getOwnerUUID().equals(playerUUID)) {
                if (!pathFile.delete()) {
                    TrailblazerPlugin.getPluginLogger().severe("Failed to delete path file: " + pathFile.getAbsolutePath());
                    return false;
                }
                return true;
            }

            // Player doesn't own this path, so they can't delete it
            return false;
        } finally {
            releaseLock(pathId, lock);
        }
    }



    public void renamePath(UUID worldUid, UUID playerUUID, UUID pathId, String newName) {
        if (pathId == null) {
            TrailblazerPlugin.getPluginLogger().warning("Attempted to rename a path with null identifier");
            return;
        }
        String sanitized = com.trailblazer.api.PathNameSanitizer.sanitize(newName);
        File worldFolder = resolveWorldFolder(worldUid);
        File pathFile = new File(worldFolder, pathId.toString() + ".json");
        if (!pathFile.exists()) {
            TrailblazerPlugin.getPluginLogger().warning("Attempted to rename a path that does not exist: " + pathId);
            return;
        }

        ReentrantLock lock = acquireLock(pathId);
        try (FileReader reader = new FileReader(pathFile)) {
            PathData pathData = gson.fromJson(reader, PathData.class);
            if (pathData != null && pathData.getOwnerUUID().equals(playerUUID)) {
                pathData.setPathName(sanitized);
                savePath(worldUid, pathData);
            }
        } catch (IOException e) {
            TrailblazerPlugin.getPluginLogger().log(java.util.logging.Level.SEVERE, "Failed to rename path: " + pathId, e);
        } finally {
            releaseLock(pathId, lock);
        }
    }

    public List<PathData> updateMetadata(UUID worldUid, UUID playerUUID, UUID pathId, String newName, int colorArgb) {
        List<PathData> paths = loadPaths(worldUid, playerUUID);
        boolean updated = false;
        for (PathData path : paths) {
            if (!path.getPathId().equals(pathId)) {
                continue;
            }
            if (path.getOwnerUUID().equals(playerUUID)) {
                if (newName != null && !newName.isBlank()) {
                    path.setPathName(com.trailblazer.api.PathNameSanitizer.sanitize(newName));
                }
                if (colorArgb != 0) {
                    path.setColorArgb(colorArgb);
                }
                savePath(worldUid, path);
                updated = true;
            }
            break;
        }
        return updated ? paths : null;
    }

    public static boolean isValidPathData(PathData path) {
        return path.getPathId() != null
            && path.getOwnerUUID() != null
            && path.getPoints() != null
            && path.getPoints().size() <= MAX_POINTS_PER_PATH;
    }

    private ReentrantLock acquireLock(UUID pathId) {
        ReentrantLock lock = pathLocks.computeIfAbsent(pathId, id -> new ReentrantLock());
        lock.lock();
        return lock;
    }

    private void releaseLock(UUID pathId, ReentrantLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            
            // Clean up the lock if it's no longer in use to prevent memory leaks.
            // Use tryLock() to atomically check if the lock is truly free.
            // If we can acquire it immediately, no other thread has it, so it's safe to remove.
            if (lock.tryLock()) {
                try {
                    // While holding the lock, check if any threads are queued.
                    // If not, it's safe to remove since we have exclusive access.
                    if (!lock.hasQueuedThreads()) {
                        pathLocks.remove(pathId, lock);
                    }
                } finally {
                    // Always release the lock we just acquired.
                    lock.unlock();
                }
            }
            // If tryLock() failed, another thread acquired the lock, so do not remove.
        }
    }

    private UUID extractPathId(String fileName) {
        if (fileName == null || !fileName.endsWith(".json")) {
            return null;
        }
        String raw = fileName.substring(0, fileName.length() - 5);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private File resolveWorldFolder(UUID worldUid) {
        if (worldUid == null) {
            throw new IllegalArgumentException("worldUid must not be null for per-world path storage");
        }
        String key = worldUid.toString();
        File worldFolder = new File(basePathsFolder, key);
        if (!worldFolder.exists() && !worldFolder.mkdirs()) {
            TrailblazerPlugin.getPluginLogger().severe("Could not create world paths folder: " + worldFolder.getAbsolutePath());
        }
        return worldFolder;
    }
}