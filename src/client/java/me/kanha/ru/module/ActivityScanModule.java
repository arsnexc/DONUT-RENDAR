package me.kanha.ru.module;

public final class ActivityScanModule extends Module {
    public enum Signal {
        STORAGE,
        WORKSTATION,
        BUILDING,
        REDSTONE,
        DOMESTIC
    }

    private final NumberSetting threshold = new NumberSetting("Score threshold", "threshold", 8, 1, 100, 1);
    private final NumberSetting noiseFloor = new NumberSetting("Noise floor", "noiseFloor", 3, 0, 32, 1);
    private final NumberSetting cellSize = new NumberSetting("Map cell", "cellSize", 16, 16, 64, 16);
    private final NumberSetting storageWeight = new NumberSetting("Storage weight", "storageWeight", 3, 0, 5, 1);
    private final NumberSetting workstationWeight = new NumberSetting("Workstation weight", "workstationWeight", 3, 0, 5, 1);
    private final NumberSetting buildingWeight = new NumberSetting("Building weight", "buildingWeight", 1, 0, 5, 1);
    private final NumberSetting redstoneWeight = new NumberSetting("Redstone weight", "redstoneWeight", 3, 0, 5, 1);
    private final NumberSetting domesticWeight = new NumberSetting("Home/decor weight", "domesticWeight", 2, 0, 5, 1);

    public ActivityScanModule() {
        super("activity_scan", "Activity Scan", Category.RENDER);
        addSetting(threshold);
        addSetting(noiseFloor);
        addSetting(cellSize);
        addSetting(storageWeight);
        addSetting(workstationWeight);
        addSetting(buildingWeight);
        addSetting(redstoneWeight);
        addSetting(domesticWeight);
    }

    public float getThreshold() {
        return (float) threshold.get();
    }

    public int getNoiseFloor() {
        return (int) Math.round(noiseFloor.get());
    }

    public int getCellSize() {
        return (int) Math.round(cellSize.get());
    }

    public float getWeight(Signal signal) {
        double value = switch (signal) {
            case STORAGE -> storageWeight.get();
            case WORKSTATION -> workstationWeight.get();
            case BUILDING -> buildingWeight.get();
            case REDSTONE -> redstoneWeight.get();
            case DOMESTIC -> domesticWeight.get();
        };
        return (float) value;
    }

    /** Changes to any aggregation option invalidate Activity Scan's cached chunk summaries. */
    public long getAggregationSignature() {
        long signature = 17;
        signature = signature * 31 + getNoiseFloor();
        signature = signature * 31 + getCellSize();
        for (Signal signal : Signal.values()) {
            signature = signature * 31 + Float.floatToIntBits(getWeight(signal));
        }
        return signature;
    }

    @Override
    public void onTick() {
        // Chunk scanning is handled by DataAggregator.
    }
}
