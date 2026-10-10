package me.kanha.ru.scan;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.module.ActivityScanModule;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, paced scanner for an integrated local world. Every feature has an
 * independent scan window and coverage counter; only already-loaded chunks are read.
 */
public final class DataAggregator {
    private static final ChunkResultStore<BlockPos> CONTAINER_RESULTS = new ChunkResultStore<>();
    private static final ChunkResultStore<BlockPos> SPAWNER_RESULTS = new ChunkResultStore<>();
    private static final ChunkResultStore<BlockPos> SEARCH_RESULTS = new ChunkResultStore<>();

    public static final Set<BlockPos> trackedContainers = CONTAINER_RESULTS.values();
    public static final Set<BlockPos> trackedSpawners = SPAWNER_RESULTS.values();
    public static final Map<BlockPos, Float> heatMap = new ConcurrentHashMap<>();

    private static final int SCAN_JOBS_PER_TICK = 2;
    private static final int COVERAGE_REFRESH_INTERVAL_TICKS = 20;

    private static final Set<Block> CONTAINERS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.FURNACE,
        Blocks.BLAST_FURNACE, Blocks.SMOKER
    );
    private static final Set<Block> WORKSTATIONS = Set.of(
        Blocks.CRAFTING_TABLE, Blocks.BOOKSHELF, Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.BREWING_STAND, Blocks.LECTERN, Blocks.LOOM,
        Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.GRINDSTONE,
        Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.COMPOSTER
    );
    private static final Set<Block> REDSTONE_SIGNALS = Set.of(
        Blocks.REDSTONE_LAMP, Blocks.REPEATER, Blocks.COMPARATOR,
        Blocks.REDSTONE_TORCH, Blocks.DAYLIGHT_DETECTOR, Blocks.OBSERVER,
        Blocks.NOTE_BLOCK, Blocks.JUKEBOX
    );
    private static final Set<Block> BUILDING_SIGNALS = Set.of(
        Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS,
        Blocks.NETHER_BRICKS, Blocks.BRICKS, Blocks.GLASS, Blocks.WHITE_CONCRETE
    );

    private static final Map<String, ScanState> SCAN_STATES = new LinkedHashMap<>();
    private static final Map<ScanWindow.Chunk, Map<ActivityCell, SignalCount>> ACTIVITY_BY_CHUNK = new HashMap<>();
    private static final Map<ActivityCell, SignalCount> ACTIVITY_TOTALS = new HashMap<>();

    private static Object activeLevel;
    private static int moduleCursor;
    private static BlockPos lastSearchPruneOrigin;
    private static double lastSearchPruneRange = Double.NaN;
    private static int lastSearchSelectionRevision = Integer.MIN_VALUE;
    private static int activityCellSize = 16;
    private static volatile long resultRevision;

    private DataAggregator() {
        throw new AssertionError("No instances");
    }

    public static Set<BlockPos> getSearchResults() {
        return SEARCH_RESULTS.values();
    }

    public static void clearSearchResults() {
        if (!SEARCH_RESULTS.values().isEmpty()) {
            SEARCH_RESULTS.clear();
            resultRevision++;
        }
    }

    public static void tick(Minecraft client) {
        if (!RenderUtilClient.hasLocalWorld(client)) {
            clear();
            return;
        }

        // ClientLevel identity changes on a dimension/world switch. Never let results
        // from the old local level leak into the new one at matching XYZ coordinates.
        if (activeLevel != client.level) {
            clearScanData();
            CONTAINER_RESULTS.resetForWorld(client.level);
            SPAWNER_RESULTS.resetForWorld(client.level);
            SEARCH_RESULTS.resetForWorld(client.level);
            activeLevel = client.level;
        }

        BlockPos playerPos = client.player.blockPosition();
        int centerX = client.player.chunkPosition().x;
        int centerZ = client.player.chunkPosition().z;
        int probeY = getProbeY(client);
        prepareScanStates(client, centerX, centerZ, probeY);
        pruneSearchResults(client, playerPos);
        processScanJobs(client, probeY);
    }

    private static void prepareScanStates(Minecraft client, int centerX, int centerZ, int probeY) {
        for (Module module : ModuleManager.getAll()) {
            ScanState state = SCAN_STATES.computeIfAbsent(module.getId(), ignored -> new ScanState());
            boolean shouldScan = module.isEnabled() && module.isScanEnabled();
            if (module instanceof BlockSearchModule && BlockSearchModule.searchBlocks.isEmpty()) {
                shouldScan = false;
            }

            if (!shouldScan) {
                if (state.active || hasModuleResults(module.getId())) {
                    clearModuleResults(module.getId());
                }
                state.active = false;
                state.window = null;
                state.refreshTicks = 0;
                state.signature = Long.MIN_VALUE;
                continue;
            }

            long signature = module instanceof ActivityScanModule activity
                ? activity.getAggregationSignature() : 0L;
            boolean changedWindow = state.window == null
                || state.window.centerX() != centerX
                || state.window.centerZ() != centerZ
                || state.window.radius() != module.getScanRadius();
            boolean changedActivitySettings = module instanceof ActivityScanModule
                && state.active && state.signature != signature;

            if (!state.active || changedWindow || changedActivitySettings) {
                if (changedActivitySettings) {
                    clearModuleResults(module.getId());
                }
                state.window = new ScanWindow(centerX, centerZ, module.getScanRadius());
                state.active = true;
                state.signature = signature;
                state.refreshTicks = COVERAGE_REFRESH_INTERVAL_TICKS;
                refreshLoadedCount(client, state, probeY);
                pruneResultsOutsideScanWindow(module.getId(), state.window);
            } else if (--state.refreshTicks <= 0) {
                refreshLoadedCount(client, state, probeY);
                state.refreshTicks = COVERAGE_REFRESH_INTERVAL_TICKS;
            }
        }
    }

    private static void refreshLoadedCount(Minecraft client, ScanState state, int probeY) {
        int loaded = 0;
        ScanWindow window = state.window;
        if (window == null) {
            return;
        }
        for (ScanWindow.Chunk offset : window.orderedOffsets()) {
            if (isChunkLoaded(client, window.centerX() + offset.x(), window.centerZ() + offset.z(), probeY)) {
                loaded++;
            }
        }
        window.setLoadedCount(loaded);
    }

    private static void processScanJobs(Minecraft client, int probeY) {
        List<Module> modules = ModuleManager.getAll();
        if (modules.isEmpty()) {
            return;
        }

        int jobs = 0;
        int attempts = 0;
        int maxAttempts = modules.size() * SCAN_JOBS_PER_TICK;
        while (jobs < SCAN_JOBS_PER_TICK && attempts < maxAttempts) {
            Module module = modules.get(Math.floorMod(moduleCursor, modules.size()));
            moduleCursor = (moduleCursor + 1) % modules.size();
            attempts++;

            ScanState state = SCAN_STATES.get(module.getId());
            if (state == null || !state.active || state.window == null) {
                continue;
            }

            ScanWindow.Chunk candidate = state.window.nextCandidate();
            jobs++;
            if (!isChunkLoaded(client, candidate.x(), candidate.z(), probeY)) {
                removeChunkResults(module.getId(), candidate.x(), candidate.z());
                continue;
            }

            LevelChunk chunk = client.level.getChunk(candidate.x(), candidate.z());
            scanChunk(module, chunk, client);
            state.window.markVisited(candidate.x(), candidate.z());
        }
    }

    private static boolean isChunkLoaded(Minecraft client, int chunkX, int chunkZ, int probeY) {
        BlockPos probe = new BlockPos(chunkX * 16 + 8, probeY, chunkZ * 16 + 8);
        // ClientLevel cache check only; never request or force-load a chunk.
        return client.level.hasChunkAt(probe);
    }

    private static int getProbeY(Minecraft client) {
        return Math.max(client.level.getMinY(),
            Math.min(client.level.getMaxY(), client.player.blockPosition().getY()));
    }

    private static void scanChunk(Module module, LevelChunk chunk, Minecraft client) {
        String moduleId = module.getId();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        switch (moduleId) {
            case "container_esp" -> scanContainers(chunk);
            case "spawner_esp" -> scanSpawners(chunk);
            case "block_search" -> scanSearch(chunk, client);
            case "activity_scan" -> scanActivity(chunk, module instanceof ActivityScanModule activity
                ? activity : null);
            default -> removeChunkResults(moduleId, chunkX, chunkZ);
        }
        resultRevision++;
    }

    private static void scanContainers(LevelChunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        ArrayList<BlockPos> found = new ArrayList<>();
        walkChunk(chunk, (state, block, pos) -> {
            if (CONTAINERS.contains(block) || block instanceof ShulkerBoxBlock
                || state.is(BlockTags.COPPER_CHESTS)) {
                found.add(pos);
            }
        });
        CONTAINER_RESULTS.replaceChunk(chunkX, chunkZ, found);
    }

    private static void scanSpawners(LevelChunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        ArrayList<BlockPos> found = new ArrayList<>();
        walkChunk(chunk, (state, block, pos) -> {
            if (block == Blocks.SPAWNER) {
                found.add(pos);
            }
        });
        SPAWNER_RESULTS.replaceChunk(chunkX, chunkZ, found);
    }

    private static void scanSearch(LevelChunk chunk, Minecraft client) {
        BlockSearchModule searchModule = ModuleManager.getByName("Block Search") instanceof BlockSearchModule search
            ? search : null;
        if (searchModule == null || !searchModule.isEnabled() || !searchModule.isScanEnabled()) {
            SEARCH_RESULTS.removeChunk(chunk.getPos().x, chunk.getPos().z);
            return;
        }

        BlockPos playerPos = client.player.blockPosition();
        double rangeSquared = searchModule.getRange() * searchModule.getRange();
        ArrayList<BlockPos> found = new ArrayList<>();
        walkChunk(chunk, (state, block, pos) -> {
            if (BlockSearchModule.shouldTrack(block) && playerPos.distSqr(pos) <= rangeSquared) {
                found.add(pos);
            }
        });
        SEARCH_RESULTS.replaceChunk(chunk.getPos().x, chunk.getPos().z, found);
    }

    private static void scanActivity(LevelChunk chunk, ActivityScanModule activity) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        ScanWindow.Chunk chunkKey = new ScanWindow.Chunk(chunkX, chunkZ);
        removeActivityChunk(chunkKey);
        if (activity == null) {
            return;
        }

        activityCellSize = activity.getCellSize();
        Map<ActivityCell, SignalCount> localCells = new HashMap<>();
        walkChunk(chunk, (state, block, pos) -> {
            ActivityScanModule.Signal signal = classifyActivitySignal(state, block);
            if (signal == null) {
                return;
            }
            float weight = activity.getWeight(signal);
            if (weight <= 0.0f) {
                return;
            }

            ActivityCell cell = new ActivityCell(
                Math.floorDiv(pos.getX(), activityCellSize),
                Math.floorDiv(pos.getY(), 16),
                Math.floorDiv(pos.getZ(), activityCellSize)
            );
            localCells.computeIfAbsent(cell, ignored -> new SignalCount()).add(weight);
        });

        if (!localCells.isEmpty()) {
            ACTIVITY_BY_CHUNK.put(chunkKey, localCells);
            for (Map.Entry<ActivityCell, SignalCount> entry : localCells.entrySet()) {
                SignalCount total = ACTIVITY_TOTALS.computeIfAbsent(entry.getKey(), ignored -> new SignalCount());
                total.add(entry.getValue());
                publishActivityCell(entry.getKey(), total, activityCellSize, activity.getNoiseFloor());
            }
        }
    }

    private static void walkChunk(LevelChunk chunk, BlockVisitor visitor) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int sectionBaseY = chunk.getMinSectionY() * 16 + sectionIndex * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            Block block = state.getBlock();
                            visitor.visit(state, block, new BlockPos(baseX + x, sectionBaseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
    }

    private static ActivityScanModule.Signal classifyActivitySignal(BlockState state, Block block) {
        if (CONTAINERS.contains(block) || block instanceof ShulkerBoxBlock || state.is(BlockTags.COPPER_CHESTS)) {
            return ActivityScanModule.Signal.STORAGE;
        }
        if (REDSTONE_SIGNALS.contains(block)) {
            return ActivityScanModule.Signal.REDSTONE;
        }
        if (WORKSTATIONS.contains(block)) {
            return ActivityScanModule.Signal.WORKSTATION;
        }
        if (block == Blocks.BELL || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
            || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)
            || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.BANNERS)
            || state.is(BlockTags.LANTERNS) || state.is(BlockTags.CANDLES)
            || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) {
            return ActivityScanModule.Signal.DOMESTIC;
        }
        if (BUILDING_SIGNALS.contains(block) || state.is(BlockTags.PLANKS)
            || state.is(BlockTags.STONE_BRICKS) || state.is(BlockTags.STAIRS)
            || state.is(BlockTags.SLABS) || state.is(BlockTags.WALLS)
            || state.is(BlockTags.FENCES) || state.is(BlockTags.RAILS)) {
            return ActivityScanModule.Signal.BUILDING;
        }
        return null;
    }

    private static void publishActivityCell(ActivityCell cell, SignalCount total, int cellSize, int noiseFloor) {
        BlockPos displayPosition = cell.toBlockPos(cellSize);
        if (total.signals < noiseFloor || total.signals == 0) {
            heatMap.remove(displayPosition);
        } else {
            heatMap.put(displayPosition, (float) total.score);
        }
    }

    private static void removeActivityChunk(ScanWindow.Chunk chunkKey) {
        Map<ActivityCell, SignalCount> removed = ACTIVITY_BY_CHUNK.remove(chunkKey);
        if (removed == null) {
            return;
        }
        for (Map.Entry<ActivityCell, SignalCount> entry : removed.entrySet()) {
            SignalCount total = ACTIVITY_TOTALS.get(entry.getKey());
            if (total == null) {
                continue;
            }
            total.subtract(entry.getValue());
            if (total.signals <= 0) {
                ACTIVITY_TOTALS.remove(entry.getKey());
                heatMap.remove(entry.getKey().toBlockPos(activityCellSize));
            } else {
                publishActivityCell(entry.getKey(), total, activityCellSize, currentActivityNoiseFloor());
            }
        }
    }

    private static int currentActivityNoiseFloor() {
        Module module = ModuleManager.getByName("Activity Scan");
        return module instanceof ActivityScanModule activity ? activity.getNoiseFloor() : 0;
    }

    private static void pruneSearchResults(Minecraft client, BlockPos playerPos) {
        Module base = ModuleManager.getByName("Block Search");
        if (!(base instanceof BlockSearchModule searchModule)
            || !searchModule.isEnabled() || !searchModule.isScanEnabled()
            || BlockSearchModule.searchBlocks.isEmpty()) {
            if (!SEARCH_RESULTS.values().isEmpty()) {
                SEARCH_RESULTS.clear();
                resultRevision++;
            }
            lastSearchPruneOrigin = null;
            lastSearchPruneRange = Double.NaN;
            lastSearchSelectionRevision = Integer.MIN_VALUE;
            return;
        }

        double range = searchModule.getRange();
        int selectionRevision = BlockSearchModule.getSelectionRevision();
        boolean rangeOrPositionChanged = !playerPos.equals(lastSearchPruneOrigin)
            || Double.compare(range, lastSearchPruneRange) != 0;
        boolean selectionChanged = selectionRevision != lastSearchSelectionRevision;
        if (!rangeOrPositionChanged && !selectionChanged) {
            return;
        }

        double rangeSquared = range * range;
        boolean changed = SEARCH_RESULTS.removeIf(pos -> {
            if (playerPos.distSqr(pos) > rangeSquared) {
                return true;
            }
            // Block edits are picked up during a chunk rescan. Selection changes
            // also validate retained positions without forcing an unloaded chunk.
            return selectionChanged && (!client.level.hasChunkAt(pos)
                || !BlockSearchModule.shouldTrack(client.level.getBlockState(pos).getBlock()));
        });
        if (changed) {
            resultRevision++;
        }

        lastSearchPruneOrigin = playerPos;
        lastSearchPruneRange = range;
        lastSearchSelectionRevision = selectionRevision;
    }

    private static void pruneResultsOutsideScanWindow(String moduleId, ScanWindow window) {
        boolean changed = switch (moduleId) {
            case "container_esp" -> CONTAINER_RESULTS.pruneOutside(window);
            case "spawner_esp" -> SPAWNER_RESULTS.pruneOutside(window);
            case "block_search" -> SEARCH_RESULTS.pruneOutside(window);
            case "activity_scan" -> pruneActivityOutside(window);
            default -> false;
        };
        if (changed) {
            resultRevision++;
        }
    }

    private static boolean pruneActivityOutside(ScanWindow window) {
        ArrayList<ScanWindow.Chunk> outside = new ArrayList<>();
        for (ScanWindow.Chunk chunk : ACTIVITY_BY_CHUNK.keySet()) {
            if (!window.contains(chunk.x(), chunk.z())) {
                outside.add(chunk);
            }
        }
        if (outside.isEmpty()) {
            return false;
        }
        outside.forEach(DataAggregator::removeActivityChunk);
        return true;
    }

    private static void removeChunkResults(String moduleId, int chunkX, int chunkZ) {
        boolean changed = switch (moduleId) {
            case "container_esp" -> CONTAINER_RESULTS.removeChunk(chunkX, chunkZ);
            case "spawner_esp" -> SPAWNER_RESULTS.removeChunk(chunkX, chunkZ);
            case "block_search" -> SEARCH_RESULTS.removeChunk(chunkX, chunkZ);
            case "activity_scan" -> {
                ScanWindow.Chunk key = new ScanWindow.Chunk(chunkX, chunkZ);
                boolean hadResults = ACTIVITY_BY_CHUNK.containsKey(key);
                removeActivityChunk(key);
                yield hadResults;
            }
            default -> false;
        };
        if (changed) {
            resultRevision++;
        }
    }

    private static void clearModuleResults(String moduleId) {
        boolean changed = switch (moduleId) {
            case "container_esp" -> clearStore(CONTAINER_RESULTS);
            case "spawner_esp" -> clearStore(SPAWNER_RESULTS);
            case "block_search" -> clearStore(SEARCH_RESULTS);
            case "activity_scan" -> clearActivityResults();
            default -> false;
        };
        if (changed) {
            resultRevision++;
        }
    }

    private static boolean hasModuleResults(String moduleId) {
        return switch (moduleId) {
            case "container_esp" -> !CONTAINER_RESULTS.values().isEmpty();
            case "spawner_esp" -> !SPAWNER_RESULTS.values().isEmpty();
            case "block_search" -> !SEARCH_RESULTS.values().isEmpty();
            case "activity_scan" -> !heatMap.isEmpty() || !ACTIVITY_BY_CHUNK.isEmpty();
            default -> false;
        };
    }

    private static boolean clearStore(ChunkResultStore<BlockPos> store) {
        if (store.values().isEmpty()) {
            return false;
        }
        store.clear();
        return true;
    }

    private static boolean clearActivityResults() {
        boolean changed = !heatMap.isEmpty() || !ACTIVITY_BY_CHUNK.isEmpty() || !ACTIVITY_TOTALS.isEmpty();
        heatMap.clear();
        ACTIVITY_BY_CHUNK.clear();
        ACTIVITY_TOTALS.clear();
        return changed;
    }

    private static void clearScanData() {
        CONTAINER_RESULTS.clear();
        SPAWNER_RESULTS.clear();
        SEARCH_RESULTS.clear();
        heatMap.clear();
        ACTIVITY_BY_CHUNK.clear();
        ACTIVITY_TOTALS.clear();
        SCAN_STATES.clear();
        moduleCursor = 0;
        resultRevision++;
        lastSearchPruneOrigin = null;
        lastSearchPruneRange = Double.NaN;
        lastSearchSelectionRevision = Integer.MIN_VALUE;
    }

    public static ScanWindow.Coverage getCoverage(String moduleId) {
        ScanState state = SCAN_STATES.get(moduleId);
        return state == null || !state.active || state.window == null
            ? new ScanWindow.Coverage(0, 0, 0) : state.window.coverage();
    }

    public static boolean isScanning(String moduleId) {
        ScanState state = SCAN_STATES.get(moduleId);
        return state != null && state.active && state.window != null;
    }

    public static long getResultRevision() {
        return resultRevision;
    }

    public static void clear() {
        clearScanData();
        activeLevel = null;
    }

    private record ActivityCell(int xCell, int ySection, int zCell) {
        private BlockPos toBlockPos(int cellSize) {
            return new BlockPos(xCell * cellSize + cellSize / 2,
                ySection * 16 + 8,
                zCell * cellSize + cellSize / 2);
        }
    }

    private static final class SignalCount {
        private double score;
        private int signals;

        private void add(float weight) {
            score += weight;
            signals++;
        }

        private void add(SignalCount other) {
            score += other.score;
            signals += other.signals;
        }

        private void subtract(SignalCount other) {
            score -= other.score;
            signals -= other.signals;
        }
    }

    private static final class ScanState {
        private ScanWindow window;
        private boolean active;
        private int refreshTicks;
        private long signature = Long.MIN_VALUE;
    }

    @FunctionalInterface
    private interface BlockVisitor {
        void visit(BlockState state, Block block, BlockPos pos);
    }
}
