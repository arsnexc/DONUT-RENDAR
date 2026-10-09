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

import java.util.Set;

/** Draws local-world markers using the 1.21.11 world-render event API. */
public final class WorldPainter {
    private static final int MAX_MARKERS_PER_MODULE = 200;
    private static final double MAX_MARKER_DISTANCE_SQUARED = 96.0 * 96.0;

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
        if (matrices == null) {
            return;
        }

        Vec3 camera = context.worldState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        VertexConsumer vertices = context.consumers().getBuffer(RenderTypes.debugFilledBox());

        Module containerBase = ModuleManager.getByName("Container ESP");
        if (containerBase instanceof ContainerEspModule container && container.isEnabled() && container.shouldHighlight()) {
            drawPositions(vertices, matrices, DataAggregator.trackedContainers,
                ARGB.colorFromFloat(0.22f, 1.0f, 0.20f, 0.20f), client.player.blockPosition());
        }

        Module spawnerBase = ModuleManager.getByName("Spawner ESP");
        if (spawnerBase instanceof SpawnerEspModule spawner && spawner.isEnabled() && spawner.shouldHighlight()) {
            drawPositions(vertices, matrices, DataAggregator.trackedSpawners,
                ARGB.colorFromFloat(0.24f, 1.0f, 0.90f, 0.18f), client.player.blockPosition());
        }

        Module searchBase = ModuleManager.getByName("Block Search");
        if (searchBase instanceof BlockSearchModule search && search.isEnabled() && search.shouldHighlight()) {
            drawPositions(vertices, matrices, BlockSearchModule.foundBlocks,
                ARGB.colorFromFloat(0.22f, 0.25f, 0.65f, 1.0f), client.player.blockPosition());
        }

        Module activityBase = ModuleManager.getByName("Activity Scan");
        if (activityBase instanceof ActivityScanModule activity && activity.isEnabled()) {
            int rendered = 0;
            BlockPos playerPos = client.player.blockPosition();
            for (var entry : DataAggregator.heatMap.entrySet()) {
                if (rendered >= MAX_MARKERS_PER_MODULE) {
                    break;
                }
                BlockPos pos = entry.getKey();
                if (playerPos.distSqr(pos) > MAX_MARKER_DISTANCE_SQUARED || entry.getValue() < activity.getThreshold()) {
                    continue;
                }
                float red = Math.min(1.0f, entry.getValue() / 20.0f);
                float blue = 1.0f - red;
                drawFilledBox(vertices, matrices, new AABB(pos).inflate(0.5),
                    ARGB.colorFromFloat(0.20f, red, 0.75f, blue));
                rendered++;
            }
        }

        matrices.popPose();
    }

    private static void drawPositions(VertexConsumer vertices, PoseStack matrices,
                                      Set<BlockPos> positions, int color, BlockPos origin) {
        int rendered = 0;
        for (BlockPos pos : positions) {
            if (rendered >= MAX_MARKERS_PER_MODULE) {
                break;
            }
            if (origin.distSqr(pos) > MAX_MARKER_DISTANCE_SQUARED) {
                continue;
            }
            drawFilledBox(vertices, matrices, new AABB(pos), color);
            rendered++;
        }
    }

    private static void drawFilledBox(VertexConsumer vertices, PoseStack matrices, AABB box, int color) {
        Matrix4f matrix = matrices.last().pose();
        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float z1 = (float) box.minZ;
        float x2 = (float) box.maxX;
        float y2 = (float) box.maxY;
        float z2 = (float) box.maxZ;

        // Front face.
        vertex(vertices, matrix, x1, y1, z1, color);
        vertex(vertices, matrix, x2, y1, z1, color);
        vertex(vertices, matrix, x2, y2, z1, color);
        vertex(vertices, matrix, x1, y2, z1, color);
        // Back face.
        vertex(vertices, matrix, x2, y1, z2, color);
        vertex(vertices, matrix, x1, y1, z2, color);
        vertex(vertices, matrix, x1, y2, z2, color);
        vertex(vertices, matrix, x2, y2, z2, color);
        // Left face.
        vertex(vertices, matrix, x1, y1, z2, color);
        vertex(vertices, matrix, x1, y1, z1, color);
        vertex(vertices, matrix, x1, y2, z1, color);
        vertex(vertices, matrix, x1, y2, z2, color);
        // Right face.
        vertex(vertices, matrix, x2, y1, z1, color);
        vertex(vertices, matrix, x2, y1, z2, color);
        vertex(vertices, matrix, x2, y2, z2, color);
        vertex(vertices, matrix, x2, y2, z1, color);
        // Top face.
        vertex(vertices, matrix, x1, y2, z1, color);
        vertex(vertices, matrix, x2, y2, z1, color);
        vertex(vertices, matrix, x2, y2, z2, color);
        vertex(vertices, matrix, x1, y2, z2, color);
        // Bottom face.
        vertex(vertices, matrix, x1, y1, z2, color);
        vertex(vertices, matrix, x2, y1, z2, color);
        vertex(vertices, matrix, x2, y1, z1, color);
        vertex(vertices, matrix, x1, y1, z1, color);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix,
                               float x, float y, float z, int color) {
        vertices.addVertex(matrix, x, y, z).setColor(color);
    }
}
