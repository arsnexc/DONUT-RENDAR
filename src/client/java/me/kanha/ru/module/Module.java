package me.kanha.ru.module;

import me.kanha.ru.RenderUtilClient;
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

    private final String name;
    private final Category category;
    private final List<Setting> settings = new ArrayList<>();
    private boolean enabled;
    private boolean expanded;

    protected Module(String name, Category category) {
        this.name = name;
        this.category = category;
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

    protected void addSetting(Setting setting) {
        settings.add(setting);
    }

    public abstract void onTick();

    public abstract static class Setting {
        private final String name;

        protected Setting(String name) {
            this.name = name;
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

        public double get() {
            return value;
        }

        public void set(double value) {
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
            return String.format(Locale.ROOT, "%.1f", value);
        }
    }
}
