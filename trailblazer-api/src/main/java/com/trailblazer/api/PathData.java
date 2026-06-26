package com.trailblazer.api;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Core path data structure for recording and sharing player movement trails.
 */
public class PathData implements Serializable {
    private static final long serialVersionUID = 2L;

    private final UUID pathId;
    private String pathName;
    private final UUID ownerUUID;
    private final String ownerName;
    private final long creationTimestamp;
    private final String dimension;
    private final List<Vector3d> points;
    private final List<UUID> sharedWith;
    private UUID originPathId;
    private UUID originOwnerUUID;
    private String originOwnerName;
    private int colorArgb; 

    public PathData(UUID pathId, String pathName, UUID ownerUUID, String ownerName, long creationTimestamp, String dimension, List<Vector3d> points) {
        Objects.requireNonNull(pathId, "Path ID cannot be null");
        Objects.requireNonNull(ownerUUID, "Owner UUID cannot be null");
        Objects.requireNonNull(ownerName, "Owner Name cannot be null");
        Objects.requireNonNull(dimension, "Dimension cannot be null");
        Objects.requireNonNull(points, "Points list cannot be null");

        this.pathId = pathId;
        // Sanitize early to enforce consistent invariant for all PathData instances (security hardening)
        this.pathName = PathNameSanitizer.sanitize(pathName);
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.creationTimestamp = creationTimestamp;
        this.dimension = dimension;
        this.points = points;
        this.sharedWith = new ArrayList<>();
        this.originPathId = pathId;
        this.originOwnerUUID = ownerUUID;
        this.originOwnerName = ownerName;
        this.colorArgb = 0;
    }

    public PathData(UUID pathId, String pathName, UUID ownerUUID, String ownerName, long creationTimestamp, String dimension, List<Vector3d> points, int colorArgb) {
        this(pathId, pathName, ownerUUID, ownerName, creationTimestamp, dimension, points);
        this.colorArgb = (colorArgb == 0 ? PathColors.assignColorFor(pathId) : colorArgb);
    }

    public PathData(UUID pathId, String pathName, UUID ownerUUID, String ownerName, long creationTimestamp, String dimension, List<Vector3d> points, int colorArgb, List<UUID> sharedWith) {
        this(pathId, pathName, ownerUUID, ownerName, creationTimestamp, dimension, points, colorArgb);
        this.sharedWith.addAll(sharedWith);
    }

    public UUID getPathId() {
        return pathId;
    }

    public String getPathName() {
        return pathName;
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    public String getDimension() {
        return dimension;
    }

    public List<Vector3d> getPoints() {
        return points;
    }

    public List<UUID> getSharedWith() {
        return sharedWith;
    }

    public boolean isSharedWith(UUID player) {
        return sharedWith != null && sharedWith.contains(player);
    }

    public boolean addShare(UUID player) {
        if (sharedWith != null && !sharedWith.contains(player)) {
            return sharedWith.add(player);
        }
        return false;
    }

    public boolean removeShare(UUID player) {
        if (sharedWith != null) {
            return sharedWith.remove(player);
        }
        return false;
    }

    public UUID getOriginPathId() {
        return originPathId;
    }

    public UUID getOriginOwnerUUID() {
        return originOwnerUUID;
    }

    public String getOriginOwnerName() {
        return originOwnerName;
    }

    public void setOrigin(UUID originPathId, UUID originOwnerUUID, String originOwnerName) {
        this.originPathId = originPathId;
        this.originOwnerUUID = originOwnerUUID;
        this.originOwnerName = originOwnerName;
    }

    /** Returns the path color, lazily assigning one if zero. */
    public int getColorArgb() {
        if (colorArgb == 0) {
            colorArgb = PathColors.assignColorFor(pathId);
        }
        return colorArgb;
    }

    public void setColorArgb(int colorArgb) {
        if (colorArgb == 0) return;
        this.colorArgb = colorArgb;
    }

    public void setPathName(String pathName) {
        // Accept null / blank and map to default via sanitizer
        this.pathName = PathNameSanitizer.sanitize(pathName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathData pathData = (PathData) o;
        return pathId.equals(pathData.pathId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathId);
    }

    @Override
    public String toString() {
        return "PathData{" +
                "pathId=" + pathId +
                ", pathName='" + pathName + '\'' +
                ", ownerUUID=" + ownerUUID +
                ", pointCount=" + points.size() +
                '}';
    }
}