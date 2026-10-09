package me.kanha.ru.hud;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import me.kanha.ru.scan.DataAggregator;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class DisplayLayer {
    private DisplayLayer() {
    }

    public static void init() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("renderutil", "overlay"),
            DisplayLayer::render
        );
    }

    private static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (!RenderUtilClient.hasLocalWorld(client) || client.options.hideGui || !ModuleManager.anyEnabled()) {
            return;
        }

        int x = 6;
        int y = 6;
        int lineHeight = 12;
        graphics.drawString(client.font, "RenderUtil  •  local world", x, y, 0xFF4A9EFF, true);
        y += lineHeight;

        Module containers = ModuleManager.getByName("Container ESP");
        if (containers != null && containers.isEnabled()) {
            graphics.drawString(client.font, "Containers: " + DataAggregator.trackedContainers.size(),
                x, y, 0xFFFFFFFF, true);
            y += lineHeight;
        }

        Module spawners = ModuleManager.getByName("Spawner ESP");
        if (spawners != null && spawners.isEnabled()) {
            graphics.drawString(client.font, "Spawners: " + DataAggregator.trackedSpawners.size(),
                x, y, 0xFFFFFFFF, true);
            y += lineHeight;
        }

        Module search = ModuleManager.getByName("Block Search");
        if (search != null && search.isEnabled()) {
            graphics.drawString(client.font,
                "Block search: " + BlockSearchModule.foundBlocks.size() + " found / "
                    + BlockSearchModule.searchBlocks.size() + " selected",
                x, y, 0xFFFFFFFF, true);
            y += lineHeight;
        }

        Module activity = ModuleManager.getByName("Activity Scan");
        if (activity != null && activity.isEnabled()) {
            BlockPos hotspot = getNearestHotspot(client, activity);
            if (hotspot != null) {
                double distance = client.player.position().distanceTo(Vec3.atCenterOf(hotspot));
                float score = DataAggregator.heatMap.getOrDefault(hotspot, 0.0f);
                graphics.drawString(client.font,
                    String.format("Hotspot: %d %d %d  (%.0fm, %.0f)",
                        hotspot.getX(), hotspot.getY(), hotspot.getZ(), distance, score),
                    x, y, 0xFFFFFFFF, true);
                y += lineHeight;
            }
        }

        BlockPos nearest = getNearestMarker(client);
        if (nearest != null) {
            double distance = client.player.position().distanceTo(Vec3.atCenterOf(nearest));
            graphics.drawString(client.font,
                String.format("Nearest marker: %d %d %d  (%.0fm)",
                    nearest.getX(), nearest.getY(), nearest.getZ(), distance),
                x, y, 0xFFCCCCCC, true);
        }
    }

    private static BlockPos getNearestHotspot(Minecraft client, Module activity) {
        BlockPos nearest = null;
        double bestDistance = Double.MAX_VALUE;
        float threshold = activity instanceof me.kanha.ru.module.ActivityScanModule scan
            ? scan.getThreshold() : 8.0f;

        for (var entry : DataAggregator.heatMap.entrySet()) {
            if (entry.getValue() < threshold) {
                continue;
            }
            double distance = client.player.blockPosition().distSqr(entry.getKey());
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = entry.getKey();
            }
        }
        return nearest;
    }

    private static BlockPos getNearestMarker(Minecraft client) {
        BlockPos origin = client.player.blockPosition();
        BlockPos nearest = nearestOf(origin, DataAggregator.trackedContainers, Double.MAX_VALUE);
        double bestDistance = nearest == null ? Double.MAX_VALUE : origin.distSqr(nearest);

        BlockPos candidate = nearestOf(origin, DataAggregator.trackedSpawners, bestDistance);
        if (candidate != null) {
            nearest = candidate;
            bestDistance = origin.distSqr(candidate);
        }

        candidate = nearestOf(origin, BlockSearchModule.foundBlocks, bestDistance);
        if (candidate != null) {
            nearest = candidate;
        }
        return nearest;
    }

    private static BlockPos nearestOf(BlockPos origin, Set<BlockPos> positions, double maximumDistance) {
        BlockPos nearest = null;
        double bestDistance = maximumDistance;
        for (BlockPos pos : positions) {
            double distance = origin.distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = pos;
            }
        }
        return nearest;
    }
}
