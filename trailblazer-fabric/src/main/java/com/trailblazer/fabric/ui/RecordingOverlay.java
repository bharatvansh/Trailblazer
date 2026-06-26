package com.trailblazer.fabric.ui;

import com.trailblazer.fabric.ClientPathManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;

/** Simple HUD overlay for recording indicator. */
public class RecordingOverlay implements HudElement {
    private final ClientPathManager pathManager;
    public RecordingOverlay(ClientPathManager mgr) { this.pathManager = mgr; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        if (!pathManager.isRecording()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        var recordingPath = pathManager.getRecordingPath();
        String label = "Recording Path: " + (recordingPath != null ? recordingPath.getPathName() : "...") +
                " (" + (recordingPath != null ? recordingPath.getPoints().size() : 0) + ")";
        context.text(client.font, Component.literal(label), 8, 8 + client.font.lineHeight, 0xFFFF5555);
    }
}
