package me.kanha.ru.hud;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.config.Settings;
import me.kanha.ru.module.ActivityScanModule;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import me.kanha.ru.scan.DataAggregator;
import me.kanha.ru.scan.ScanWindow;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        Settings.HudPreferences preferences = Settings.hud();
        if (!RenderUtilClient.hasLocalWorld(client) || client.options.hideGui || !preferences.visible
            || !ModuleManager.anyEnabled()) {
            return;
        }

        boolean compact = preferences.layout.equals("COMPACT");
        ArrayList<HudLine> lines = new ArrayList<>();
        lines.add(new HudLine("RenderUtil  ·  local world", preferences.accentColor));

        Module containers = ModuleManager.getByName("Container ESP");
        if (containers != null && containers.isEnabled()) {
            appendFeature(lines, containers, "Containers", Integer.toString(DataAggregator.trackedContainers.size()), compact, preferences);
        }

        Module spawners = ModuleManager.getByName("Spawner ESP");
        if (spawners != null && spawners.isEnabled()) {
            appendFeature(lines, spawners, "Spawners", Integer.toString(DataAggregator.trackedSpawners.size()), compact, preferences);
        }

        Module search = ModuleManager.getByName("Block Search");
        if (search != null && search.isEnabled()) {
            String count = BlockSearchModule.foundBlocks.size() + "/" + BlockSearchModule.searchBlocks.size() + " IDs";
            appendFeature(lines, search, "Block search", count, compact, preferences);
        }

        Module activityBase = ModuleManager.getByName("Activity Scan");
        if (activityBase instanceof ActivityScanModule activity && activity.isEnabled()) {
            BlockPos hotspot = getNearestHotspot(client, activity);
            if (hotspot == null) {
                lines.add(new HudLine("Activity estimate: no cells over threshold", preferences.textColor));
            } else {
                double distance = client.player.position().distanceTo(Vec3.atCenterOf(hotspot));
                float score = DataAggregator.heatMap.getOrDefault(hotspot, 0.0f);
                String activityLine = String.format(Locale.ROOT,
                    "Activity estimate: %d %d %d · %.0fm · score %.0f",
                    hotspot.getX(), hotspot.getY(), hotspot.getZ(), distance, score);
                lines.add(new HudLine(activityLine, preferences.textColor));
                if (!compact) {
                    lines.add(new HudLine(String.format(Locale.ROOT,
                        "Map cell %d×%d blocks / 16 high · heuristic, not player-placement proof",
                        activity.getCellSize(), activity.getCellSize()), 0xFFB5B5B5));
                }
            }
            if (preferences.showCoverage) {
                appendCoverage(lines, activity, compact, preferences);
            }
        }

        BlockPos nearest = getNearestMarker(client);
        if (nearest != null) {
            double distance = client.player.position().distanceTo(Vec3.atCenterOf(nearest));
            lines.add(new HudLine(String.format(Locale.ROOT, "Nearest marker: %d %d %d · %.0fm",
                nearest.getX(), nearest.getY(), nearest.getZ(), distance), preferences.textColor));
        }

        int lineHeight = compact ? 10 : 12;
        int padding = compact ? 3 : 5;
        int contentWidth = 0;
        for (HudLine line : lines) {
            contentWidth = Math.max(contentWidth, client.font.width(line.text()));
        }
        int boxWidth = contentWidth + padding * 2;
        int boxHeight = lines.size() * lineHeight + padding * 2;
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        boolean right = preferences.corner.endsWith("RIGHT");
        boolean bottom = preferences.corner.startsWith("BOTTOM");
        int x = right ? screenWidth - boxWidth - 6 : 6;
        int y = bottom ? screenHeight - boxHeight - 6 : 6;
        graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x70000000);

        int textY = y + padding;
        for (HudLine line : lines) {
            graphics.drawString(client.font, line.text(), x + padding, textY, line.color(), true);
            textY += lineHeight;
        }
    }

    private static void appendFeature(List<HudLine> lines, Module module, String label, String result,
                                      boolean compact, Settings.HudPreferences preferences) {
        if (compact) {
            ScanWindow.Coverage coverage = DataAggregator.getCoverage(module.getId());
            String coverageText = !preferences.showCoverage ? ""
                : DataAggregator.isScanning(module.getId())
                    ? String.format(Locale.ROOT, " · L%d/%d V%d", coverage.loaded(), coverage.total(), coverage.visited())
                    : " · scan off R" + module.getScanRadius();
            lines.add(new HudLine(label + ": " + result + coverageText, preferences.textColor));
        } else {
            lines.add(new HudLine(label + ": " + result, preferences.textColor));
            if (preferences.showCoverage) {
                appendCoverage(lines, module, false, preferences);
            }
        }
    }

    private static void appendCoverage(List<HudLine> lines, Module module, boolean compact,
                                      Settings.HudPreferences preferences) {
        ScanWindow.Coverage coverage = DataAggregator.getCoverage(module.getId());
        if (DataAggregator.isScanning(module.getId())) {
            String text = compact
                ? String.format(Locale.ROOT, "%s coverage L%d/%d V%d", module.getName(),
                    coverage.loaded(), coverage.total(), coverage.visited())
                : String.format(Locale.ROOT, "%s coverage: %d/%d loaded · %d visited",
                    module.getName(), coverage.loaded(), coverage.total(), coverage.visited());
            lines.add(new HudLine(text, compact ? preferences.textColor : 0xFFCCCCCC));
        } else {
            lines.add(new HudLine(module.getName() + " coverage: scan idle · radius " + module.getScanRadius(),
                0xFFCCCCCC));
        }
    }

    private static BlockPos getNearestHotspot(Minecraft client, ActivityScanModule activity) {
        BlockPos nearest = null;
        double bestDistance = Double.MAX_VALUE;
        float threshold = activity.getThreshold();

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

    private record HudLine(String text, int color) {
    }
}
