package me.kanha.ru.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure per-feature scan window and coverage counter, kept separate from Minecraft APIs for regression tests. */
public final class ScanWindow {
    public record Chunk(int x, int z) {
    }

    public record Coverage(int loaded, int total, int visited) {
    }

    private static final Comparator<Chunk> NEAREST_FIRST = Comparator
        .comparingInt((Chunk chunk) -> {
            int x = chunk.x();
            int z = chunk.z();
            return x * x + z * z;
        })
        .thenComparingInt(chunk -> Math.abs(chunk.x()) + Math.abs(chunk.z()))
        .thenComparingInt(Chunk::z)
        .thenComparingInt(Chunk::x);

    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final List<Chunk> orderedOffsets;
    private final Map<Chunk, Integer> indexByOffset;
    private final boolean[] visited;
    private int cursor;
    private int loaded;
    private int visitedCount;

    public ScanWindow(int centerX, int centerZ, int radius) {
        if (radius < 1 || radius > 6) {
            throw new IllegalArgumentException("Scan radius must be between 1 and 6 chunks");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;

        ArrayList<Chunk> offsets = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                offsets.add(new Chunk(x, z));
            }
        }
        offsets.sort(NEAREST_FIRST);
        orderedOffsets = List.copyOf(offsets);
        indexByOffset = new HashMap<>(orderedOffsets.size());
        for (int index = 0; index < orderedOffsets.size(); index++) {
            indexByOffset.put(orderedOffsets.get(index), index);
        }
        visited = new boolean[orderedOffsets.size()];
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public int total() {
        return orderedOffsets.size();
    }

    public List<Chunk> orderedOffsets() {
        return orderedOffsets;
    }

    /** Returns the next offset in nearest-first order and advances the round-robin cursor. */
    public Chunk nextCandidate() {
        Chunk offset = orderedOffsets.get(cursor);
        cursor = (cursor + 1) % orderedOffsets.size();
        return new Chunk(centerX + offset.x(), centerZ + offset.z());
    }

    /** Marks a loaded chunk as visited. Repeated visits do not inflate coverage. */
    public boolean markVisited(int chunkX, int chunkZ) {
        Integer index = indexByOffset.get(new Chunk(chunkX - centerX, chunkZ - centerZ));
        if (index == null || visited[index]) {
            return false;
        }
        visited[index] = true;
        visitedCount++;
        return true;
    }

    public boolean contains(int chunkX, int chunkZ) {
        return Math.abs(chunkX - centerX) <= radius && Math.abs(chunkZ - centerZ) <= radius;
    }

    public void setLoadedCount(int count) {
        loaded = Math.max(0, Math.min(total(), count));
    }

    public Coverage coverage() {
        return new Coverage(loaded, total(), visitedCount);
    }
}
