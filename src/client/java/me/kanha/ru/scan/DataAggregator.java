package me.kanha.ru.scan;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.config.Settings;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

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

    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final Set<Block> CONTAINERS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.FURNACE,
        Blocks.BLAST_FURNACE, Blocks.SMOKER
    );
    private static final Set<Block> CRAFTED = Set.of(
        Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.OAK_PLANKS,
        Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.CRIMSON_PLANKS,
        Blocks.WARPED_PLANKS, Blocks.GLASS, Blocks.WHITE_CONCRETE,
        Blocks.DEEPSLATE_BRICKS, Blocks.NETHER_BRICKS, Blocks.BRICKS,
        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG
    );

    private static int scanCooldown;
    private static int chunkCursor;

    private DataAggregator() {
    }

    public static void tick(Minecraft client) {
        if (!RenderUtilClient.hasLocalWorld(client)) {
            clear();
            return;
        }

        clearDisabledModuleData();
        if (!ModuleManager.anyEnabled()) {
            return;
        }

        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }
        scanCooldown = SCAN_INTERVAL_TICKS;

        int radius = Math.max(1, Math.min(6, Settings.scanRadius));
        int side = radius * 2 + 1;
        int totalChunks = side * side;
        if (chunkCursor >= totalChunks) {
            chunkCursor = 0;
        }

        int offsetX = chunkCursor % side - radius;
        int offsetZ = chunkCursor / side - radius;
        chunkCursor = (chunkCursor + 1) % totalChunks;

        int chunkX = client.player.chunkPosition().x + offsetX;
        int chunkZ = client.player.chunkPosition().z + offsetZ;
        // 1.21.11 renamed the LevelHeightAccessor height APIs:
        // getMinBuildHeight() -> getMinY(), getMaxBuildHeight() -> getMaxY()
        // (getMaxY() is already the inclusive top block, so no -1 adjustment).
        int y = Math.max(client.level.getMinY(),
            Math.min(client.level.getMaxY(), client.player.blockPosition().getY()));
        BlockPos probe = new BlockPos(chunkX * 16 + 8, y, chunkZ * 16 + 8);

        // hasChunkAt checks the client cache; do not force-load chunks to scan them.
        if (!client.level.hasChunkAt(probe)) {
            return;
        }

        LevelChunk chunk = client.level.getChunk(chunkX, chunkZ);
        scanChunk(chunk, client);
    }

    private static void scanChunk(LevelChunk chunk, Minecraft client) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        removeChunkResults(chunkX, chunkZ);

        boolean scanContainers = isEnabled("Container ESP");
        boolean scanSpawners = isEnabled("Spawner ESP");
        Module searchBase = ModuleManager.getByName("Block Search");
        BlockSearchModule searchModule = searchBase instanceof BlockSearchModule search ? search : null;
        boolean scanSearch = searchModule != null && searchModule.isEnabled();
        double searchRange = searchModule == null ? 64.0 : searchModule.getRange();
        double searchRangeSquared = searchRange * searchRange;
        boolean scanActivity = isEnabled("Activity Scan");
        if (!scanContainers && !scanSpawners && !scanSearch && !scanActivity) {
            return;
        }

        BlockPos playerPos = client.player.blockPosition();
        int craftedCount = 0;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int baseY = chunk.getMinSectionY() * 16 + sectionIndex * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }

                        Block block = state.getBlock();
                        BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);

                        if (scanContainers && (CONTAINERS.contains(block) || block instanceof ShulkerBoxBlock)) {
                            trackedContainers.add(pos);
                        }
                        if (scanSpawners && block == Blocks.SPAWNER) {
                            trackedSpawners.add(pos);
                        }
                        if (scanActivity && CRAFTED.contains(block)) {
                            craftedCount++;
                        }
                        if (scanSearch && BlockSearchModule.shouldTrack(block)
                            && playerPos.distSqr(pos) <= searchRangeSquared) {
                            BlockSearchModule.foundBlocks.add(pos);
                        }
                    }
                }
            }
        }

        if (scanActivity && craftedCount > 0) {
            BlockPos center = new BlockPos(baseX + 8, playerPos.getY(), baseZ + 8);
            heatMap.put(center, (float) craftedCount);
        }
    }

    private static void removeChunkResults(int chunkX, int chunkZ) {
        trackedContainers.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        trackedSpawners.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        BlockSearchModule.foundBlocks.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        heatMap.keySet().removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
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

    private static boolean isEnabled(String name) {
        Module module = ModuleManager.getByName(name);
        return module != null && module.isEnabled();
    }

    public static void clear() {
        trackedContainers.clear();
        trackedSpawners.clear();
        heatMap.clear();
        BlockSearchModule.clearFound();
        scanCooldown = 0;
        chunkCursor = 0;
    }
}
