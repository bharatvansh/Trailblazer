package com.trailblazer.fabric;

import com.trailblazer.fabric.rendering.RenderMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Manages client-side path rendering settings.
 */
public class RenderSettingsManager {

    private RenderMode currentMode = RenderMode.SOLID_LINE;
    private double markerSpacing = 1.0;

    public RenderMode getRenderMode() {
        return currentMode;
    }

    public void setRenderMode(RenderMode mode) {
        if (mode == null || mode == this.currentMode) {
            return;
        }
        this.currentMode = mode;
        notifyModeChanged();
    }

    public double getMarkerSpacing() {
        return markerSpacing;
    }

    public void cycleRenderMode() {
        currentMode = currentMode.next();
        notifyModeChanged();
    }

    private void notifyModeChanged() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Component title = Component.literal("Render Mode");
            Component description = currentMode.getDisplayText();
            client.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, description));
        }
    }
}