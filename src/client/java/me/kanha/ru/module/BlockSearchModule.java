package me.kanha.ru.module;

import me.kanha.ru.scan.DataAggregator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockSearchModule extends Module {
    public static final Set<Block> searchBlocks = ConcurrentHashMap.newKeySet();
    /** Read-only view of the chunk-indexed result set maintained by DataAggregator. */
    public static final Set<BlockPos> foundBlocks = DataAggregator.getSearchResults();
    private static int selectionRevision;

    private final BooleanSetting highlight = new BooleanSetting("Highlight", "highlight", true);
    private final NumberSetting range = new NumberSetting("Range", "range", 64, 16, 256, 16);

    public BlockSearchModule() {
        super("block_search", "Block Search", Category.SEARCH);
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
        if (searchBlocks.add(block)) {
            selectionRevision++;
        }
    }

    public static void removeBlock(Block block) {
        if (searchBlocks.remove(block)) {
            selectionRevision++;
        }
    }

    public static void replaceSelection(Collection<Block> blocks) {
        Set<Block> replacement = ConcurrentHashMap.newKeySet();
        replacement.addAll(blocks);
        if (!searchBlocks.equals(replacement)) {
            searchBlocks.clear();
            searchBlocks.addAll(replacement);
            selectionRevision++;
        }
    }

    public static int getSelectionRevision() {
        return selectionRevision;
    }

    public static void clearFound() {
        DataAggregator.clearSearchResults();
    }
}
