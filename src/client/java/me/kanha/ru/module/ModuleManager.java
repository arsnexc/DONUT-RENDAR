package me.kanha.ru.module;

import java.util.ArrayList;
import java.util.List;

public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();

    private ModuleManager() {
    }

    public static void init() {
        MODULES.clear();
        MODULES.add(new ContainerEspModule());
        MODULES.add(new SpawnerEspModule());
        MODULES.add(new BlockSearchModule());
        MODULES.add(new ActivityScanModule());
    }

    public static void tick() {
        for (Module module : MODULES) {
            if (module.isEnabled()) {
                module.onTick();
            }
        }
    }

    public static List<Module> getAll() {
        return List.copyOf(MODULES);
    }

    public static List<Module> getModulesByCategory(Module.Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : MODULES) {
            if (module.getCategory() == category) {
                result.add(module);
            }
        }
        return result;
    }

    public static Module getByName(String name) {
        for (Module module : MODULES) {
            if (module.getName().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    public static boolean anyEnabled() {
        for (Module module : MODULES) {
            if (module.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    public static void disableAll() {
        for (Module module : MODULES) {
            module.setEnabled(false);
        }
    }
}
