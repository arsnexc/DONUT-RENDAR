package me.kanha.ru.module;

import me.kanha.ru.RenderUtilClient;
import me.kanha.ru.config.Settings;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public abstract class Module {
    public enum Category {
        RENDER("Render"),
        SEARCH("Search"),
        MISC("Misc");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    private final String id;
    private final String name;
    private final Category category;
    private final List<Setting> settings = new ArrayList<>();
    private final BooleanSetting scanEnabled = new BooleanSetting("Scan", "scanEnabled", true);
    private final NumberSetting scanRadius = new NumberSetting("Radius", "scanRadius",
        Settings.scanRadius, 1, 6, 1);
    private boolean enabled;
    private boolean expanded;

    protected Module(String id, String name, Category category) {
        this.id = id;
        this.name = name;
        this.category = category;
        addSetting(scanEnabled);
        addSetting(scanRadius);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled && !RenderUtilClient.hasLocalWorld(Minecraft.getInstance())) {
            this.enabled = false;
            return;
        }
        this.enabled = enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    public List<Setting> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    public boolean isScanEnabled() {
        return scanEnabled.get();
    }

    public void setScanEnabled(boolean enabled) {
        scanEnabled.set(enabled);
    }

    public int getScanRadius() {
        return (int) Math.round(scanRadius.get());
    }

    public void setScanRadius(int radius) {
        scanRadius.set(radius);
    }

    protected void addSetting(Setting setting) {
        settings.add(setting);
    }

    public abstract void onTick();

    public abstract static class Setting {
        private final String key;
        private final String name;

        protected Setting(String name, String key) {
            this.name = name;
            this.key = key;
        }

        protected Setting(String name) {
            this(name, toKey(name));
        }

        private static String toKey(String name) {
            return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        }

        public String getKey() {
            return key;
        }

        public String getName() {
            return name;
        }

        public abstract String getValueText();
    }

    public static final class BooleanSetting extends Setting {
        private boolean value;

        public BooleanSetting(String name, boolean defaultValue) {
            super(name);
            value = defaultValue;
        }

        public BooleanSetting(String name, String key, boolean defaultValue) {
            super(name, key);
            value = defaultValue;
        }

        public boolean get() {
            return value;
        }

        public void set(boolean value) {
            this.value = value;
        }

        public void toggle() {
            value = !value;
        }

        @Override
        public String getValueText() {
            return value ? "ON" : "OFF";
        }
    }

    public static final class NumberSetting extends Setting {
        private final double min;
        private final double max;
        private final double step;
        private double value;

        public NumberSetting(String name, double defaultValue, double min, double max, double step) {
            super(name);
            this.min = min;
            this.max = max;
            this.step = step;
            set(defaultValue);
        }

        public NumberSetting(String name, String key, double defaultValue, double min, double max, double step) {
            super(name, key);
            this.min = min;
            this.max = max;
            this.step = step;
            set(defaultValue);
        }

        public double get() {
            return value;
        }

        public void set(double value) {
            if (!Double.isFinite(value)) {
                value = min;
            }
            this.value = Math.max(min, Math.min(max, value));
        }

        public void adjust(double direction) {
            set(value + (step * direction));
        }

        @Override
        public String getValueText() {
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return Long.toString(Math.round(value));
            }
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }
}
