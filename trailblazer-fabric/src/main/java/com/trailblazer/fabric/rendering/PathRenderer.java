package com.trailblazer.fabric.rendering;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trailblazer.api.PathData;
import com.trailblazer.api.Vector3d;
import com.trailblazer.fabric.ClientPathManager;
import com.trailblazer.fabric.RenderSettingsManager;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Handles the client-side rendering of paths in the world.
 */
public class PathRenderer {

    private static final double MIN_SEGMENT_LENGTH = 0.05;

    private static final double DASH_LENGTH = 2.0;
    private static final double GAP_LENGTH = 1.0;
    private static final double DASH_PATTERN_LENGTH = DASH_LENGTH + GAP_LENGTH;

    private static final double LINE_HALF_WIDTH = 0.10;

    private static final double MARKER_HALF_SIZE = 0.14;

    private static final double ARROW_LENGTH = 0.75;
    private static final double ARROW_HEAD_LENGTH = 0.25;
    private static final double ARROW_SHAFT_HALF_WIDTH = 0.04;
    private static final double ARROW_HEAD_HALF_WIDTH = 0.16;
    private static final double ARROW_TIP_HALF_WIDTH = 0.02;

    private static final double MAX_RENDER_DISTANCE = 256.0;
    private static final double MAX_RENDER_DISTANCE_SQ = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

    private final ClientPathManager clientPathManager;
    private final RenderSettingsManager renderSettingsManager;

    public PathRenderer(ClientPathManager clientPathManager, RenderSettingsManager renderSettingsManager) {
        this.clientPathManager = clientPathManager;
        this.renderSettingsManager = renderSettingsManager;
    }

    public void initialize() {
        // Render late in the main world pass so our overlays aren't immediately overwritten.
        LevelRenderEvents.END_MAIN.register(this::renderActivePaths);
    }

    private void renderActivePaths(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world == null) {
            return;
        }
        String currentDimension = world.dimension().identifier().toString();

        Vec3 cameraPos = client.gameRenderer.mainCamera().position();
        Vec3 cameraForward = getCameraForward(client);

        PathData livePath = clientPathManager.getLivePath();
        Iterable<PathData> visiblePaths = clientPathManager.getVisiblePaths();

        switch (renderSettingsManager.getRenderMode()) {
            case SOLID_LINE -> renderSolidLines(context, currentDimension, livePath, visiblePaths, cameraPos, cameraForward);
            case DASHED_LINE -> renderDashedLines(context, currentDimension, livePath, visiblePaths, cameraPos, cameraForward);
            case SPACED_MARKERS -> renderSpacedMarkers(context, currentDimension, livePath, visiblePaths, cameraPos, cameraForward);
            case DIRECTIONAL_ARROWS -> renderDirectionalArrows(context, currentDimension, livePath, visiblePaths, cameraPos, cameraForward);
        }
    }

    private void renderDashedLines(
            LevelRenderContext context,
            String currentDimension,
            PathData livePath,
            Iterable<PathData> visiblePaths,
            Vec3 cameraPos,
            Vec3 cameraForward
    ) {
        context.submitNodeCollector().submitCustomGeometry(
            context.poseStack(),
            RenderTypes.debugQuads(),
            (pose, consumer) -> {
                if (livePath != null) {
                    appendDashedPath(livePath, true, currentDimension, cameraPos, cameraForward, pose, consumer);
                }
                for (PathData path : visiblePaths) {
                    appendDashedPath(path, false, currentDimension, cameraPos, cameraForward, pose, consumer);
                }
            }
        );
    }

    private void renderSolidLines(
            LevelRenderContext context,
            String currentDimension,
            PathData livePath,
            Iterable<PathData> visiblePaths,
            Vec3 cameraPos,
            Vec3 cameraForward
    ) {
        context.submitNodeCollector().submitCustomGeometry(
            context.poseStack(),
            RenderTypes.debugQuads(),
            (pose, consumer) -> {
                if (livePath != null) {
                    appendSolidPath(livePath, true, currentDimension, cameraPos, cameraForward, pose, consumer);
                }
                for (PathData path : visiblePaths) {
                    appendSolidPath(path, false, currentDimension, cameraPos, cameraForward, pose, consumer);
                }
            }
        );
    }

    private void renderSpacedMarkers(
            LevelRenderContext context,
            String currentDimension,
            PathData livePath,
            Iterable<PathData> visiblePaths,
            Vec3 cameraPos,
            Vec3 cameraForward
    ) {
        double spacing = Math.max(0.25, renderSettingsManager.getMarkerSpacing());

        Vec3 billboardRight = getBillboardRight(cameraForward);
        Vec3 billboardUp = getBillboardUp(cameraForward, billboardRight);

        context.submitNodeCollector().submitCustomGeometry(
            context.poseStack(),
            RenderTypes.debugQuads(),
            (pose, consumer) -> {
                if (livePath != null) {
                    appendMarkerPath(livePath, true, currentDimension, cameraPos, billboardRight, billboardUp, spacing, pose, consumer);
                }
                for (PathData path : visiblePaths) {
                    appendMarkerPath(path, false, currentDimension, cameraPos, billboardRight, billboardUp, spacing, pose, consumer);
                }
            }
        );
    }

    private void renderDirectionalArrows(
            LevelRenderContext context,
            String currentDimension,
            PathData livePath,
            Iterable<PathData> visiblePaths,
            Vec3 cameraPos,
            Vec3 cameraForward
    ) {
        double spacing = 3.0;

        context.submitNodeCollector().submitCustomGeometry(
            context.poseStack(),
            RenderTypes.debugQuads(),
            (pose, consumer) -> {
                if (livePath != null) {
                    appendArrowPath(livePath, true, currentDimension, cameraPos, cameraForward, spacing, pose, consumer);
                }
                for (PathData path : visiblePaths) {
                    appendArrowPath(path, false, currentDimension, cameraPos, cameraForward, spacing, pose, consumer);
                }
            }
        );
    }

    private boolean appendDashedPath(
            PathData path,
            boolean isLive,
            String currentDimension,
            Vec3 cameraPos,
            Vec3 cameraForward,
            PoseStack.Pose pose,
            VertexConsumer consumer
    ) {
        List<Vector3d> points = path.getPoints();
        if (points.size() < 2) {
            return false;
        }

        String dim = path.getDimension();
        if (dim != null && !dim.isBlank() && !currentDimension.equals(dim)) {
            return false;
        }

        final int color = path.getColorArgb();
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = isLive ? 1.0f : 0.9f;

        double camX = cameraPos.x;
        double camY = cameraPos.y;
        double camZ = cameraPos.z;

        boolean any = false;
        double patternOffset = 0.0;
        double[] right = new double[3];

        Vector3d prev = points.getFirst();
        double x0 = prev.getX();
        double y0 = prev.getY();
        double z0 = prev.getZ();

        for (int i = 1; i < points.size(); i++) {
            Vector3d next = points.get(i);

            double x1 = next.getX();
            double y1 = next.getY();
            double z1 = next.getZ();

            double dx = x1 - x0;
            double dy = y1 - y0;
            double dz = z1 - z0;
            double segLenSq = dx * dx + dy * dy + dz * dz;
            if (segLenSq < MIN_SEGMENT_LENGTH * MIN_SEGMENT_LENGTH) {
                continue;
            }

            double d0x = x0 - camX;
            double d0y = y0 - camY;
            double d0z = z0 - camZ;
            double d1x = x1 - camX;
            double d1y = y1 - camY;
            double d1z = z1 - camZ;
            if ((d0x * d0x + d0y * d0y + d0z * d0z) > MAX_RENDER_DISTANCE_SQ
                    && (d1x * d1x + d1y * d1y + d1z * d1z) > MAX_RENDER_DISTANCE_SQ) {
                x0 = x1;
                y0 = y1;
                z0 = z1;
                continue;
            }

            double segLen = Math.sqrt(segLenSq);
            double invLen = 1.0 / segLen;
            double dirX = dx * invLen;
            double dirY = dy * invLen;
            double dirZ = dz * invLen;

            stableRightVector(cameraForward, dirX, dirY, dirZ, LINE_HALF_WIDTH, right);
            double rightX = right[0];
            double rightY = right[1];
            double rightZ = right[2];

            double t = 0.0;
            while (t < segLen) {
                boolean inDash = patternOffset < DASH_LENGTH;
                double maxStep = (inDash ? DASH_LENGTH : DASH_PATTERN_LENGTH) - patternOffset;
                double step = Math.min(maxStep, segLen - t);

                if (inDash && step >= 0.02) {
                    double s = t;
                    double e = t + step;

                    double sx = x0 + dirX * s;
                    double sy = y0 + dirY * s;
                    double sz = z0 + dirZ * s;

                    double ex = x0 + dirX * e;
                    double ey = y0 + dirY * e;
                    double ez = z0 + dirZ * e;

                    addRibbonQuad(pose, consumer, sx, sy, sz, ex, ey, ez, camX, camY, camZ, rightX, rightY, rightZ, r, g, b, a);
                    any = true;
                }

                t += step;
                patternOffset += step;
                if (patternOffset >= DASH_PATTERN_LENGTH) {
                    patternOffset %= DASH_PATTERN_LENGTH;
                }
            }

            x0 = x1;
            y0 = y1;
            z0 = z1;
        }

        return any;
    }

    private boolean appendSolidPath(
            PathData path,
            boolean isLive,
            String currentDimension,
            Vec3 cameraPos,
            Vec3 cameraForward,
            PoseStack.Pose pose,
            VertexConsumer consumer
    ) {
        List<Vector3d> points = path.getPoints();
        if (points.size() < 2) {
            return false;
        }

        String dim = path.getDimension();
        if (dim != null && !dim.isBlank() && !currentDimension.equals(dim)) {
            return false;
        }

        final int color = path.getColorArgb();
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = isLive ? 1.0f : 0.9f;

        double camX = cameraPos.x;
        double camY = cameraPos.y;
        double camZ = cameraPos.z;

        boolean any = false;
        double[] right = new double[3];

        Vector3d prev = points.getFirst();
        double x0 = prev.getX();
        double y0 = prev.getY();
        double z0 = prev.getZ();

        for (int i = 1; i < points.size(); i++) {
            Vector3d next = points.get(i);

            double x1 = next.getX();
            double y1 = next.getY();
            double z1 = next.getZ();

            double dx = x1 - x0;
            double dy = y1 - y0;
            double dz = z1 - z0;
            double segLenSq = dx * dx + dy * dy + dz * dz;
            if (segLenSq < MIN_SEGMENT_LENGTH * MIN_SEGMENT_LENGTH) {
                x0 = x1;
                y0 = y1;
                z0 = z1;
                continue;
            }

            double d0x = x0 - camX;
            double d0y = y0 - camY;
            double d0z = z0 - camZ;
            double d1x = x1 - camX;
            double d1y = y1 - camY;
            double d1z = z1 - camZ;
            if ((d0x * d0x + d0y * d0y + d0z * d0z) > MAX_RENDER_DISTANCE_SQ
                    && (d1x * d1x + d1y * d1y + d1z * d1z) > MAX_RENDER_DISTANCE_SQ) {
                x0 = x1;
                y0 = y1;
                z0 = z1;
                continue;
            }

            double segLen = Math.sqrt(segLenSq);
            double invLen = 1.0 / segLen;
            double dirX = dx * invLen;
            double dirY = dy * invLen;
            double dirZ = dz * invLen;

            stableRightVector(cameraForward, dirX, dirY, dirZ, LINE_HALF_WIDTH, right);
            double rightX = right[0];
            double rightY = right[1];
            double rightZ = right[2];

            addRibbonQuad(pose, consumer, x0, y0, z0, x1, y1, z1, camX, camY, camZ, rightX, rightY, rightZ, r, g, b, a);
            any = true;

            x0 = x1;
            y0 = y1;
            z0 = z1;
        }

        return any;
    }

    private boolean appendMarkerPath(
            PathData path,
            boolean isLive,
            String currentDimension,
            Vec3 cameraPos,
            Vec3 billboardRight,
            Vec3 billboardUp,
            double spacing,
            PoseStack.Pose pose,
            VertexConsumer consumer
    ) {
        List<Vector3d> points = path.getPoints();
        if (points.isEmpty()) {
            return false;
        }

        String dim = path.getDimension();
        if (dim != null && !dim.isBlank() && !currentDimension.equals(dim)) {
            return false;
        }

        final int color = path.getColorArgb();
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = isLive ? 1.0f : 0.9f;

        double camX = cameraPos.x;
        double camY = cameraPos.y;
        double camZ = cameraPos.z;

        boolean any = false;

        Vector3d p0 = points.getFirst();
        double x0 = p0.getX();
        double y0 = p0.getY();
        double z0 = p0.getZ();

        any |= addBillboardSquare(pose, consumer, x0, y0, z0, camX, camY, camZ, billboardRight, billboardUp, MARKER_HALF_SIZE, r, g, b, a);

        double distanceToNext = spacing;

        for (int i = 1; i < points.size(); i++) {
            Vector3d p1 = points.get(i);
            double x1 = p1.getX();
            double y1 = p1.getY();
            double z1 = p1.getZ();

            double dx = x1 - x0;
            double dy = y1 - y0;
            double dz = z1 - z0;
            double segLenSq = dx * dx + dy * dy + dz * dz;
            if (segLenSq < MIN_SEGMENT_LENGTH * MIN_SEGMENT_LENGTH) {
                continue;
            }

            double segLen = Math.sqrt(segLenSq);
            double invLen = 1.0 / segLen;
            double dirX = dx * invLen;
            double dirY = dy * invLen;
            double dirZ = dz * invLen;

            double travelled = 0.0;
            while (travelled + distanceToNext <= segLen) {
                travelled += distanceToNext;

                double px = x0 + dirX * travelled;
                double py = y0 + dirY * travelled;
                double pz = z0 + dirZ * travelled;

                if (distanceSqToCamera(px, py, pz, camX, camY, camZ) <= MAX_RENDER_DISTANCE_SQ) {
                    any |= addBillboardSquare(pose, consumer, px, py, pz, camX, camY, camZ, billboardRight, billboardUp, MARKER_HALF_SIZE, r, g, b, a);
                }

                distanceToNext = spacing;
            }

            distanceToNext -= (segLen - travelled);

            x0 = x1;
            y0 = y1;
            z0 = z1;
        }

        return any;
    }

    private boolean appendArrowPath(
            PathData path,
            boolean isLive,
            String currentDimension,
            Vec3 cameraPos,
            Vec3 cameraForward,
            double spacing,
            PoseStack.Pose pose,
            VertexConsumer consumer
    ) {
        List<Vector3d> points = path.getPoints();
        if (points.size() < 2) {
            return false;
        }

        String dim = path.getDimension();
        if (dim != null && !dim.isBlank() && !currentDimension.equals(dim)) {
            return false;
        }

        final int color = path.getColorArgb();
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float a = isLive ? 1.0f : 0.9f;

        double camX = cameraPos.x;
        double camY = cameraPos.y;
        double camZ = cameraPos.z;

        boolean any = false;
        double[] right = new double[3];

        Vector3d p0 = points.getFirst();
        double x0 = p0.getX();
        double y0 = p0.getY();
        double z0 = p0.getZ();

        double distanceToNext = 0.0;

        for (int i = 1; i < points.size(); i++) {
            Vector3d p1 = points.get(i);
            double x1 = p1.getX();
            double y1 = p1.getY();
            double z1 = p1.getZ();

            double dx = x1 - x0;
            double dy = y1 - y0;
            double dz = z1 - z0;
            double segLenSq = dx * dx + dy * dy + dz * dz;
            if (segLenSq < MIN_SEGMENT_LENGTH * MIN_SEGMENT_LENGTH) {
                continue;
            }

            double segLen = Math.sqrt(segLenSq);
            double invLen = 1.0 / segLen;
            double dirX = dx * invLen;
            double dirY = dy * invLen;
            double dirZ = dz * invLen;

            double travelled = 0.0;
            while (travelled + distanceToNext <= segLen) {
                travelled += distanceToNext;

                double px = x0 + dirX * travelled;
                double py = y0 + dirY * travelled;
                double pz = z0 + dirZ * travelled;

                if (distanceSqToCamera(px, py, pz, camX, camY, camZ) <= MAX_RENDER_DISTANCE_SQ) {
                    any |= addArrow(pose, consumer, px, py, pz, dirX, dirY, dirZ, camX, camY, camZ, cameraForward, right, r, g, b, a);
                }

                distanceToNext = spacing;
            }

            distanceToNext -= (segLen - travelled);

            x0 = x1;
            y0 = y1;
            z0 = z1;
        }

        return any;
    }

    private static void addRibbonQuad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            double sx,
            double sy,
            double sz,
            double ex,
            double ey,
            double ez,
            double camX,
            double camY,
            double camZ,
            double rx,
            double ry,
            double rz,
            float r,
            float g,
            float b,
            float a
    ) {
        double sxr = sx - camX;
        double syr = sy - camY;
        double szr = sz - camZ;

        double exr = ex - camX;
        double eyr = ey - camY;
        double ezr = ez - camZ;

        consumer.addVertex(pose, (float) (sxr + rx), (float) (syr + ry), (float) (szr + rz)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (sxr - rx), (float) (syr - ry), (float) (szr - rz)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (exr - rx), (float) (eyr - ry), (float) (ezr - rz)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (exr + rx), (float) (eyr + ry), (float) (ezr + rz)).setColor(r, g, b, a);
    }

    private static boolean addBillboardSquare(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            double x,
            double y,
            double z,
            double camX,
            double camY,
            double camZ,
            Vec3 right,
            Vec3 up,
            double halfSize,
            float r,
            float g,
            float b,
            float a
    ) {
        if (distanceSqToCamera(x, y, z, camX, camY, camZ) > MAX_RENDER_DISTANCE_SQ) {
            return false;
        }

        double rx = right.x * halfSize;
        double ry = right.y * halfSize;
        double rz = right.z * halfSize;

        double ux = up.x * halfSize;
        double uy = up.y * halfSize;
        double uz = up.z * halfSize;

        double xr = x - camX;
        double yr = y - camY;
        double zr = z - camZ;

        consumer.addVertex(pose, (float) (xr - rx - ux), (float) (yr - ry - uy), (float) (zr - rz - uz)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (xr - rx + ux), (float) (yr - ry + uy), (float) (zr - rz + uz)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (xr + rx + ux), (float) (yr + ry + uy), (float) (zr + rz + uz)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (xr + rx - ux), (float) (yr + ry - uy), (float) (zr + rz - uz)).setColor(r, g, b, a);

        return true;
    }

    private static boolean addArrow(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            double x,
            double y,
            double z,
            double dirX,
            double dirY,
            double dirZ,
            double camX,
            double camY,
            double camZ,
            Vec3 cameraForward,
            double[] right,
            float r,
            float g,
            float b,
            float a
    ) {
        stableRightVector(cameraForward, dirX, dirY, dirZ, 1.0, right);

        double rx = right[0];
        double ry = right[1];
        double rz = right[2];

        double shaftHalf = ARROW_SHAFT_HALF_WIDTH;
        double headHalf = ARROW_HEAD_HALF_WIDTH;
        double tipHalf = ARROW_TIP_HALF_WIDTH;

        double baseX = x - dirX * ARROW_LENGTH;
        double baseY = y - dirY * ARROW_LENGTH;
        double baseZ = z - dirZ * ARROW_LENGTH;

        double headBaseX = x - dirX * ARROW_HEAD_LENGTH;
        double headBaseY = y - dirY * ARROW_HEAD_LENGTH;
        double headBaseZ = z - dirZ * ARROW_HEAD_LENGTH;

        addRibbonQuad(
                pose,
                consumer,
                baseX,
                baseY,
                baseZ,
                headBaseX,
                headBaseY,
                headBaseZ,
                camX,
                camY,
                camZ,
                rx * shaftHalf,
                ry * shaftHalf,
                rz * shaftHalf,
                r,
                g,
                b,
                a
        );

        double tipLeftX = x + rx * tipHalf;
        double tipLeftY = y + ry * tipHalf;
        double tipLeftZ = z + rz * tipHalf;

        double tipRightX = x - rx * tipHalf;
        double tipRightY = y - ry * tipHalf;
        double tipRightZ = z - rz * tipHalf;

        double baseLeftX = headBaseX + rx * headHalf;
        double baseLeftY = headBaseY + ry * headHalf;
        double baseLeftZ = headBaseZ + rz * headHalf;

        double baseRightX = headBaseX - rx * headHalf;
        double baseRightY = headBaseY - ry * headHalf;
        double baseRightZ = headBaseZ - rz * headHalf;

        consumer.addVertex(pose, (float) (tipLeftX - camX), (float) (tipLeftY - camY), (float) (tipLeftZ - camZ)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (baseLeftX - camX), (float) (baseLeftY - camY), (float) (baseLeftZ - camZ)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (baseRightX - camX), (float) (baseRightY - camY), (float) (baseRightZ - camZ)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (tipRightX - camX), (float) (tipRightY - camY), (float) (tipRightZ - camZ)).setColor(r, g, b, a);

        return true;
    }

    private static void stableRightVector(
            Vec3 cameraForward,
            double dirX,
            double dirY,
            double dirZ,
            double scale,
            double[] out
    ) {
        double fx = cameraForward.x;
        double fy = cameraForward.y;
        double fz = cameraForward.z;

        double crossX = fy * dirZ - fz * dirY;
        double crossY = fz * dirX - fx * dirZ;
        double crossZ = fx * dirY - fy * dirX;

        double lenSq = crossX * crossX + crossY * crossY + crossZ * crossZ;
        if (lenSq < 1.0e-8) {
            crossX = dirZ;
            crossY = 0.0;
            crossZ = -dirX;
            lenSq = crossX * crossX + crossY * crossY + crossZ * crossZ;
        }
        if (lenSq < 1.0e-8) {
            crossX = 0.0;
            crossY = -dirZ;
            crossZ = dirY;
            lenSq = crossX * crossX + crossY * crossY + crossZ * crossZ;
        }

        double invLen = 1.0 / Math.sqrt(lenSq);
        out[0] = crossX * invLen * scale;
        out[1] = crossY * invLen * scale;
        out[2] = crossZ * invLen * scale;
    }

    private static Vec3 getCameraForward(Minecraft client) {
        var cam = client.gameRenderer.mainCamera();
        double yaw = Math.toRadians(cam.yRot());
        double pitch = Math.toRadians(cam.xRot());

        double x = -Math.sin(yaw) * Math.cos(pitch);
        double y = -Math.sin(pitch);
        double z = Math.cos(yaw) * Math.cos(pitch);

        double lenSq = x * x + y * y + z * z;
        if (lenSq < 1.0e-8) {
            return new Vec3(0.0, 0.0, 1.0);
        }

        double invLen = 1.0 / Math.sqrt(lenSq);
        return new Vec3(x * invLen, y * invLen, z * invLen);
    }

    private static Vec3 getBillboardRight(Vec3 cameraForward) {
        double fx = cameraForward.x;
        double fz = cameraForward.z;

        // worldUp (0,1,0) x cameraForward
        double rx = fz;
        double rz = -fx;

        double lenSq = rx * rx + rz * rz;
        if (lenSq < 1.0e-8) {
            return new Vec3(1.0, 0.0, 0.0);
        }

        double invLen = 1.0 / Math.sqrt(lenSq);
        return new Vec3(rx * invLen, 0.0, rz * invLen);
    }

    private static Vec3 getBillboardUp(Vec3 cameraForward, Vec3 cameraRight) {
        double fx = cameraForward.x;
        double fy = cameraForward.y;
        double fz = cameraForward.z;

        double rx = cameraRight.x;
        double ry = cameraRight.y;
        double rz = cameraRight.z;

        // cameraForward x cameraRight
        double ux = fy * rz - fz * ry;
        double uy = fz * rx - fx * rz;
        double uz = fx * ry - fy * rx;

        double lenSq = ux * ux + uy * uy + uz * uz;
        if (lenSq < 1.0e-8) {
            return new Vec3(0.0, 1.0, 0.0);
        }

        double invLen = 1.0 / Math.sqrt(lenSq);
        return new Vec3(ux * invLen, uy * invLen, uz * invLen);
    }

    private static double distanceSqToCamera(double x, double y, double z, double camX, double camY, double camZ) {
        double dx = x - camX;
        double dy = y - camY;
        double dz = z - camZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
