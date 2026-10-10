package me.kanha.ru.gui;

import me.kanha.ru.config.Settings;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import me.kanha.ru.scan.DataAggregator;
import me.kanha.ru.scan.ScanWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BlockSearchScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 456;
    private static final int SEARCH_HEIGHT = 22;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_VISIBLE = 14;
    private static final int LIST_Y_OFFSET = 132;
    private static final int CATALOG_X_OFFSET = 8;
    private static final int CATALOG_WIDTH = 244;
    private static final int RESULTS_X_OFFSET = 262;
    private static final int RESULTS_WIDTH = 250;
    private static final int MAX_COORDINATE_RESULTS = 512;

    private final List<Block> allBlocks = new ArrayList<>();
    private final List<Block> filteredBlocks = new ArrayList<>();
    private final List<BlockPos> nearbyResults = new ArrayList<>();
    private EditBox searchField;
    private EditBox presetNameField;
    private int scrollOffset;
    private int resultScrollOffset;
    private int coordinateIndex;
    private int panelX;
    private int panelY;
    private FilterMode filterMode = FilterMode.ALL;
    private List<String> presetNames = List.of();
    private int presetIndex = -1;
    private String lastCopiedCoordinates = "";

    public BlockSearchScreen() {
        super(Component.literal("Block Search"));
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = Math.max(6, (height - PANEL_HEIGHT) / 2);

        allBlocks.clear();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR) {
                allBlocks.add(block);
            }
        }
        allBlocks.sort(Comparator.comparing(block -> block.getName().getString(), String.CASE_INSENSITIVE_ORDER));

        searchField = new EditBox(Minecraft.getInstance().font,
            panelX + 12, panelY + 24, 250, SEARCH_HEIGHT, Component.literal("Search blocks or IDs"));
        searchField.setResponder(this::updateFilter);
        addRenderableWidget(searchField);

        presetNameField = new EditBox(Minecraft.getInstance().font,
            panelX + 12, panelY + 77, 150, 20, Component.literal("Preset name"));
        presetNameField.setMaxLength(32);
        addRenderableWidget(presetNameField);

        searchField.setFocused(true);
        presetNames = Settings.getPresetNames();
        if (!presetNames.isEmpty()) {
            presetIndex = 0;
        }
        updateFilter("");
    }

    private void updateFilter(String query) {
        filteredBlocks.clear();
        String lower = query.toLowerCase(Locale.ROOT).trim();
        Set<Block> foundTypes = filterMode == FilterMode.FOUND ? getFoundBlockTypes() : Set.of();
        for (Block block : allBlocks) {
            boolean matchesQuery = lower.isEmpty()
                || block.getName().getString().toLowerCase(Locale.ROOT).contains(lower)
                || BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT).contains(lower);
            boolean matchesMode = switch (filterMode) {
                case ALL -> true;
                case SELECTED -> BlockSearchModule.searchBlocks.contains(block);
                case FOUND -> foundTypes.contains(block);
            };
            if (matchesQuery && matchesMode) {
                filteredBlocks.add(block);
            }
        }
        scrollOffset = Math.min(scrollOffset, Math.max(0, filteredBlocks.size() - MAX_VISIBLE));
    }

    private Set<Block> getFoundBlockTypes() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || BlockSearchModule.foundBlocks.isEmpty()) {
            return Set.of();
        }
        Set<Block> types = new HashSet<>();
        for (BlockPos pos : BlockSearchModule.foundBlocks) {
            if (client.level.hasChunkAt(pos)) {
                types.add(client.level.getBlockState(pos).getBlock());
            }
        }
        return types;
    }

    private void updateNearbyResults() {
        nearbyResults.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            coordinateIndex = 0;
            return;
        }

        String query = searchField == null ? "" : searchField.getValue().toLowerCase(Locale.ROOT).trim();
        BlockPos origin = client.player.blockPosition();
        ArrayList<BlockPos> matching = new ArrayList<>();
        for (BlockPos pos : BlockSearchModule.foundBlocks) {
            if (!client.level.hasChunkAt(pos)) {
                continue;
            }
            Block block = client.level.getBlockState(pos).getBlock();
            String name = block.getName().getString().toLowerCase(Locale.ROOT);
            String identifier = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !name.contains(query) && !identifier.contains(query)) {
                continue;
            }
            matching.add(pos);
        }
        matching.sort(Comparator.comparingDouble(origin::distSqr)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ));
        nearbyResults.addAll(matching.subList(0, Math.min(matching.size(), MAX_COORDINATE_RESULTS)));
        if (nearbyResults.isEmpty()) {
            coordinateIndex = 0;
            resultScrollOffset = 0;
        } else {
            coordinateIndex = Math.floorMod(coordinateIndex, nearbyResults.size());
            resultScrollOffset = Math.min(resultScrollOffset,
                Math.max(0, nearbyResults.size() - MAX_VISIBLE));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        updateNearbyResults();
        graphics.fill(0, 0, width, height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xF01A1A1A);
        graphics.renderOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);
        graphics.drawString(font, "BLOCK SEARCH", panelX + 12, panelY + 8, 0xFF4A9EFF, false);

        // Draw edit widgets before the custom rows so the fields remain clickable and visible.
        super.render(graphics, mouseX, mouseY, delta);
        drawFilterButtons(graphics, mouseX, mouseY);
        drawPresetControls(graphics, mouseX, mouseY);

        ScanWindow.Coverage coverage = DataAggregator.getCoverage("block_search");
        Module searchBase = ModuleManager.getByName("Block Search");
        int radius = searchBase == null ? 4 : searchBase.getScanRadius();
        int range = searchBase instanceof BlockSearchModule search ? (int) search.getRange() : 64;
        graphics.drawString(font,
            String.format("Loaded only · R%d (%d×%d) · range %dm · loaded %d/%d · visited %d · no loading",
                radius, radius * 2 + 1, radius * 2 + 1, range,
                coverage.loaded(), coverage.total(), coverage.visited()),
            panelX + 12, panelY + 105, 0xFFFFD27A, false);

        int listY = panelY + LIST_Y_OFFSET;
        int catalogX = panelX + CATALOG_X_OFFSET;
        int resultsX = panelX + RESULTS_X_OFFSET;
        graphics.drawString(font, "BLOCK CATALOGUE  ·  " + filteredBlocks.size() + " shown",
            catalogX + 4, listY - 12, 0xFFB8B8B8, false);
        graphics.drawString(font, "NEARBY MATCHES  ·  " + nearbyResults.size(),
            resultsX + 4, listY - 12, 0xFFB8B8B8, false);
        drawCycleButtons(graphics, listY - 16, resultsX);

        graphics.enableScissor(catalogX, listY, catalogX + CATALOG_WIDTH, listY + MAX_VISIBLE * ROW_HEIGHT);
        int catalogEnd = Math.min(scrollOffset + MAX_VISIBLE, filteredBlocks.size());
        for (int i = scrollOffset; i < catalogEnd; i++) {
            Block block = filteredBlocks.get(i);
            int rowY = listY + (i - scrollOffset) * ROW_HEIGHT;
            boolean selected = BlockSearchModule.searchBlocks.contains(block);
            boolean hovered = inBounds(mouseX, mouseY, catalogX, rowY, CATALOG_WIDTH, ROW_HEIGHT);
            graphics.fill(catalogX, rowY, catalogX + CATALOG_WIDTH, rowY + ROW_HEIGHT,
                selected ? 0xFF246CB5 : (hovered ? 0xFF303030 : 0xFF232323));
            String name = block.getName().getString();
            graphics.drawString(font, (selected ? "[+] " : "    ") + name,
                catalogX + 6, rowY + 5, selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
        graphics.disableScissor();

        graphics.enableScissor(resultsX, listY, resultsX + RESULTS_WIDTH, listY + MAX_VISIBLE * ROW_HEIGHT);
        int resultEnd = Math.min(resultScrollOffset + MAX_VISIBLE, nearbyResults.size());
        for (int i = resultScrollOffset; i < resultEnd; i++) {
            BlockPos pos = nearbyResults.get(i);
            int rowY = listY + (i - resultScrollOffset) * ROW_HEIGHT;
            boolean selected = i == coordinateIndex;
            boolean hovered = inBounds(mouseX, mouseY, resultsX, rowY, RESULTS_WIDTH, ROW_HEIGHT);
            graphics.fill(resultsX, rowY, resultsX + RESULTS_WIDTH, rowY + ROW_HEIGHT,
                selected ? 0xFF385C7A : (hovered ? 0xFF303030 : 0xFF232323));
            String coordinates = String.format("%d %d %d", pos.getX(), pos.getY(), pos.getZ());
            graphics.drawString(font, coordinates, resultsX + 6, rowY + 5,
                selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
            double distance = Minecraft.getInstance().player.blockPosition().distSqr(pos);
            String distanceText = String.format("%.0fm", Math.sqrt(distance));
            graphics.drawString(font, distanceText, resultsX + RESULTS_WIDTH - 8 - font.width(distanceText),
                rowY + 5, 0xFF8FB6D5, false);
        }
        graphics.disableScissor();

        if (nearbyResults.isEmpty()) {
            graphics.drawString(font, "No matches in scanned loaded chunks.", resultsX + 6,
                listY + 8, 0xFF888888, false);
        }
        graphics.drawString(font,
            "Selected: " + BlockSearchModule.searchBlocks.size() + " block IDs · click a result to copy",
            panelX + 12, panelY + PANEL_HEIGHT - 35, 0xFFFFFFFF, false);
        graphics.drawString(font,
            (lastCopiedCoordinates.isEmpty() ? "N: next nearby  ·  C: copy  ·  Esc: back"
                : "Copied " + lastCopiedCoordinates + "   ·   N: next  ·  C: copy  ·  Esc: back"),
            panelX + 12, panelY + PANEL_HEIGHT - 19, 0xFF888888, false);
    }

    private void drawFilterButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = panelY + 52;
        String[] labels = {"All", "Selected", "Found"};
        FilterMode[] modes = FilterMode.values();
        for (int i = 0; i < modes.length; i++) {
            int x = panelX + 274 + i * 76;
            boolean active = filterMode == modes[i];
            boolean hovered = inBounds(mouseX, mouseY, x, y, 70, 19);
            graphics.fill(x, y, x + 70, y + 19,
                active ? 0xFF246CB5 : (hovered ? 0xFF303030 : 0xFF242424));
            graphics.renderOutline(x, y, 70, 19, 0xFF3A3A3A);
            int labelWidth = font.width(labels[i]);
            graphics.drawString(font, labels[i], x + (70 - labelWidth) / 2, y + 6, 0xFFFFFFFF, false);
        }
    }

    private void drawPresetControls(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = panelY + 77;
        drawButton(graphics, panelX + 170, y, 48, 20, "Save", mouseX, mouseY);
        drawButton(graphics, panelX + 222, y, 48, 20, "Load", mouseX, mouseY);
        drawButton(graphics, panelX + 274, y, 54, 20, "Delete", mouseX, mouseY);
        drawButton(graphics, panelX + 336, y, 22, 20, "<", mouseX, mouseY);
        drawButton(graphics, panelX + 460, y, 22, 20, ">", mouseX, mouseY);
        String preset = presetIndex >= 0 && presetIndex < presetNames.size()
            ? presetNames.get(presetIndex) : "No presets";
        graphics.drawString(font, trimToWidth(preset, 96), panelX + 362, y + 6, 0xFFB8B8B8, false);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int buttonWidth, int buttonHeight,
                            String label, int mouseX, int mouseY) {
        boolean hovered = inBounds(mouseX, mouseY, x, y, buttonWidth, buttonHeight);
        graphics.fill(x, y, x + buttonWidth, y + buttonHeight, hovered ? 0xFF333333 : 0xFF242424);
        graphics.renderOutline(x, y, buttonWidth, buttonHeight, 0xFF3A3A3A);
        graphics.drawString(font, label, x + (buttonWidth - font.width(label)) / 2, y + 6, 0xFFDDDDDD, false);
    }

    private void drawCycleButtons(GuiGraphics graphics, int y, int resultsX) {
        drawButton(graphics, resultsX + RESULTS_WIDTH - 42, y - 1, 18, 15, "<",
            Integer.MIN_VALUE, Integer.MIN_VALUE);
        drawButton(graphics, resultsX + RESULTS_WIDTH - 21, y - 1, 18, 15, ">",
            Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    private boolean inBounds(double mouseX, double mouseY, int x, int y, int boxWidth, int boxHeight) {
        return mouseX >= x && mouseX <= x + boxWidth && mouseY >= y && mouseY < y + boxHeight;
    }

    private String trimToWidth(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String text = value;
        while (!text.isEmpty() && font.width(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int button = event.button();

        if (mouseY >= panelY + 52 && mouseY < panelY + 71) {
            for (int i = 0; i < FilterMode.values().length; i++) {
                int x = panelX + 274 + i * 76;
                if (inBounds(mouseX, mouseY, x, panelY + 52, 70, 19)) {
                    filterMode = FilterMode.values()[i];
                    scrollOffset = 0;
                    updateFilter(searchField.getValue());
                    return true;
                }
            }
        }

        if (mouseY >= panelY + 77 && mouseY < panelY + 97) {
            if (inBounds(mouseX, mouseY, panelX + 170, panelY + 77, 48, 20)) {
                String name = presetNameField.getValue().trim();
                if (name.isEmpty()) {
                    name = "Preset " + (Settings.getPresetNames().size() + 1);
                }
                Settings.saveSearchPreset(name);
                Settings.save();
                refreshPresetNames(name);
                return true;
            }
            if (inBounds(mouseX, mouseY, panelX + 222, panelY + 77, 48, 20)) {
                if (presetIndex >= 0 && presetIndex < presetNames.size()) {
                    Settings.applySearchPreset(presetNames.get(presetIndex));
                    Settings.save();
                    updateFilter(searchField.getValue());
                }
                return true;
            }
            if (inBounds(mouseX, mouseY, panelX + 274, panelY + 77, 54, 20)) {
                if (presetIndex >= 0 && presetIndex < presetNames.size()) {
                    Settings.deleteSearchPreset(presetNames.get(presetIndex));
                    Settings.save();
                    refreshPresetNames("");
                }
                return true;
            }
            if (inBounds(mouseX, mouseY, panelX + 336, panelY + 77, 22, 20)) {
                cyclePreset(-1);
                return true;
            }
            if (inBounds(mouseX, mouseY, panelX + 460, panelY + 77, 22, 20)) {
                cyclePreset(1);
                return true;
            }
        }

        int listY = panelY + LIST_Y_OFFSET;
        int resultsX = panelX + RESULTS_X_OFFSET;
        if (mouseY >= listY - 17 && mouseY < listY) {
            if (inBounds(mouseX, mouseY, resultsX + RESULTS_WIDTH - 42, listY - 17, 18, 16)) {
                cycleCoordinate(-1);
                return true;
            }
            if (inBounds(mouseX, mouseY, resultsX + RESULTS_WIDTH - 21, listY - 17, 18, 16)) {
                cycleCoordinate(1);
                return true;
            }
        }

        if (mouseY >= listY && mouseY < listY + MAX_VISIBLE * ROW_HEIGHT) {
            if (inBounds(mouseX, mouseY, panelX + CATALOG_X_OFFSET, listY, CATALOG_WIDTH,
                MAX_VISIBLE * ROW_HEIGHT)) {
                int index = (mouseY - listY) / ROW_HEIGHT + scrollOffset;
                if (index >= 0 && index < filteredBlocks.size()) {
                    Block block = filteredBlocks.get(index);
                    if (button == 1) {
                        BlockSearchModule.removeBlock(block);
                    } else if (BlockSearchModule.searchBlocks.contains(block)) {
                        BlockSearchModule.removeBlock(block);
                    } else {
                        BlockSearchModule.addBlock(block);
                    }
                    Settings.save();
                    updateFilter(searchField.getValue());
                    return true;
                }
            }
            if (inBounds(mouseX, mouseY, resultsX, listY, RESULTS_WIDTH, MAX_VISIBLE * ROW_HEIGHT)) {
                int index = (mouseY - listY) / ROW_HEIGHT + resultScrollOffset;
                if (index >= 0 && index < nearbyResults.size()) {
                    coordinateIndex = index;
                    copyCoordinate(nearbyResults.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void refreshPresetNames(String selectedName) {
        presetNames = Settings.getPresetNames();
        presetIndex = presetNames.indexOf(selectedName);
        if (presetIndex < 0 && !presetNames.isEmpty()) {
            presetIndex = 0;
        }
    }

    private void cyclePreset(int direction) {
        presetNames = Settings.getPresetNames();
        if (presetNames.isEmpty()) {
            presetIndex = -1;
            return;
        }
        presetIndex = Math.floorMod(presetIndex + direction, presetNames.size());
    }

    private void cycleCoordinate(int direction) {
        if (nearbyResults.isEmpty()) {
            return;
        }
        coordinateIndex = Math.floorMod(coordinateIndex + direction, nearbyResults.size());
        if (coordinateIndex < resultScrollOffset) {
            resultScrollOffset = coordinateIndex;
        } else if (coordinateIndex >= resultScrollOffset + MAX_VISIBLE) {
            resultScrollOffset = coordinateIndex - MAX_VISIBLE + 1;
        }
    }

    private void copyCoordinate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        lastCopiedCoordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        Minecraft.getInstance().keyboardHandler.setClipboard(lastCopiedCoordinates);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = (int) Math.signum(scrollY);
        int maxScroll = Math.max(0, filteredBlocks.size() - MAX_VISIBLE);
        int maxResultScroll = Math.max(0, nearbyResults.size() - MAX_VISIBLE);
        if (mouseX >= panelX + RESULTS_X_OFFSET && mouseY >= panelY + LIST_Y_OFFSET) {
            resultScrollOffset = Math.max(0, Math.min(maxResultScroll, resultScrollOffset - direction));
        } else {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - direction));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            Settings.save();
            Minecraft.getInstance().setScreen(new ClickGuiScreen());
            return true;
        }
        if (searchField != null && searchField.isFocused() && searchField.keyPressed(event)) {
            return true;
        }
        if (presetNameField != null && presetNameField.isFocused() && presetNameField.keyPressed(event)) {
            return true;
        }
        if (event.key() == 78) {
            cycleCoordinate(1);
            return true;
        }
        if (event.key() == 67 && !nearbyResults.isEmpty()) {
            copyCoordinate(nearbyResults.get(coordinateIndex));
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

    private enum FilterMode {
        ALL,
        SELECTED,
        FOUND
    }
}
