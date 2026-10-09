package me.kanha.ru.module;

public final class SpawnerEspModule extends Module {
    private final BooleanSetting highlight = new BooleanSetting("Highlight", true);

    public SpawnerEspModule() {
        super("Spawner ESP", Category.RENDER);
        addSetting(highlight);
    }

    public boolean shouldHighlight() {
        return highlight.get();
    }

    @Override
    public void onTick() {
        // Chunk scanning and rendering are handled by their client-side services.
    }
}
