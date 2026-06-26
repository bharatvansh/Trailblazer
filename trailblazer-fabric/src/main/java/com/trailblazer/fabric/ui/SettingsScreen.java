package com.trailblazer.fabric.ui;

import com.trailblazer.fabric.RenderSettingsManager;
import com.trailblazer.fabric.rendering.RenderMode;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public class SettingsScreen extends Screen {
    private final RenderSettingsManager renderSettingsManager;
    private final Screen parent;

    public SettingsScreen(RenderSettingsManager renderSettingsManager, Screen parent) {
        super(Component.literal("Trailblazer Settings"));
        this.renderSettingsManager = renderSettingsManager;
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 200;
        int buttonHeight = 20;
        int buttonX = this.width / 2 - buttonWidth / 2;
        int buttonY = this.height / 2 - 40;

        Button renderModeButton = Button.builder(renderModeLabel(), button -> {
            RenderMode currentMode = renderSettingsManager.getRenderMode();
            RenderMode nextMode = currentMode.next();
            renderSettingsManager.setRenderMode(nextMode);
            button.setMessage(renderModeLabel());
        }).bounds(buttonX, buttonY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(renderModeButton);

        this.addRenderableWidget(Button.builder(Component.literal("Configure Keybindings"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreenAndShow(new KeyBindsScreen(this.parent, this.minecraft.options));
            }
        }).bounds(buttonX, buttonY + 30, buttonWidth, buttonHeight).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            this.minecraft.setScreenAndShow(parent);
        }).bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
    }

    private Component renderModeLabel() {
        return Component.literal("Render Mode: " + renderSettingsManager.getRenderMode().getDisplayText().getString());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Avoid calling renderBackground explicitly; base Screen handles blur.
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }
}
