package me.kanha.ru.gui;

import me.kanha.ru.module.BlockSearchModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public final class BlockSearchScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 420;
    private static final int SEARCH_HEIGHT = 22;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_VISIBLE = 16;

    private final List<Block> allBlocks = new ArrayList<>();
    private final List<Block> filteredBlocks = new ArrayList<>();
    private EditBox searchField;
    private int scrollOffset;
    private int panelX;
    private int panelY;

    public BlockSearchScreen() {
        super(Component.literal("Block Search"));
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;

        allBlocks.clear();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR) {
                allBlocks.add(block);
            }
        }
        allBlocks.sort((left, right) -> left.getName().getString()
            .compareToIgnoreCase(right.getName().getString()));

        searchField = new EditBox(Minecraft.getInstance().font,
            panelX + 12, panelY + 12, PANEL_WIDTH - 24, SEARCH_HEIGHT,
            Component.literal("Search blocks"));
        searchField.setResponder(this::updateFilter);
        addRenderableWidget(searchField);
        searchField.setFocused(true);
        updateFilter("");
    }

    private void updateFilter(String query) {
        filteredBlocks.clear();
        String lower = query.toLowerCase().trim();
        for (Block block : allBlocks) {
            String displayName = block.getName().getString().toLowerCase();
            String identifier = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase();
            if (lower.isEmpty() || displayName.contains(lower) || identifier.contains(lower)) {
                filteredBlocks.add(block);
            }
        }
        scrollOffset = Math.min(scrollOffset, Math.max(0, filteredBlocks.size() - MAX_VISIBLE));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xF01A1A1A);
        graphics.renderOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);
        graphics.drawString(font, "BLOCK CATALOGUE", panelX + 12, panelY - 14, 0xFF4A9EFF, false);

        // Draw widgets before the rows so the search box stays visible.
        super.render(graphics, mouseX, mouseY, delta);

        int listY = panelY + 12 + SEARCH_HEIGHT + 8;
        int listHeight = MAX_VISIBLE * ROW_HEIGHT;
        graphics.enableScissor(panelX + 6, listY, panelX + PANEL_WIDTH - 6, listY + listHeight);

        int end = Math.min(scrollOffset + MAX_VISIBLE, filteredBlocks.size());
        for (int i = scrollOffset; i < end; i++) {
            Block block = filteredBlocks.get(i);
            int rowY = listY + (i - scrollOffset) * ROW_HEIGHT;
            boolean selected = BlockSearchModule.searchBlocks.contains(block);
            boolean hovered = mouseX >= panelX + 6 && mouseX <= panelX + PANEL_WIDTH - 6
                && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;

            int background = selected ? 0xFF246CB5 : (hovered ? 0xFF303030 : 0xFF232323);
            graphics.fill(panelX + 6, rowY, panelX + PANEL_WIDTH - 6, rowY + ROW_HEIGHT, background);

            String name = block.getName().getString();
            graphics.drawString(font, (selected ? "[+] " : "    ") + name,
                panelX + 12, rowY + 5, selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);

            String identifier = BuiltInRegistries.BLOCK.getKey(block).toString();
            int identifierWidth = font.width(identifier);
            graphics.drawString(font, identifier,
                panelX + PANEL_WIDTH - 18 - identifierWidth, rowY + 5, 0xFF888888, false);
        }
        graphics.disableScissor();

        if (filteredBlocks.size() > MAX_VISIBLE) {
            int scrollBarHeight = Math.max(20, listHeight * MAX_VISIBLE / filteredBlocks.size());
            int scrollRange = Math.max(1, filteredBlocks.size() - MAX_VISIBLE);
            int scrollBarY = listY + (listHeight - scrollBarHeight) * scrollOffset / scrollRange;
            graphics.fill(panelX + PANEL_WIDTH - 8, listY,
                panelX + PANEL_WIDTH - 6, listY + listHeight, 0xFF333333);
            graphics.fill(panelX + PANEL_WIDTH - 8, scrollBarY,
                panelX + PANEL_WIDTH - 6, scrollBarY + scrollBarHeight, 0xFF4A9EFF);
        }

        graphics.drawString(font,
            "Selected: " + BlockSearchModule.searchBlocks.size() + " blocks",
            panelX + 12, panelY + PANEL_HEIGHT - 18, 0xFFFFFFFF, false);
        graphics.drawString(font, "ESC: back  |  Left-click: toggle  |  Right-click: remove",
            panelX + 145, panelY + PANEL_HEIGHT - 18, 0xFF888888, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int listY = panelY + 12 + SEARCH_HEIGHT + 8;
        int listHeight = MAX_VISIBLE * ROW_HEIGHT;

        if (mouseX >= panelX + 6 && mouseX <= panelX + PANEL_WIDTH - 6
            && mouseY >= listY && mouseY < listY + listHeight) {
            int index = (mouseY - listY) / ROW_HEIGHT + scrollOffset;
            if (index >= 0 && index < filteredBlocks.size()) {
                Block block = filteredBlocks.get(index);
                if (event.button() == 0) {
                    if (BlockSearchModule.searchBlocks.contains(block)) {
                        BlockSearchModule.removeBlock(block);
                    } else {
                        BlockSearchModule.addBlock(block);
                    }
                } else if (event.button() == 1) {
                    BlockSearchModule.removeBlock(block);
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, filteredBlocks.size() - MAX_VISIBLE);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            Minecraft.getInstance().setScreen(new ClickGuiScreen());
            return true;
        }
        if (searchField != null && searchField.isFocused() && searchField.keyPressed(event)) {
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
        Minecraft.getInstance().setScreen(new ClickGuiScreen());
    }
}
