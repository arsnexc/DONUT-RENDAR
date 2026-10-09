package me.kanha.ru.scan;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.config.Settings;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, paced scanner for an integrated local world. It never runs against
 * a remote multiplayer level.
 */
public final class DataAggregator {
    public static final Set<BlockPos> trackedContainers = ConcurrentHashMap.newKeySet();
    public static final Set<BlockPos> trackedSpawners = ConcurrentHashMap.newKeySet();
    public static final Map<BlockPos, Float> heatMap = new ConcurrentHashMap<>();

    // Scan the closest chunks first, then continue through the configured window.
    // A small per-tick budget keeps the full-height chunk walk from monopolizing a tick.
    private static final int CHUNKS_PER_TICK = 2;
    private static final int COVERAGE_REFRESH_INTERVAL_TICKS = 20;

    private static final Set<Block> CONTAINERS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.FURNACE,
        Blocks.BLAST_FURNACE, Blocks.SMOKER
    );
    private static final Set<Block> ACTIVITY_SIGNALS = Set.of(
        Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS,
        Blocks.NETHER_BRICKS, Blocks.BRICKS, Blocks.GLASS, Blocks.WHITE_CONCRETE,
        Blocks.CRAFTING_TABLE, Blocks.BOOKSHELF, Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.BREWING_STAND, Blocks.LECTERN, Blocks.LOOM,
        Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.GRINDSTONE,
        Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.COMPOSTER,
        Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.BELL,
        Blocks.REDSTONE_LAMP, Blocks.REPEATER, Blocks.COMPARATOR,
        Blocks.REDSTONE_TORCH, Blocks.DAYLIGHT_DETECTOR, Blocks.OBSERVER,
        Blocks.NOTE_BLOCK, Blocks.JUKEBOX
    );

    private static Object activeLevel;
    private static List<ChunkOffset> scanOrder = List.of();
    private static boolean[] visitedChunks = new boolean[0];
    private static int scanCenterX = Integer.MIN_VALUE;
    private static int scanCenterZ = Integer.MIN_VALUE;
    private static int scanRadius = -1;
    private static int scanCursor;
    private static int scanModuleMask = -1;
    private static int visitedChunkCount;
    private static int loadedChunkCount;
    private static int coverageRefreshTicks;
    private static boolean scanningWasActive;
    private static BlockPos lastSearchPruneOrigin;
    private static double lastSearchPruneRange = Double.NaN;
    private static int lastSearchSelectionRevision = Integer.MIN_VALUE;

    private DataAggregator() {
    }

    public static void tick(Minecraft client) {
        if (!RenderUtilClient.hasLocalWorld(client)) {
            clear();
            return;
        }

        // ClientLevel identity changes on a dimension/world switch. Do not let
        // results from the old local level leak into the new one at matching XYZs.
        if (activeLevel != client.level) {
            clearScanData();
            activeLevel = client.level;
        }

        clearDisabledModuleData();
        pruneSearchResults(client);

        int moduleMask = getEnabledModuleMask();
        if (moduleMask == 0) {
            resetScanPlan();
            scanningWasActive = false;
            return;
        }

        int radius = Math.max(1, Math.min(6, Settings.scanRadius));
        int centerX = client.player.chunkPosition().x;
        int centerZ = client.player.chunkPosition().z;
        int probeY = getProbeY(client);

        if (!scanningWasActive || centerX != scanCenterX || centerZ != scanCenterZ
            || radius != scanRadius || moduleMask != scanModuleMask) {
            rebuildScanPlan(client, centerX, centerZ, radius, probeY, moduleMask);
        } else if (--coverageRefreshTicks <= 0) {
            loadedChunkCount = countLoadedChunks(client, probeY);
            coverageRefreshTicks = COVERAGE_REFRESH_INTERVAL_TICKS;
        }
        scanningWasActive = true;

        int candidatesChecked = 0;
        while (candidatesChecked < CHUNKS_PER_TICK && !scanOrder.isEmpty()) {
            int index = scanCursor;
            ChunkOffset offset = scanOrder.get(index);
            scanCursor = (scanCursor + 1) % scanOrder.size();
            candidatesChecked++;

            int chunkX = centerX + offset.x();
            int chunkZ = centerZ + offset.z();
            if (!isChunkLoaded(client, chunkX, chunkZ, probeY)) {
                // Do not retain markers for a chunk that has left the client cache.
                removeChunkResults(chunkX, chunkZ);
                continue;
            }

            LevelChunk chunk = client.level.getChunk(chunkX, chunkZ);
            scanChunk(chunk, client);
            if (!visitedChunks[index]) {
                visitedChunks[index] = true;
                visitedChunkCount++;
            }
        }
    }

    private static void rebuildScanPlan(Minecraft client, int centerX, int centerZ, int radius,
                                        int probeY, int moduleMask) {
        scanCenterX = centerX;
        scanCenterZ = centerZ;
        scanRadius = radius;
        scanModuleMask = moduleMask;
        scanCursor = 0;
        visitedChunkCount = 0;

        ArrayList<ChunkOffset> offsets = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                offsets.add(new ChunkOffset(offsetX, offsetZ));
            }
        }
        offsets.sort(Comparator
            .comparingInt((ChunkOffset offset) -> offset.x() * offset.x() + offset.z() * offset.z())
            .thenComparingInt(offset -> Math.abs(offset.x()) + Math.abs(offset.z()))
            .thenComparingInt(ChunkOffset::z)
            .thenComparingInt(ChunkOffset::x));
        scanOrder = List.copyOf(offsets);
        visitedChunks = new boolean[scanOrder.size()];
        loadedChunkCount = countLoadedChunks(client, probeY);
        coverageRefreshTicks = COVERAGE_REFRESH_INTERVAL_TICKS;

        // Keep retained results scoped to the current scan window. In particular,
        // shrinking the configured radius must not leave old ESP markers behind.
        pruneResultsOutsideScanWindow(centerX, centerZ, radius);
    }

    private static int countLoadedChunks(Minecraft client, int probeY) {
        int loaded = 0;
        for (ChunkOffset offset : scanOrder) {
            if (isChunkLoaded(client, scanCenterX + offset.x(), scanCenterZ + offset.z(), probeY)) {
                loaded++;
            }
        }
        return loaded;
    }

    private static boolean isChunkLoaded(Minecraft client, int chunkX, int chunkZ, int probeY) {
        BlockPos probe = new BlockPos(chunkX * 16 + 8, probeY, chunkZ * 16 + 8);
        // Check the client cache only; never force-load chunks for scanning.
        return client.level.hasChunkAt(probe);
    }

    private static int getProbeY(Minecraft client) {
        // In 1.21.11 getMaxY() is inclusive, so it is used without a -1.
        return Math.max(client.level.getMinY(),
            Math.min(client.level.getMaxY(), client.player.blockPosition().getY()));
    }

    private static void scanChunk(LevelChunk chunk, Minecraft client) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        removeChunkResults(chunkX, chunkZ);

        boolean scanContainers = isEnabled("Container ESP");
        boolean scanSpawners = isEnabled("Spawner ESP");
        Module searchBase = ModuleManager.getByName("Block Search");
        BlockSearchModule searchModule = searchBase instanceof BlockSearchModule search ? search : null;
        boolean scanSearch = searchModule != null && searchModule.isEnabled()
            && !BlockSearchModule.searchBlocks.isEmpty();
        double searchRangeSquared = searchModule == null ? 0.0 : searchModule.getRange() * searchModule.getRange();
        boolean scanActivity = isEnabled("Activity Scan");
        if (!scanContainers && !scanSpawners && !scanSearch && !scanActivity) {
            return;
        }

        BlockPos playerPos = client.player.blockPosition();
        int hottestSectionCount = 0;
        BlockPos hottestSectionCenter = null;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int sectionBaseY = chunk.getMinSectionY() * 16 + sectionIndex * 16;
            int activityCount = 0;
            long activityX = 0;
            long activityY = 0;
            long activityZ = 0;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }

                        Block block = state.getBlock();
                        BlockPos pos = new BlockPos(baseX + x, sectionBaseY + y, baseZ + z);

                        if (scanContainers && (CONTAINERS.contains(block)
                            || block instanceof ShulkerBoxBlock || state.is(BlockTags.COPPER_CHESTS))) {
                            trackedContainers.add(pos);
                        }
                        if (scanSpawners && block == Blocks.SPAWNER) {
                            trackedSpawners.add(pos);
                        }
                        if (scanActivity && isActivityIndicator(state, block)) {
                            activityCount++;
                            activityX += pos.getX();
                            activityY += pos.getY();
                            activityZ += pos.getZ();
                        }
                        if (scanSearch && BlockSearchModule.shouldTrack(block)
                            && playerPos.distSqr(pos) <= searchRangeSquared) {
                            BlockSearchModule.foundBlocks.add(pos);
                        }
                    }
                }
            }

            // Keep each hotspot at the busiest observed vertical section and at
            // the average coordinates of its signal blocks, not at player Y.
            if (scanActivity && activityCount > hottestSectionCount) {
                hottestSectionCount = activityCount;
                hottestSectionCenter = new BlockPos(
                    averageCoordinate(activityX, activityCount),
                    averageCoordinate(activityY, activityCount),
                    averageCoordinate(activityZ, activityCount)
                );
            }
        }

        if (scanActivity && hottestSectionCenter != null) {
            heatMap.put(hottestSectionCenter, (float) hottestSectionCount);
        }
    }

    private static boolean isActivityIndicator(BlockState state, Block block) {
        return ACTIVITY_SIGNALS.contains(block)
            || CONTAINERS.contains(block)
            || block instanceof ShulkerBoxBlock
            || state.is(BlockTags.COPPER_CHESTS)
            || state.is(BlockTags.PLANKS)
            || state.is(BlockTags.STONE_BRICKS)
            || state.is(BlockTags.STAIRS)
            || state.is(BlockTags.SLABS)
            || state.is(BlockTags.WALLS)
            || state.is(BlockTags.FENCES)
            || state.is(BlockTags.DOORS)
            || state.is(BlockTags.TRAPDOORS)
            || state.is(BlockTags.BEDS)
            || state.is(BlockTags.WOOL)
            || state.is(BlockTags.WOOL_CARPETS)
            || state.is(BlockTags.RAILS)
            || state.is(BlockTags.ALL_SIGNS)
            || state.is(BlockTags.BANNERS)
            || state.is(BlockTags.LANTERNS)
            || state.is(BlockTags.CANDLES)
            || state.is(BlockTags.SHULKER_BOXES);
    }

    private static int averageCoordinate(long sum, int count) {
        return (int) Math.round((double) sum / count);
    }

    private static void pruneSearchResults(Minecraft client) {
        Module searchBase = ModuleManager.getByName("Block Search");
        if (!(searchBase instanceof BlockSearchModule searchModule)
            || !searchModule.isEnabled() || BlockSearchModule.searchBlocks.isEmpty()) {
            BlockSearchModule.clearFound();
            lastSearchPruneOrigin = null;
            lastSearchPruneRange = Double.NaN;
            lastSearchSelectionRevision = Integer.MIN_VALUE;
            return;
        }

        BlockPos playerPos = client.player.blockPosition();
        double range = searchModule.getRange();
        int selectionRevision = BlockSearchModule.getSelectionRevision();
        boolean rangeOrPositionChanged = !playerPos.equals(lastSearchPruneOrigin)
            || Double.compare(range, lastSearchPruneRange) != 0;
        boolean selectionChanged = selectionRevision != lastSearchSelectionRevision;
        if (!rangeOrPositionChanged && !selectionChanged) {
            return;
        }

        double rangeSquared = range * range;
        BlockSearchModule.foundBlocks.removeIf(pos -> {
            if (playerPos.distSqr(pos) > rangeSquared) {
                return true;
            }
            // Validate block state only when the search selection changes. Normal
            // world edits are handled by the fast chunk rescan; this avoids walking
            // every result and querying block state on every render tick.
            return selectionChanged && (!client.level.hasChunkAt(pos)
                || !BlockSearchModule.shouldTrack(client.level.getBlockState(pos).getBlock()));
        });

        lastSearchPruneOrigin = playerPos;
        lastSearchPruneRange = range;
        lastSearchSelectionRevision = selectionRevision;
    }

    private static void removeChunkResults(int chunkX, int chunkZ) {
        trackedContainers.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        trackedSpawners.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        BlockSearchModule.foundBlocks.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        heatMap.keySet().removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
    }

    private static void pruneResultsOutsideScanWindow(int centerX, int centerZ, int radius) {
        trackedContainers.removeIf(pos -> !withinChunkRadius(pos, centerX, centerZ, radius));
        trackedSpawners.removeIf(pos -> !withinChunkRadius(pos, centerX, centerZ, radius));
        BlockSearchModule.foundBlocks.removeIf(pos -> !withinChunkRadius(pos, centerX, centerZ, radius));
        heatMap.keySet().removeIf(pos -> !withinChunkRadius(pos, centerX, centerZ, radius));
    }

    private static boolean withinChunkRadius(BlockPos pos, int centerX, int centerZ, int radius) {
        return Math.abs((pos.getX() >> 4) - centerX) <= radius
            && Math.abs((pos.getZ() >> 4) - centerZ) <= radius;
    }

    private static void clearDisabledModuleData() {
        if (!isEnabled("Container ESP")) {
            trackedContainers.clear();
        }
        if (!isEnabled("Spawner ESP")) {
            trackedSpawners.clear();
        }
        if (!isEnabled("Block Search")) {
            BlockSearchModule.clearFound();
        }
        if (!isEnabled("Activity Scan")) {
            heatMap.clear();
        }
    }

    private static int getEnabledModuleMask() {
        int mask = 0;
        if (isEnabled("Container ESP")) {
            mask |= 1;
        }
        if (isEnabled("Spawner ESP")) {
            mask |= 1 << 1;
        }
        if (isEnabled("Block Search")) {
            mask |= 1 << 2;
        }
        if (isEnabled("Activity Scan")) {
            mask |= 1 << 3;
        }
        return mask;
    }

    private static boolean isEnabled(String name) {
        Module module = ModuleManager.getByName(name);
        return module != null && module.isEnabled();
    }

    private static void resetScanPlan() {
        scanOrder = List.of();
        visitedChunks = new boolean[0];
        scanCenterX = Integer.MIN_VALUE;
        scanCenterZ = Integer.MIN_VALUE;
        scanRadius = -1;
        scanCursor = 0;
        scanModuleMask = -1;
        visitedChunkCount = 0;
        loadedChunkCount = 0;
        coverageRefreshTicks = 0;
    }

    private static void clearScanData() {
        trackedContainers.clear();
        trackedSpawners.clear();
        heatMap.clear();
        BlockSearchModule.clearFound();
        resetScanPlan();
        scanningWasActive = false;
        lastSearchPruneOrigin = null;
        lastSearchPruneRange = Double.NaN;
        lastSearchSelectionRevision = Integer.MIN_VALUE;
    }

    public static int getLoadedChunkCount() {
        return loadedChunkCount;
    }

    public static int getTotalChunkCount() {
        return scanOrder.size();
    }

    /** Number of chunks visited at least once for the current center/radius. */
    public static int getVisitedChunkCount() {
        return visitedChunkCount;
    }

    public static void clear() {
        clearScanData();
        activeLevel = null;
    }

    private record ChunkOffset(int x, int z) {
    }
}
