package com.trailblazer.fabric;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;

public class KeyBindingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeyBindingManager.class);

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("trailblazer", "trailblazer"));

    private static KeyMapping toggleRecordingKey;
    private static KeyMapping cycleRenderModeKey;
    private static KeyMapping openMenuKey;

    public static void initialize(RenderSettingsManager renderSettingsManager, ClientPathManager clientPathManager) {
        toggleRecordingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.trailblazer.toggle_recording",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KEY_CATEGORY));

        cycleRenderModeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.trailblazer.cycle_render_mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                KEY_CATEGORY));
        
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.trailblazer.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KEY_CATEGORY));

        registerKeyListeners(renderSettingsManager, clientPathManager);
    }

    private static void registerKeyListeners(RenderSettingsManager renderSettingsManager, ClientPathManager clientPathManager) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleRecordingKey.consumeClick()) {
                if (client.player != null) {
                    boolean isRecording = clientPathManager.isRecording();
                    boolean useServer = clientPathManager.shouldUseServerRecording();
                    
                    LOGGER.info("Recording key (R) pressed: isRecording={}, useServerRecording={}", isRecording, useServer);

                    if (isRecording) {
                        if (useServer) {
                            clientPathManager.sendStopRecordingRequest(true);
                            client.player.sendOverlayMessage(Component.literal("Stopping server-side recording...").withStyle(ChatFormatting.GREEN));
                        } else {
                            clientPathManager.stopRecordingLocal();
                            client.player.sendOverlayMessage(Component.literal("Stopped local recording.").withStyle(ChatFormatting.GREEN));
                        }
                    } else {
                        if (useServer) {
                            LOGGER.info("Keybinding: Using SERVER recording");
                            clientPathManager.sendStartRecordingRequest(null);
                            client.player.sendOverlayMessage(Component.literal("Started server-side recording.").withStyle(ChatFormatting.GREEN));
                        } else {
                            LOGGER.info("Keybinding: Using LOCAL recording");
                            clientPathManager.startRecordingLocal();
                            client.player.sendOverlayMessage(Component.literal("Started local recording.").withStyle(ChatFormatting.GREEN));
                        }
                    }
                }
            }

            while (cycleRenderModeKey.consumeClick()) {
                if (client.player != null) {
                    LOGGER.info("Cycle render mode key pressed.");
                    renderSettingsManager.cycleRenderMode();
                }
            }

            while (openMenuKey.consumeClick()) {
                if (client.player != null) {
                    client.setScreenAndShow(new com.trailblazer.fabric.ui.MainMenuScreen(clientPathManager, renderSettingsManager));
                }
            }
        });
    }
}

