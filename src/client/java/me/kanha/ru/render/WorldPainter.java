package me.kanha.ru.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.config.Settings;
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
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

    private static final Comparator<MarkerCandidate> NEAREST_FIRST = Comparator
        .comparingDouble(MarkerCandidate::distanceSquared)
        .thenComparingInt(candidate -> candidate.position().getX())
        .thenComparingInt(candidate -> candidate.position().getY())
        .thenComparingInt(candidate -> candidate.position().getZ());

    private static final MarkerCache CONTAINER_CACHE = new MarkerCache();
    private static final MarkerCache SPAWNER_CACHE = new MarkerCache();
    private static final MarkerCache SEARCH_CACHE = new MarkerCache();
    private static final MarkerCache ACTIVITY_CACHE = new MarkerCache();

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

            Module containerBase = ModuleManager.getByName("Container ESP");
            if (containerBase instanceof ContainerEspModule container
                && container.isEnabled() && container.shouldHighlight()) {
                Settings.MarkerStyle style = Settings.markerStyle("container_esp");
                drawPositions(fillVertices, outlineVertices(context, style), matrices, CONTAINER_CACHE,
                    DataAggregator.trackedContainers, style, client.player.blockPosition());
            }

            Module spawnerBase = ModuleManager.getByName("Spawner ESP");
            if (spawnerBase instanceof SpawnerEspModule spawner
                && spawner.isEnabled() && spawner.shouldHighlight()) {
                Settings.MarkerStyle style = Settings.markerStyle("spawner_esp");
                drawPositions(fillVertices, outlineVertices(context, style), matrices, SPAWNER_CACHE,
                    DataAggregator.trackedSpawners, style, client.player.blockPosition());
            }

            Module searchBase = ModuleManager.getByName("Block Search");
            if (searchBase instanceof BlockSearchModule search
                && search.isEnabled() && search.shouldHighlight()) {
                Settings.MarkerStyle style = Settings.markerStyle("block_search");
                drawPositions(fillVertices, outlineVertices(context, style), matrices, SEARCH_CACHE,
                    BlockSearchModule.foundBlocks, style, client.player.blockPosition());
            }

            Module activityBase = ModuleManager.getByName("Activity Scan");
            if (activityBase instanceof ActivityScanModule activity && activity.isEnabled()) {
                Settings.MarkerStyle style = Settings.markerStyle("activity_scan");
                VertexConsumer activityOutline = outlineVertices(context, style);
                BlockPos playerPos = client.player.blockPosition();
                for (MarkerCandidate candidate : nearestHotspots(
                    ACTIVITY_CACHE, DataAggregator.heatMap, playerPos, activity.getThreshold())) {
                    float score = DataAggregator.heatMap.getOrDefault(candidate.position(), 0.0f);
                    if (score < activity.getThreshold()) {
                        continue;
                    }
                    drawMarker(fillVertices, activityOutline, matrices,
                        new AABB(candidate.position()).inflate(0.5),
                        fillColor(style), outlineColor(style), style.outlineWidth);
                }
            }
        } finally {
            matrices.popPose();
        }
    }

    private static VertexConsumer outlineVertices(WorldRenderContext context, Settings.MarkerStyle style) {
        // Through-wall rendering is a local visual preference. The alternate
        // vanilla layer uses normal depth testing when the option is disabled.
        return context.consumers().getBuffer(style.throughWalls
            ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.debugFilledBox());
    }

    private static void drawPositions(VertexConsumer fillVertices, VertexConsumer outlineVertices,
                                      PoseStack matrices, MarkerCache cache, Set<BlockPos> positions,
                                      Settings.MarkerStyle style, BlockPos origin) {
        for (MarkerCandidate candidate : nearestPositions(cache, positions, origin)) {
            drawMarker(fillVertices, outlineVertices, matrices,
                new AABB(candidate.position()), fillColor(style), outlineColor(style), style.outlineWidth);
        }
    }

    private static void drawMarker(VertexConsumer fillVertices, VertexConsumer outlineVertices,
                                   PoseStack matrices, AABB box, int fillColor, int outlineColor,
                                   double outlineWidth) {
        drawBox(fillVertices, matrices, box, fillColor, false, false);
        drawOutline(outlineVertices, matrices, box, outlineColor, outlineWidth);
    }

    private static int fillColor(Settings.MarkerStyle style) {
        return colorWithOpacity(style.fillColor, (float) style.fillOpacity);
    }

    private static int outlineColor(Settings.MarkerStyle style) {
        return colorWithOpacity(style.outlineColor, 1.0f);
    }

    private static int colorWithOpacity(int color, float opacity) {
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        return ARGB.colorFromFloat(Math.max(0.0f, Math.min(1.0f, opacity)), red, green, blue);
    }

    // Search results can be large (for example, a common block type). Cache the
    // bounded nearest-first snapshot so repeated render frames do not rescan it.
    private static List<MarkerCandidate> nearestPositions(MarkerCache cache,
                                                           Set<BlockPos> positions, BlockPos origin) {
        long revision = DataAggregator.getResultRevision();
        if (cache.matches(revision, origin, Float.NaN)) {
            return cache.candidates();
        }

        PriorityQueue<MarkerCandidate> nearest = newNearestQueue();
        for (BlockPos pos : positions) {
            double distanceSquared = origin.distSqr(pos);
            if (distanceSquared <= MAX_MARKER_DISTANCE_SQUARED) {
                keepNearest(nearest, new MarkerCandidate(pos, distanceSquared));
            }
        }
        return cache.store(revision, origin, Float.NaN, sortedCandidates(nearest));
    }

    private static List<MarkerCandidate> nearestHotspots(MarkerCache cache, Map<BlockPos, Float> heatMap,
                                                          BlockPos origin, float threshold) {
        long revision = DataAggregator.getResultRevision();
        if (cache.matches(revision, origin, threshold)) {
            return cache.candidates();
        }

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
        return cache.store(revision, origin, threshold, sortedCandidates(nearest));
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

    private static List<MarkerCandidate> sortedCandidates(PriorityQueue<MarkerCandidate> candidates) {
        ArrayList<MarkerCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(NEAREST_FIRST);
        return List.copyOf(sorted);
    }

    private static void drawOutline(VertexConsumer vertices, PoseStack matrices, AABB box,
                                    int color, double thickness) {
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

    private static final class MarkerCache {
        private long revision = Long.MIN_VALUE;
        private BlockPos origin;
        private float threshold = Float.NaN;
        private List<MarkerCandidate> candidates = List.of();

        private boolean matches(long revision, BlockPos origin, float threshold) {
            return this.revision == revision && origin.equals(this.origin)
                && Float.compare(this.threshold, threshold) == 0;
        }

        private List<MarkerCandidate> candidates() {
            return candidates;
        }

        private List<MarkerCandidate> store(long revision, BlockPos origin, float threshold,
                                            List<MarkerCandidate> candidates) {
            this.revision = revision;
            this.origin = origin;
            this.threshold = threshold;
            this.candidates = candidates;
            return candidates;
        }
    }
}
