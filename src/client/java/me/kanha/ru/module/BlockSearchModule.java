package me.kanha.ru.module;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockSearchModule extends Module {
    public static final Set<Block> searchBlocks = ConcurrentHashMap.newKeySet();
    public static final Set<BlockPos> foundBlocks = ConcurrentHashMap.newKeySet();

    private final BooleanSetting highlight = new BooleanSetting("Highlight", true);
    private final NumberSetting range = new NumberSetting("Range", 64, 16, 256, 16);

    public BlockSearchModule() {
        super("Block Search", Category.SEARCH);
        addSetting(highlight);
        addSetting(range);
    }

    public boolean shouldHighlight() {
        return highlight.get();
    }

    public double getRange() {
        return range.get();
    }

    @Override
    public void onTick() {
        // Chunk scanning is handled by DataAggregator.
    }

    public static boolean shouldTrack(Block block) {
        return searchBlocks.contains(block);
    }

    public static void addBlock(Block block) {
        searchBlocks.add(block);
    }

    public static void removeBlock(Block block) {
        searchBlocks.remove(block);
    }

    public static void clearFound() {
        foundBlocks.clear();
    }
}
