package me.kanha.ru.gui;

import me.kanha.ru.config.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Small settings page for HUD layout/colors and each local-world marker style. */
public final class AppearanceScreen extends Screen {
    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 392;
    private static final int ROW_HEIGHT = 22;
    private static final int[] PALETTE = {
        0xFFFF4D4D, 0xFFFFA52E, 0xFFF2E84A, 0xFF55D879,
        0xFF36C9CF, 0xFF4C8DFF, 0xFFB36BFF, 0xFFFFFFFF
    };
    private static final List<String> MARKER_IDS = List.of(
        "container_esp", "spawner_esp", "block_search", "activity_scan"
    );
    private static final List<String> MARKER_NAMES = List.of(
        "Container ESP", "Spawner ESP", "Block Search", "Activity Scan"
    );

    private int panelX;
    private int panelY;
    private int selectedMarker;

    public AppearanceScreen() {
        super(Component.literal("RenderUtil Appearance"));
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xF01A1A1A);
        graphics.renderOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);
        graphics.drawString(font, "HUD & MARKER APPEARANCE", panelX + 14, panelY + 12, 0xFF4A9EFF, false);
        graphics.drawString(font, "Changes are client-local; scan markers remain single-player only.",
            panelX + 14, panelY + 27, 0xFFAAAAAA, false);

        int y = panelY + 48;
        drawSection(graphics, "HUD", y);
        y += 18;
        Settings.HudPreferences hud = Settings.hud();
        drawRow(graphics, y, "HUD visibility", hud.visible ? "ON" : "OFF", 0xFFE0E0E0);
        y += ROW_HEIGHT;
        drawRow(graphics, y, "Layout", hud.layout.equals("COMPACT") ? "COMPACT" : "STACKED", 0xFFE0E0E0);
        y += ROW_HEIGHT;
        drawRow(graphics, y, "Screen corner", hud.corner.replace('_', ' '), 0xFFE0E0E0);
        y += ROW_HEIGHT;
        drawRow(graphics, y, "Per-feature scan coverage", hud.showCoverage ? "ON" : "OFF", 0xFFE0E0E0);
        y += ROW_HEIGHT;
        drawColorRow(graphics, y, "HUD text color", hud.textColor);
        y += ROW_HEIGHT;
        drawColorRow(graphics, y, "HUD accent color", hud.accentColor);

        y += 6;
        drawSection(graphics, "MARKER STYLE", y);
        y += 18;
        drawRow(graphics, y, "Feature", MARKER_NAMES.get(selectedMarker) + "   [click to change]", 0xFFE0E0E0);
        y += ROW_HEIGHT;
        Settings.MarkerStyle style = Settings.markerStyle(MARKER_IDS.get(selectedMarker));
        drawColorRow(graphics, y, "Fill color", style.fillColor);
        y += ROW_HEIGHT;
        drawColorRow(graphics, y, "Outline color", style.outlineColor);
        y += ROW_HEIGHT;
        drawRow(graphics, y, "Fill opacity", String.format("%.0f%%", style.fillOpacity * 100), 0xFFE0E0E0);
        y += ROW_HEIGHT;
        drawRow(graphics, y, "Outline width", String.format("%.2f blocks", style.outlineWidth), 0xFFE0E0E0);
        y += ROW_HEIGHT;
        drawRow(graphics, y, "Through-wall outline (local)", style.throughWalls ? "ON" : "OFF", 0xFFE0E0E0);

        graphics.drawString(font, "Left click: next / increase   ·   Right click: previous / decrease   ·   Esc: back",
            panelX + 14, panelY + PANEL_HEIGHT - 19, 0xFF888888, false);
    }

    private void drawSection(GuiGraphics graphics, String label, int y) {
        graphics.fill(panelX + 10, y, panelX + PANEL_WIDTH - 10, y + 16, 0xFF252525);
        graphics.drawString(font, label, panelX + 17, y + 4, 0xFF7AB7FF, false);
    }

    private void drawRow(GuiGraphics graphics, int y, String label, String value, int valueColor) {
        graphics.fill(panelX + 10, y, panelX + PANEL_WIDTH - 10, y + ROW_HEIGHT, 0xFF1E1E1E);
        graphics.drawString(font, label, panelX + 18, y + 7, 0xFFBBBBBB, false);
        graphics.drawString(font, value, panelX + PANEL_WIDTH - 18 - font.width(value), y + 7,
            valueColor, false);
    }

    private void drawColorRow(GuiGraphics graphics, int y, String label, int color) {
        graphics.fill(panelX + 10, y, panelX + PANEL_WIDTH - 10, y + ROW_HEIGHT, 0xFF1E1E1E);
        graphics.drawString(font, label, panelX + 18, y + 7, 0xFFBBBBBB, false);
        int swatchX = panelX + PANEL_WIDTH - 42;
        String hex = Settings.colorHex(color);
        graphics.drawString(font, hex, swatchX - 8 - font.width(hex), y + 7, 0xFFE0E0E0, false);
        graphics.fill(swatchX, y + 4, swatchX + 22, y + 18, color | 0xFF000000);
        graphics.renderOutline(swatchX, y + 4, 22, 14, 0xFFEEEEEE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int x = (int) event.x();
        int y = (int) event.y();
        int rowX = panelX + 10;
        if (x < rowX || x > panelX + PANEL_WIDTH - 10) {
            return super.mouseClicked(event, doubled);
        }

        int row = rowAt(y);
        if (row < 0) {
            return super.mouseClicked(event, doubled);
        }
        int direction = event.button() == 1 ? -1 : 1;
        Settings.HudPreferences hud = Settings.hud();
        Settings.MarkerStyle style = Settings.markerStyle(MARKER_IDS.get(selectedMarker));

        switch (row) {
            case 0 -> hud.visible = !hud.visible;
            case 1 -> hud.layout = hud.layout.equals("STACKED") ? "COMPACT" : "STACKED";
            case 2 -> hud.corner = nextCorner(hud.corner, direction);
            case 3 -> hud.showCoverage = !hud.showCoverage;
            case 4 -> hud.textColor = nextColor(hud.textColor, direction);
            case 5 -> hud.accentColor = nextColor(hud.accentColor, direction);
            case 6 -> selectedMarker = Math.floorMod(selectedMarker + direction, MARKER_IDS.size());
            case 7 -> style.fillColor = nextColor(style.fillColor, direction);
            case 8 -> style.outlineColor = nextColor(style.outlineColor, direction);
            case 9 -> style.fillOpacity = clamp(style.fillOpacity + direction * 0.05, 0.02, 0.80);
            case 10 -> style.outlineWidth = clamp(style.outlineWidth + direction * 0.01, 0.01, 0.15);
            case 11 -> style.throughWalls = !style.throughWalls;
            default -> {
                return super.mouseClicked(event, doubled);
            }
        }
        Settings.save();
        return true;
    }

    private int rowAt(int mouseY) {
        int y = panelY + 48 + 18;
        if (mouseY >= y && mouseY < y + ROW_HEIGHT * 6) {
            return (mouseY - y) / ROW_HEIGHT;
        }
        y += ROW_HEIGHT * 6 + 6 + 18;
        if (mouseY >= y && mouseY < y + ROW_HEIGHT * 6) {
            return 6 + (mouseY - y) / ROW_HEIGHT;
        }
        return -1;
    }

    private String nextCorner(String corner, int direction) {
        List<String> corners = List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_RIGHT", "BOTTOM_LEFT");
        int current = corners.indexOf(corner);
        return corners.get(Math.floorMod(current + direction, corners.size()));
    }

    private int nextColor(int current, int direction) {
        int index = -1;
        for (int i = 0; i < PALETTE.length; i++) {
            if ((PALETTE[i] & 0x00FFFFFF) == (current & 0x00FFFFFF)) {
                index = i;
                break;
            }
        }
        return PALETTE[Math.floorMod(index + direction, PALETTE.length)];
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Settings.save();
        Minecraft.getInstance().setScreen(new ClickGuiScreen());
    }
}
