package me.kanha.ru.module;

public final class ActivityScanModule extends Module {
    private final NumberSetting threshold = new NumberSetting("Threshold", 8, 1, 50, 1);

    public ActivityScanModule() {
        super("Activity Scan", Category.RENDER);
        addSetting(threshold);
    }

    public float getThreshold() {
        return (float) threshold.get();
    }

    @Override
    public void onTick() {
        // Chunk scanning is handled by DataAggregator.
    }
}
