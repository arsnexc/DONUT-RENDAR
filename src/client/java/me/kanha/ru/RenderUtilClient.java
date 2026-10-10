package me.kanha.ru;

import com.mojang.blaze3d.platform.InputConstants;
import me.kanha.ru.config.Settings;
import me.kanha.ru.gui.BlockSearchScreen;
import me.kanha.ru.gui.ClickGuiScreen;
import me.kanha.ru.hud.DisplayLayer;
import me.kanha.ru.module.ModuleManager;
import me.kanha.ru.render.WorldPainter;
import me.kanha.ru.scan.DataAggregator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class RenderUtilClient implements ClientModInitializer {
    public static KeyMapping guiKey;
    public static KeyMapping searchKey;
    public static KeyMapping panicKey;

    @Override
    public void onInitializeClient() {
        Settings.load();
        ModuleManager.init();
        Settings.applyToModules();
        WorldPainter.init();
        DisplayLayer.init();

        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("renderutil", "main")
        );

        guiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.renderutil.gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            category
        ));

        searchKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.renderutil.search",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            category
        ));

        panicKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.renderutil.panic",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_END,
            category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (hasLocalWorld(client)) {
                ModuleManager.tick();
                DataAggregator.tick(client);
            } else {
                // Never retain or render scan results outside an integrated local world.
                ModuleManager.disableAll();
                DataAggregator.clear();
            }

            while (guiKey.consumeClick()) {
                client.setScreen(new ClickGuiScreen());
            }

            while (searchKey.consumeClick()) {
                client.setScreen(new BlockSearchScreen());
            }

            while (panicKey.consumeClick()) {
                ModuleManager.disableAll();
                DataAggregator.clear();
                Settings.save();
            }
        });
    }

    /** World scanning and markers are intentionally restricted to local worlds. */
    public static boolean hasLocalWorld(Minecraft client) {
        return client != null
            && client.player != null
            && client.level != null
            && client.hasSingleplayerServer();
    }
}
