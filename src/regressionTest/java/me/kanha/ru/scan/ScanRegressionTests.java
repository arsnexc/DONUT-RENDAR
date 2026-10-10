package me.kanha.ru.scan;

import java.util.List;
import java.util.Set;

/** Dependency-free regression checks for the pure scan-window/result-store logic. */
public final class ScanRegressionTests {
    private ScanRegressionTests() {
    }

    public static void main(String[] args) {
        verifiesNearestFirstOrderingAndWraparound();
        verifiesLoadedAndVisitedCoverageAreIndependent();
        verifiesWindowPruningOnRadiusAndCenterChanges();
        verifiesWorldIdentityChangeClearsResults();
        System.out.println("RenderUtil scan regression checks passed.");
    }

    private static void verifiesNearestFirstOrderingAndWraparound() {
        ScanWindow window = new ScanWindow(10, -5, 1);
        List<ScanWindow.Chunk> expected = List.of(
            new ScanWindow.Chunk(10, -5),
            new ScanWindow.Chunk(10, -6),
            new ScanWindow.Chunk(9, -5),
            new ScanWindow.Chunk(11, -5),
            new ScanWindow.Chunk(10, -4)
        );
        for (ScanWindow.Chunk chunk : expected) {
            check(window.nextCandidate().equals(chunk), "nearest-first scan ordering changed");
        }
        for (int i = 0; i < 4; i++) {
            window.nextCandidate();
        }
        check(window.nextCandidate().equals(expected.get(0)), "scan cursor did not wrap to the nearest chunk");
        check(window.total() == 9, "radius-one window should contain nine chunks");
    }

    private static void verifiesLoadedAndVisitedCoverageAreIndependent() {
        ScanWindow window = new ScanWindow(0, 0, 2);
        window.setLoadedCount(7);
        check(window.markVisited(0, 0), "first visit should be recorded");
        check(!window.markVisited(0, 0), "a repeated visit must not inflate the count");
        check(!window.markVisited(3, 0), "a chunk outside the window must not be visited");
        ScanWindow.Coverage coverage = window.coverage();
        check(coverage.loaded() == 7 && coverage.total() == 25 && coverage.visited() == 1,
            "loaded and visited counts must be reported separately");
        window.setLoadedCount(1000);
        check(window.coverage().loaded() == 25, "loaded coverage must be capped at the window total");
    }

    private static void verifiesWindowPruningOnRadiusAndCenterChanges() {
        ChunkResultStore<String> results = new ChunkResultStore<>();
        results.replaceChunk(0, 0, List.of("center"));
        results.replaceChunk(1, 0, List.of("edge"));
        results.replaceChunk(2, 0, List.of("outside"));

        ScanWindow narrow = new ScanWindow(0, 0, 1);
        check(results.pruneOutside(narrow), "shrinking the radius should prune results outside the window");
        check(results.values().equals(Set.of("center", "edge")), "radius pruning retained the wrong results");

        ScanWindow moved = new ScanWindow(2, 0, 1);
        check(results.pruneOutside(moved), "moving the player center should prune out-of-window results");
        check(results.values().equals(Set.of("edge")), "center movement should retain only intersecting chunks");
        results.replaceChunk(2, 0, List.of("newly scanned"));
        results.removeChunk(1, 0);
        check(results.values().equals(Set.of("newly scanned")), "unloaded-chunk pruning should remove only that chunk's results");
    }

    private static void verifiesWorldIdentityChangeClearsResults() {
        ChunkResultStore<String> results = new ChunkResultStore<>();
        Object firstWorld = new Object();
        Object secondWorld = new Object();
        check(results.resetForWorld(firstWorld), "first world identity must establish the store world");
        results.replaceChunk(4, -2, List.of("old-level result"));
        check(!results.resetForWorld(firstWorld), "the same level identity must not clear results repeatedly");
        check(results.resetForWorld(secondWorld), "a new level identity must reset the store");
        check(results.values().isEmpty(), "results from the previous level must not survive a world change");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
