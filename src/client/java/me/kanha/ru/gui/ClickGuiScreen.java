package me.kanha.ru.gui;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.config.Settings;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ClickGuiScreen extends Screen {
    private static final int PANEL_WIDTH = 190;
    private static final int HEADER_HEIGHT = 24;
    private static final int MODULE_HEIGHT = 19;
    private static final int SETTING_HEIGHT = 16;

    private static final int BG_COLOR = 0xF01A1A1A;
    private static final int HEADER_COLOR = 0xFF2D2D2D;
    private static final int MODULE_BG = 0xFF232323;
    private static final int MODULE_HOVER = 0xFF303030;
    private static final int MODULE_ENABLED = 0xFF246CB5;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int BORDER_COLOR = 0xFF3A3A3A;
    private static final int ACCENT = 0xFF4A9EFF;

    private final List<Panel> panels = new ArrayList<>();
    private Panel draggingPanel;
    private double dragOffsetX;
    private double dragOffsetY;

    public ClickGuiScreen() {
        super(Component.literal("RenderUtil"));
    }

    @Override
    protected void init() {
        panels.clear();
        int x = 24;
        int y = 42;
        for (Module.Category category : Module.Category.values()) {
            if (!ModuleManager.getModulesByCategory(category).isEmpty()) {
                panels.add(new Panel(category, x, y));
                x += PANEL_WIDTH + 12;
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
        if (!RenderUtilClient.hasLocalWorld(Minecraft.getInstance())) {
            String message = "Scan modules are available only in a local single-player world.";
            graphics.drawString(font, message, (width - font.width(message)) / 2, 18, 0xFFFFCC66, true);
        }

        for (Panel panel : panels) {
            renderPanel(graphics, panel, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void renderPanel(GuiGraphics graphics, Panel panel, int mouseX, int mouseY) {
        int totalHeight = getPanelHeight(panel);
        int x = panel.x;
        int y = panel.y;

        graphics.fill(x, y, x + PANEL_WIDTH, y + totalHeight, BG_COLOR);
        graphics.fill(x, y, x + PANEL_WIDTH, y + HEADER_HEIGHT, HEADER_COLOR);
        graphics.drawString(font, panel.category.displayName.toUpperCase(), x + 9, y + 8, ACCENT, false);
        graphics.renderOutline(x, y, PANEL_WIDTH, totalHeight, BORDER_COLOR);

        int moduleY = y + HEADER_HEIGHT;
        for (Module module : ModuleManager.getModulesByCategory(panel.category)) {
            boolean hovered = isHovered(mouseX, mouseY, x, moduleY, PANEL_WIDTH, MODULE_HEIGHT);
            int background = module.isEnabled() ? MODULE_ENABLED : (hovered ? MODULE_HOVER : MODULE_BG);
            graphics.fill(x + 1, moduleY, x + PANEL_WIDTH - 1, moduleY + MODULE_HEIGHT, background);
            graphics.drawString(font, module.getName(), x + 10, moduleY + 6,
                module.isEnabled() ? TEXT_COLOR : TEXT_DIM, false);

            if (module.hasSettings()) {
                graphics.drawString(font, module.isExpanded() ? "v" : ">",
                    x + PANEL_WIDTH - 15, moduleY + 6, TEXT_DIM, false);
            }

            moduleY += MODULE_HEIGHT;
            if (module.isExpanded()) {
                for (Module.Setting setting : module.getSettings()) {
                    graphics.fill(x + 1, moduleY, x + PANEL_WIDTH - 1,
                        moduleY + SETTING_HEIGHT, 0xFF1C1C1C);
                    graphics.drawString(font, setting.getName(), x + 10, moduleY + 4, TEXT_DIM, false);
                    String value = setting.getValueText();
                    graphics.drawString(font, value,
                        x + PANEL_WIDTH - 10 - font.width(value), moduleY + 4, 0xFFE0E0E0, false);
                    moduleY += SETTING_HEIGHT;
                }
            }
        }
    }

    private int getPanelHeight(Panel panel) {
        int totalHeight = HEADER_HEIGHT;
        for (Module module : ModuleManager.getModulesByCategory(panel.category)) {
            totalHeight += MODULE_HEIGHT;
            if (module.isExpanded()) {
                totalHeight += module.getSettings().size() * SETTING_HEIGHT;
            }
        }
        return totalHeight;
    }

    private boolean isHovered(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        for (Panel panel : panels) {
            if (isHovered(mouseX, mouseY, panel.x, panel.y, PANEL_WIDTH, HEADER_HEIGHT)) {
                if (button == 0) {
                    draggingPanel = panel;
                    dragOffsetX = mouseX - panel.x;
                    dragOffsetY = mouseY - panel.y;
                    return true;
                }
            }

            int moduleY = panel.y + HEADER_HEIGHT;
            for (Module module : ModuleManager.getModulesByCategory(panel.category)) {
                if (isHovered(mouseX, mouseY, panel.x, moduleY, PANEL_WIDTH, MODULE_HEIGHT)) {
                    if (button == 0) {
                        module.toggle();
                    } else if (button == 1 && module.hasSettings()) {
                        module.setExpanded(!module.isExpanded());
                    }
                    return true;
                }

                moduleY += MODULE_HEIGHT;
                if (module.isExpanded()) {
                    for (Module.Setting setting : module.getSettings()) {
                        if (isHovered(mouseX, mouseY, panel.x, moduleY, PANEL_WIDTH, SETTING_HEIGHT)) {
                            changeSetting(setting, button);
                            return true;
                        }
                        moduleY += SETTING_HEIGHT;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void changeSetting(Module.Setting setting, int button) {
        if (setting instanceof Module.BooleanSetting booleanSetting && button == 0) {
            booleanSetting.toggle();
        } else if (setting instanceof Module.NumberSetting numberSetting) {
            if (button == 0) {
                numberSetting.adjust(1);
            } else if (button == 1) {
                numberSetting.adjust(-1);
            }
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPanel = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingPanel != null) {
            draggingPanel.x = (int) (event.x() - dragOffsetX);
            draggingPanel.y = (int) (event.y() - dragOffsetY);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        if (event.key() == 90) {
            Minecraft.getInstance().setScreen(new BlockSearchScreen());
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
        Minecraft.getInstance().setScreen(null);
    }

    private static final class Panel {
        private final Module.Category category;
        private int x;
        private int y;

        private Panel(Module.Category category, int x, int y) {
            this.category = category;
            this.x = x;
            this.y = y;
        }
    }
}
