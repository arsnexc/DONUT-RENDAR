package me.kanha.ru.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Settings {
    public static int scanRadius = 4;

    private static final int MIN_SCAN_RADIUS = 1;
    private static final int MAX_SCAN_RADIUS = 6;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir().resolve("renderutil.json");

    private Settings() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try {
            ConfigData data = GSON.fromJson(Files.readString(CONFIG_PATH), ConfigData.class);
            if (data != null) {
                scanRadius = clampRadius(data.scanRadius);
            }
        } catch (IOException | JsonParseException e) {
            System.err.println("[RenderUtil] Could not load config: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            ConfigData data = new ConfigData();
            data.scanRadius = clampRadius(scanRadius);
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (IOException e) {
            System.err.println("[RenderUtil] Could not save config: " + e.getMessage());
        }
    }

    private static int clampRadius(int radius) {
        return Math.max(MIN_SCAN_RADIUS, Math.min(MAX_SCAN_RADIUS, radius));
    }

    private static final class ConfigData {
        private int scanRadius = 4;
    }
}
