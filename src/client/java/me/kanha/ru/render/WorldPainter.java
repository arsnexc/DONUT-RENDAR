package me.kanha.ru.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.module.ActivityScanModule;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.ContainerEspModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import me.kanha.ru.module.SpawnerEspModule;
import me.kanha.ru.scan.DataAggregator;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Draws local-world markers using the 1.21.11 world-render event API. */
public final class WorldPainter {
    private static final int MAX_MARKERS_PER_MODULE = 200;
    private static final double MAX_MARKER_DISTANCE_SQUARED = 96.0 * 96.0;
    private static final double OUTLINE_THICKNESS = 0.035;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

    private static final Comparator<MarkerCandidate> NEAREST_FIRST = Comparator
        .comparingDouble(MarkerCandidate::distanceSquared)
        .thenComparingInt(candidate -> candidate.position().getX())
        .thenComparingInt(candidate -> candidate.position().getY())
        .thenComparingInt(candidate -> candidate.position().getZ());

    private WorldPainter() {
    }

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(WorldPainter::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!RenderUtilClient.hasLocalWorld(client) || !ModuleManager.anyEnabled()) {
            return;
        }

        PoseStack matrices = context.matrices();
        if (matrices == null || context.consumers() == null || context.worldState() == null
            || context.worldState().cameraRenderState == null) {
            return;
        }

        Vec3 camera = context.worldState().cameraRenderState.pos;
        if (camera == null) {
            return;
        }

        matrices.pushPose();
        try {
            // WorldRenderContext's matrix stack is world-oriented. Translate once
            // so every submitted vertex is camera-relative, as required by the
            // world renderer's shared consumer buffers.
            matrices.translate(-camera.x, -camera.y, -camera.z);

            VertexConsumer fillVertices = context.consumers().getBuffer(RenderTypes.debugFilledBox());
            // This vanilla see-through layer has depth testing and depth writes
            // disabled. Thin double-sided bars keep marker outlines legible behind
            // blocks and when the camera is inside a marker volume.
            VertexConsumer outlineVertices = context.consumers().getBuffer(RenderTypes.textBackgroundSeeThrough());

            Module containerBase = ModuleManager.getByName("Container ESP");
            if (containerBase instanceof ContainerEspModule container
                && container.isEnabled() && container.shouldHighlight()) {
                drawPositions(fillVertices, outlineVertices, matrices, DataAggregator.trackedContainers,
                    ARGB.colorFromFloat(0.16f, 1.0f, 0.20f, 0.20f),
                    ARGB.colorFromFloat(1.0f, 1.0f, 0.28f, 0.28f),
                    client.player.blockPosition());
            }

            Module spawnerBase = ModuleManager.getByName("Spawner ESP");
            if (spawnerBase instanceof SpawnerEspModule spawner
                && spawner.isEnabled() && spawner.shouldHighlight()) {
                drawPositions(fillVertices, outlineVertices, matrices, DataAggregator.trackedSpawners,
                    ARGB.colorFromFloat(0.17f, 1.0f, 0.78f, 0.16f),
                    ARGB.colorFromFloat(1.0f, 1.0f, 0.90f, 0.28f),
                    client.player.blockPosition());
            }

            Module searchBase = ModuleManager.getByName("Block Search");
            if (searchBase instanceof BlockSearchModule search
                && search.isEnabled() && search.shouldHighlight()) {
                drawPositions(fillVertices, outlineVertices, matrices, BlockSearchModule.foundBlocks,
                    ARGB.colorFromFloat(0.16f, 0.22f, 0.58f, 1.0f),
                    ARGB.colorFromFloat(1.0f, 0.38f, 0.72f, 1.0f),
                    client.player.blockPosition());
            }

            Module activityBase = ModuleManager.getByName("Activity Scan");
            if (activityBase instanceof ActivityScanModule activity && activity.isEnabled()) {
                BlockPos playerPos = client.player.blockPosition();
                for (MarkerCandidate candidate : nearestHotspots(
                    DataAggregator.heatMap, playerPos, activity.getThreshold())) {
                    float score = DataAggregator.heatMap.getOrDefault(candidate.position(), 0.0f);
                    if (score < activity.getThreshold()) {
                        continue;
                    }
                    float red = Math.min(1.0f, score / 20.0f);
                    float blue = 1.0f - red;
                    drawMarker(fillVertices, outlineVertices, matrices,
                        new AABB(candidate.position()).inflate(0.5),
                        ARGB.colorFromFloat(0.16f, red, 0.75f, blue),
                        ARGB.colorFromFloat(1.0f, Math.max(0.25f, red), 0.90f, Math.max(0.25f, blue)));
                }
            }
        } finally {
            matrices.popPose();
        }
    }

    private static void drawPositions(VertexConsumer fillVertices, VertexConsumer outlineVertices,
                                      PoseStack matrices, Set<BlockPos> positions,
                                      int fillColor, int outlineColor, BlockPos origin) {
        for (BlockPos pos : nearestPositions(positions, origin)) {
            drawMarker(fillVertices, outlineVertices, matrices, new AABB(pos), fillColor, outlineColor);
        }
    }

    private static void drawMarker(VertexConsumer fillVertices, VertexConsumer outlineVertices,
                                   PoseStack matrices, AABB box, int fillColor, int outlineColor) {
        drawBox(fillVertices, matrices, box, fillColor, false, false);
        drawOutline(outlineVertices, matrices, box, outlineColor);
    }

    private static List<BlockPos> nearestPositions(Set<BlockPos> positions, BlockPos origin) {
        PriorityQueue<MarkerCandidate> nearest = newNearestQueue();
        for (BlockPos pos : positions) {
            double distanceSquared = origin.distSqr(pos);
            if (distanceSquared <= MAX_MARKER_DISTANCE_SQUARED) {
                keepNearest(nearest, new MarkerCandidate(pos, distanceSquared));
            }
        }
        return sortedPositions(nearest);
    }

    private static List<MarkerCandidate> nearestHotspots(Map<BlockPos, Float> heatMap,
                                                          BlockPos origin, float threshold) {
        PriorityQueue<MarkerCandidate> nearest = newNearestQueue();
        for (Map.Entry<BlockPos, Float> entry : heatMap.entrySet()) {
            if (entry.getValue() < threshold) {
                continue;
            }
            double distanceSquared = origin.distSqr(entry.getKey());
            if (distanceSquared <= MAX_MARKER_DISTANCE_SQUARED) {
                keepNearest(nearest, new MarkerCandidate(entry.getKey(), distanceSquared));
            }
        }

        ArrayList<MarkerCandidate> result = new ArrayList<>(nearest);
        result.sort(NEAREST_FIRST);
        return result;
    }

    private static PriorityQueue<MarkerCandidate> newNearestQueue() {
        return new PriorityQueue<>(MAX_MARKERS_PER_MODULE, NEAREST_FIRST.reversed());
    }

    private static void keepNearest(PriorityQueue<MarkerCandidate> nearest, MarkerCandidate candidate) {
        if (nearest.size() < MAX_MARKERS_PER_MODULE) {
            nearest.add(candidate);
        } else if (NEAREST_FIRST.compare(candidate, nearest.peek()) < 0) {
            nearest.poll();
            nearest.add(candidate);
        }
    }

    private static List<BlockPos> sortedPositions(PriorityQueue<MarkerCandidate> candidates) {
        ArrayList<MarkerCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(NEAREST_FIRST);
        return sorted.stream().map(MarkerCandidate::position).toList();
    }

    private static void drawOutline(VertexConsumer vertices, PoseStack matrices, AABB box, int color) {
        double thickness = OUTLINE_THICKNESS;
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;

        // Four edges parallel to X.
        for (int yIndex = 0; yIndex < 2; yIndex++) {
            double y = yIndex == 0 ? minY : maxY;
            for (int zIndex = 0; zIndex < 2; zIndex++) {
                double z = zIndex == 0 ? minZ : maxZ;
                drawBox(vertices, matrices,
                    new AABB(minX, y - thickness, z - thickness,
                        maxX, y + thickness, z + thickness), color, true, true);
            }
        }

        // Four edges parallel to Y.
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? minX : maxX;
            for (int zIndex = 0; zIndex < 2; zIndex++) {
                double z = zIndex == 0 ? minZ : maxZ;
                drawBox(vertices, matrices,
                    new AABB(x - thickness, minY, z - thickness,
                        x + thickness, maxY, z + thickness), color, true, true);
            }
        }

        // Four edges parallel to Z.
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? minX : maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? minY : maxY;
                drawBox(vertices, matrices,
                    new AABB(x - thickness, y - thickness, minZ,
                        x + thickness, y + thickness, maxZ), color, true, true);
            }
        }
    }

    private static void drawBox(VertexConsumer vertices, PoseStack matrices, AABB box,
                                int color, boolean fullBright, boolean doubleSided) {
        Matrix4f matrix = matrices.last().pose();
        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float z1 = (float) box.minZ;
        float x2 = (float) box.maxX;
        float y2 = (float) box.maxY;
        float z2 = (float) box.maxZ;

        // Front, back, left, right, top, and bottom faces. The see-through
        // outline is emitted in both windings so it remains visible from inside.
        quad(vertices, matrix, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1,
            color, fullBright, doubleSided);
        quad(vertices, matrix, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2,
            color, fullBright, doubleSided);
        quad(vertices, matrix, x1, y1, z2, x1, y1, z1, x1, y2, z1, x1, y2, z2,
            color, fullBright, doubleSided);
        quad(vertices, matrix, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1,
            color, fullBright, doubleSided);
        quad(vertices, matrix, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2,
            color, fullBright, doubleSided);
        quad(vertices, matrix, x1, y1, z2, x2, y1, z2, x2, y1, z1, x1, y1, z1,
            color, fullBright, doubleSided);
    }

    private static void quad(VertexConsumer vertices, Matrix4f matrix,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             int color, boolean fullBright, boolean doubleSided) {
        vertex(vertices, matrix, ax, ay, az, color, fullBright);
        vertex(vertices, matrix, bx, by, bz, color, fullBright);
        vertex(vertices, matrix, cx, cy, cz, color, fullBright);
        vertex(vertices, matrix, dx, dy, dz, color, fullBright);
        if (doubleSided) {
            vertex(vertices, matrix, dx, dy, dz, color, true);
            vertex(vertices, matrix, cx, cy, cz, color, true);
            vertex(vertices, matrix, bx, by, bz, color, true);
            vertex(vertices, matrix, ax, ay, az, color, true);
        }
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float z, int color, boolean fullBright) {
        VertexConsumer vertex = vertices.addVertex(matrix, x, y, z).setColor(color);
        if (fullBright) {
            vertex.setLight(FULL_BRIGHT_LIGHT);
        }
    }

    private record MarkerCandidate(BlockPos position, double distanceSquared) {
    }
}
