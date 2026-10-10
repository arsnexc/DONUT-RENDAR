package me.kanha.ru.scan;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks feature results by chunk so rescans, window changes, unloads, and world
 * changes can prune exactly the affected results without force-loading chunks.
 */
public final class ChunkResultStore<T> {
    private final Set<T> values = ConcurrentHashMap.newKeySet();
    private final Map<ScanWindow.Chunk, Set<T>> byChunk = new HashMap<>();
    private Object worldIdentity;

    public Set<T> values() {
        return values;
    }

    public boolean replaceChunk(int chunkX, int chunkZ, Collection<? extends T> replacements) {
        ScanWindow.Chunk key = new ScanWindow.Chunk(chunkX, chunkZ);
        Set<T> next = new HashSet<>(replacements);
        Set<T> previous = byChunk.get(key);
        if (previous != null && previous.equals(next)) {
            return false;
        }

        if (previous != null) {
            values.removeAll(previous);
        }
        if (next.isEmpty()) {
            byChunk.remove(key);
        } else {
            byChunk.put(key, next);
            values.addAll(next);
        }
        return true;
    }

    public boolean removeChunk(int chunkX, int chunkZ) {
        Set<T> removed = byChunk.remove(new ScanWindow.Chunk(chunkX, chunkZ));
        return removed != null && values.removeAll(removed);
    }

    public boolean pruneOutside(ScanWindow window) {
        boolean changed = false;
        Iterator<Map.Entry<ScanWindow.Chunk, Set<T>>> iterator = byChunk.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ScanWindow.Chunk, Set<T>> entry = iterator.next();
            ScanWindow.Chunk chunk = entry.getKey();
            if (!window.contains(chunk.x(), chunk.z())) {
                values.removeAll(entry.getValue());
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    public boolean removeIf(Predicate<? super T> predicate) {
        boolean changed = false;
        Iterator<Map.Entry<ScanWindow.Chunk, Set<T>>> chunkIterator = byChunk.entrySet().iterator();
        while (chunkIterator.hasNext()) {
            Map.Entry<ScanWindow.Chunk, Set<T>> entry = chunkIterator.next();
            Set<T> chunkValues = entry.getValue();
            Iterator<T> valueIterator = chunkValues.iterator();
            while (valueIterator.hasNext()) {
                T value = valueIterator.next();
                if (predicate.test(value)) {
                    valueIterator.remove();
                    values.remove(value);
                    changed = true;
                }
            }
            if (chunkValues.isEmpty()) {
                chunkIterator.remove();
            }
        }
        return changed;
    }

    /** Clears results only when the identity token changes, not on repeated ticks in one level. */
    public boolean resetForWorld(Object nextWorldIdentity) {
        if (worldIdentity == nextWorldIdentity) {
            return false;
        }
        clear();
        worldIdentity = nextWorldIdentity;
        return true;
    }

    public void clear() {
        values.clear();
        byChunk.clear();
    }
}
