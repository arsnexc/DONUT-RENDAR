package me.kanha.ru.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.kanha.ru.module.BlockSearchModule;
import me.kanha.ru.module.Module;
import me.kanha.ru.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned client-only configuration for local scan, HUD, search, and marker preferences. */
public final class Settings {
    public static final int CURRENT_CONFIG_VERSION = 2;
    /** Legacy/shared default retained for migration and as the initial radius for new modules. */
    public static int scanRadius = 4;

    private static final int MIN_SCAN_RADIUS = 1;
    private static final int MAX_SCAN_RADIUS = 6;
    private static final int MAX_PRESETS = 16;
    private static final int MAX_PRESET_BLOCKS = 512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir().resolve("renderutil.json");
    private static ConfigData config = new ConfigData();

    private Settings() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            config = new ConfigData();
            config.schemaVersion = CURRENT_CONFIG_VERSION;
            scanRadius = 4;
            writeConfig();
            return;
        }

        try {
            ConfigData loaded = GSON.fromJson(Files.readString(CONFIG_PATH), ConfigData.class);
            if (loaded == null) {
                loaded = new ConfigData();
            }

            sanitize(loaded);
            int oldRadius = clampRadius(loaded.scanRadius);
            boolean migrated = loaded.schemaVersion < CURRENT_CONFIG_VERSION;
            loaded.scanRadius = oldRadius;
            if (loaded.schemaVersion < 2) {
                // The original unversioned file only contained scanRadius. Carry
                // that choice into each feature's new independent scan window.
                for (String moduleId : knownModuleIds()) {
                    ModuleConfig moduleConfig = loaded.modules.computeIfAbsent(moduleId,
                        ignored -> new ModuleConfig());
                    moduleConfig.scanRadius = oldRadius;
                    moduleConfig.scanEnabled = true;
                }
            }
            loaded.schemaVersion = CURRENT_CONFIG_VERSION;
            sanitize(loaded);
            config = loaded;
            scanRadius = loaded.scanRadius;

            if (migrated) {
                writeConfig();
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            System.err.println("[RenderUtil] Could not load config: " + e.getMessage());
            config = new ConfigData();
            scanRadius = 4;
        }
    }

    /** Applies saved module-setting values and selected block identifiers after registries are ready. */
    public static void applyToModules() {
        for (Module module : ModuleManager.getAll()) {
            ModuleConfig saved = config.modules.get(module.getId());
            if (saved == null) {
                continue;
            }
            module.setScanEnabled(saved.scanEnabled);
            module.setScanRadius(saved.scanRadius);

            for (Module.Setting setting : module.getSettings()) {
                if (setting.getKey().equals("scanEnabled") || setting.getKey().equals("scanRadius")) {
                    continue;
                }
                if (setting instanceof Module.BooleanSetting booleanSetting) {
                    Boolean value = saved.booleanSettings.get(setting.getKey());
                    if (value != null) {
                        booleanSetting.set(value);
                    }
                } else if (setting instanceof Module.NumberSetting numberSetting) {
                    Double value = saved.numberSettings.get(setting.getKey());
                    if (value != null && Double.isFinite(value)) {
                        numberSetting.set(value);
                    }
                }
            }
        }
        applyBlockIds(config.selectedBlockIds);
    }

    public static void save() {
        config.schemaVersion = CURRENT_CONFIG_VERSION;
        config.scanRadius = clampRadius(scanRadius);
        captureModuleSettings();
        config.selectedBlockIds = currentSelectedBlockIds();
        sanitize(config);
        writeConfig();
    }

    public static HudPreferences hud() {
        return config.hud;
    }

    public static MarkerStyle markerStyle(String moduleId) {
        MarkerStyle style = config.markerStyles.computeIfAbsent(moduleId, Settings::defaultMarkerStyle);
        sanitize(style);
        return style;
    }

    public static List<String> getPresetNames() {
        ArrayList<String> names = new ArrayList<>(config.searchPresets.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    public static List<String> getPresetBlockIds(String name) {
        List<String> ids = config.searchPresets.get(name);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    public static void saveSearchPreset(String name) {
        String safeName = sanitizePresetName(name);
        if (safeName.isEmpty()) {
            return;
        }
        if (!config.searchPresets.containsKey(safeName) && config.searchPresets.size() >= MAX_PRESETS) {
            String oldest = config.searchPresets.keySet().iterator().next();
            config.searchPresets.remove(oldest);
        }
        config.searchPresets.put(safeName, currentSelectedBlockIds());
    }

    public static void deleteSearchPreset(String name) {
        config.searchPresets.remove(name);
    }

    public static void applySearchPreset(String name) {
        applyBlockIds(getPresetBlockIds(name));
    }

    public static void replaceSelectedBlockIds(Collection<String> ids) {
        applyBlockIds(ids);
        config.selectedBlockIds = currentSelectedBlockIds();
    }

    public static String colorHex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private static void applyBlockIds(Collection<String> identifiers) {
        Set<String> selectedIds = new LinkedHashSet<>(identifiers);
        ArrayList<Block> selectedBlocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (selectedIds.contains(BuiltInRegistries.BLOCK.getKey(block).toString())) {
                selectedBlocks.add(block);
            }
        }
        BlockSearchModule.replaceSelection(selectedBlocks);
    }

    private static List<String> currentSelectedBlockIds() {
        ArrayList<String> identifiers = new ArrayList<>();
        for (Block block : BlockSearchModule.searchBlocks) {
            identifiers.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        identifiers.sort(String.CASE_INSENSITIVE_ORDER);
        return identifiers;
    }

    private static void captureModuleSettings() {
        for (Module module : ModuleManager.getAll()) {
            ModuleConfig saved = config.modules.computeIfAbsent(module.getId(),
                ignored -> new ModuleConfig());
            saved.scanEnabled = module.isScanEnabled();
            saved.scanRadius = module.getScanRadius();
            saved.booleanSettings.clear();
            saved.numberSettings.clear();
            for (Module.Setting setting : module.getSettings()) {
                if (setting.getKey().equals("scanEnabled") || setting.getKey().equals("scanRadius")) {
                    continue;
                }
                if (setting instanceof Module.BooleanSetting booleanSetting) {
                    saved.booleanSettings.put(setting.getKey(), booleanSetting.get());
                } else if (setting instanceof Module.NumberSetting numberSetting) {
                    saved.numberSettings.put(setting.getKey(), numberSetting.get());
                }
            }
        }
    }

    private static void writeConfig() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(config));
        } catch (IOException e) {
            System.err.println("[RenderUtil] Could not save config: " + e.getMessage());
        }
    }

    private static void sanitize(ConfigData data) {
        if (data.modules == null) {
            data.modules = new LinkedHashMap<>();
        }
        if (data.searchPresets == null) {
            data.searchPresets = new LinkedHashMap<>();
        }
        if (data.selectedBlockIds == null) {
            data.selectedBlockIds = new ArrayList<>();
        }
        if (data.markerStyles == null) {
            data.markerStyles = new LinkedHashMap<>();
        }
        if (data.hud == null) {
            data.hud = new HudPreferences();
        }
        data.scanRadius = clampRadius(data.scanRadius);

        for (Map.Entry<String, ModuleConfig> entry : data.modules.entrySet()) {
            ModuleConfig module = entry.getValue();
            if (module == null) {
                module = new ModuleConfig();
                entry.setValue(module);
            }
            module.scanRadius = clampRadius(module.scanRadius);
            if (module.numberSettings == null) {
                module.numberSettings = new LinkedHashMap<>();
            }
            if (module.booleanSettings == null) {
                module.booleanSettings = new LinkedHashMap<>();
            }
            module.numberSettings.values().removeIf(value -> value == null || !Double.isFinite(value));
        }

        data.selectedBlockIds = data.selectedBlockIds.stream()
            .filter(id -> id != null && id.length() <= 128)
            .distinct()
            .limit(MAX_PRESET_BLOCKS)
            .toList();

        LinkedHashMap<String, List<String>> safePresets = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : data.searchPresets.entrySet()) {
            String name = sanitizePresetName(entry.getKey());
            List<String> ids = entry.getValue();
            if (!name.isEmpty() && ids != null && safePresets.size() < MAX_PRESETS) {
                safePresets.put(name, ids.stream()
                    .filter(id -> id != null && id.length() <= 128)
                    .distinct()
                    .limit(MAX_PRESET_BLOCKS)
                    .toList());
            }
        }
        data.searchPresets = safePresets;

        for (Map.Entry<String, MarkerStyle> entry : data.markerStyles.entrySet()) {
            if (entry.getValue() == null) {
                entry.setValue(defaultMarkerStyle(entry.getKey()));
            }
            sanitize(entry.getValue());
        }
        sanitize(data.hud);
    }

    private static void sanitize(HudPreferences hud) {
        hud.layout = "COMPACT".equals(hud.layout) ? "COMPACT" : "STACKED";
        if (!List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(hud.corner)) {
            hud.corner = "TOP_LEFT";
        }
        hud.textColor = normalizeRgb(hud.textColor);
        hud.accentColor = normalizeRgb(hud.accentColor);
    }

    private static void sanitize(MarkerStyle style) {
        style.fillColor = normalizeRgb(style.fillColor);
        style.outlineColor = normalizeRgb(style.outlineColor);
        if (!Double.isFinite(style.fillOpacity)) {
            style.fillOpacity = 0.16;
        }
        style.fillOpacity = Math.max(0.02, Math.min(0.80, style.fillOpacity));
        if (!Double.isFinite(style.outlineWidth)) {
            style.outlineWidth = 0.035;
        }
        style.outlineWidth = Math.max(0.01, Math.min(0.15, style.outlineWidth));
    }

    private static MarkerStyle defaultMarkerStyle(String moduleId) {
        return switch (moduleId) {
            case "container_esp" -> new MarkerStyle(0xFFFF3333, 0xFFFF4747, 0.16, 0.035, true);
            case "spawner_esp" -> new MarkerStyle(0xFFFFC928, 0xFFFFFF48, 0.17, 0.035, true);
            case "block_search" -> new MarkerStyle(0xFF397FFF, 0xFF61B7FF, 0.16, 0.035, true);
            case "activity_scan" -> new MarkerStyle(0xFF3E91E8, 0xFF80C8FF, 0.16, 0.035, true);
            default -> new MarkerStyle(0xFF4A9EFF, 0xFFFFFFFF, 0.16, 0.035, true);
        };
    }

    private static List<String> knownModuleIds() {
        return List.of("container_esp", "spawner_esp", "block_search", "activity_scan");
    }

    private static int normalizeRgb(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static String sanitizePresetName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32).trim();
        }
        return cleaned;
    }

    private static int clampRadius(int radius) {
        return Math.max(MIN_SCAN_RADIUS, Math.min(MAX_SCAN_RADIUS, radius));
    }

    private static final class ConfigData {
        private int schemaVersion;
        private int scanRadius = 4;
        private Map<String, ModuleConfig> modules = new LinkedHashMap<>();
        private List<String> selectedBlockIds = new ArrayList<>();
        private Map<String, List<String>> searchPresets = new LinkedHashMap<>();
        private HudPreferences hud = new HudPreferences();
        private Map<String, MarkerStyle> markerStyles = defaultMarkerStyles();

        private static Map<String, MarkerStyle> defaultMarkerStyles() {
            LinkedHashMap<String, MarkerStyle> styles = new LinkedHashMap<>();
            for (String moduleId : knownModuleIds()) {
                styles.put(moduleId, defaultMarkerStyle(moduleId));
            }
            return styles;
        }
    }

    private static final class ModuleConfig {
        private boolean scanEnabled = true;
        private int scanRadius = 4;
        private Map<String, Double> numberSettings = new LinkedHashMap<>();
        private Map<String, Boolean> booleanSettings = new LinkedHashMap<>();
    }

    public static final class HudPreferences {
        public boolean visible = true;
        public boolean showCoverage = true;
        public String layout = "STACKED";
        public String corner = "TOP_LEFT";
        public int textColor = 0xFFFFFFFF;
        public int accentColor = 0xFF4A9EFF;
    }

    public static final class MarkerStyle {
        public int fillColor;
        public int outlineColor;
        public double fillOpacity;
        public double outlineWidth;
        public boolean throughWalls;

        public MarkerStyle() {
            this(0xFF4A9EFF, 0xFFFFFFFF, 0.16, 0.035, true);
        }

        public MarkerStyle(int fillColor, int outlineColor, double fillOpacity,
                           double outlineWidth, boolean throughWalls) {
            this.fillColor = fillColor;
            this.outlineColor = outlineColor;
            this.fillOpacity = fillOpacity;
            this.outlineWidth = outlineWidth;
            this.throughWalls = throughWalls;
        }
    }
}
