package com.trailblazer.fabric.ui;

import com.trailblazer.api.PathData;
import com.trailblazer.fabric.ClientPathManager;
import com.trailblazer.fabric.ClientPathManager.PathOrigin;
import com.trailblazer.fabric.networking.payload.c2s.UpdatePathMetadataPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.List;

public class PathCreationScreen extends Screen {
    private final ClientPathManager pathManager;
    private final Consumer<PathData> onSave;
    private final PathData editingPath;
    private Screen parentScreen = null;
    private EditBox nameField;
    private Button saveButton;
    private Button cancelButton;
    private Button cycleColorButton;
    private EditBox hexColorField;
    private int workingColor = 0;

    public PathCreationScreen(ClientPathManager pathManager, Consumer<PathData> onSave) {
        this(pathManager, onSave, (PathData) null);
    }

    public PathCreationScreen(ClientPathManager pathManager, Consumer<PathData> onSave, PathData editingPath) {
        super(editingPath == null ? Component.literal("Create New Path") : Component.literal("Edit Path"));
        this.pathManager = pathManager;
        this.onSave = onSave;
        this.editingPath = editingPath;
    }

    /**
     * Parent-aware constructor. When parent is non-null, Save/Cancel will return to the parent screen
     * instead of closing the UI entirely.
     */
    public PathCreationScreen(ClientPathManager pathManager, Consumer<PathData> onSave, PathData editingPath, Screen parent) {
        this(pathManager, onSave, editingPath);
        this.parentScreen = parent;
    }

    @Override
    protected void init() {
        super.init();
        int fieldWidth = 200;
        int fieldHeight = 20;
        int fieldX = this.width / 2 - fieldWidth / 2;
        int fieldY = this.height / 2 - fieldHeight / 2;

        nameField = new EditBox(this.font, fieldX, fieldY, fieldWidth, fieldHeight, Component.literal("Path Name"));
        if (editingPath != null) {
            nameField.setValue(editingPath.getPathName());
            workingColor = editingPath.getColorArgb();
        }
        this.addRenderableWidget(nameField);

        int buttonWidth = 90;
        int buttonHeight = 20;
        int buttonY = fieldY + fieldHeight + 10;

        cycleColorButton = Button.builder(Component.literal(colorButtonLabel()), button -> {
            java.util.List<Integer> palette = com.trailblazer.api.PathColors.palette();
            if (workingColor == 0) {
                workingColor = palette.get(0);
            } else {
                int idx = 0;
                for (int i = 0; i < palette.size(); i++) {
                    if (palette.get(i) == workingColor) { idx = i; break; }
                }
                workingColor = palette.get((idx + 1) % palette.size());
            }
            cycleColorButton.setMessage(Component.literal(colorButtonLabel()));
            if (hexColorField != null) {
                hexColorField.setValue(String.format("#%06X", workingColor & 0xFFFFFF));
            }
        }).bounds(this.width / 2 - buttonWidth - 5, buttonY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(cycleColorButton);

        hexColorField = new EditBox(this.font, this.width / 2 + 5, buttonY, buttonWidth, buttonHeight, Component.literal("#RRGGBB"));
        if (workingColor != 0) {
            hexColorField.setValue(String.format("#%06X", workingColor & 0xFFFFFF));
        }
        this.addRenderableWidget(hexColorField);

        buttonY += 30;

        saveButton = Button.builder(Component.literal("Save"), button -> {
            String name = nameField.getValue();
            if (name.isEmpty()) {
                name = "New Path " + System.currentTimeMillis();
            }

            String hex = hexColorField.getValue();
            java.util.Optional<Integer> parsed = com.trailblazer.api.PathColors.parse(hex);
            if (parsed.isPresent()) {
                workingColor = parsed.get();
            }

            if (editingPath != null) {
                editingPath.setPathName(name);
                if (workingColor != 0) {
                    editingPath.setColorArgb(workingColor);
                }
                onSave.accept(editingPath);
                PathOrigin origin = pathManager.getPathOrigin(editingPath.getPathId());
                if (origin == PathOrigin.SERVER_OWNED && ClientPlayNetworking.canSend(UpdatePathMetadataPayload.TYPE)) {
                    int colorToSend = editingPath.getColorArgb();
                    ClientPlayNetworking.send(new UpdatePathMetadataPayload(editingPath.getPathId(), editingPath.getPathName(), colorToSend));
                }
            } else {
                UUID ownerUuid = pathManager.getLocalPlayerUuid() != null ? pathManager.getLocalPlayerUuid() : UUID.randomUUID();
                String ownerName = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.getGameProfile().name()
                    : "Player";
                String dimension = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.dimension().identifier().toString()
                    : "minecraft:overworld";
                PathData newPath = new PathData(UUID.randomUUID(), name, ownerUuid, ownerName, System.currentTimeMillis(), dimension, List.of());
                if (workingColor != 0) {
                    newPath.setColorArgb(workingColor);
                }
                onSave.accept(newPath);
            }
            if (this.parentScreen != null) {
                this.minecraft.setScreenAndShow(this.parentScreen);
            } else {
                this.minecraft.setScreenAndShow(null);
            }
        }).bounds(this.width / 2 - buttonWidth - 5, buttonY, buttonWidth, buttonHeight).build();

        cancelButton = Button.builder(Component.literal("Cancel"), button -> {
            if (this.parentScreen != null) {
                this.minecraft.setScreenAndShow(this.parentScreen);
            } else {
                this.minecraft.setScreenAndShow(null);
            }
        }).bounds(this.width / 2 + 5, buttonY, buttonWidth, buttonHeight).build();

        this.addRenderableWidget(saveButton);
        this.addRenderableWidget(cancelButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    private String colorButtonLabel() {
        int color = workingColor;
        if (color == 0 && editingPath != null) {
            color = editingPath.getColorArgb();
        }
        if (color == 0) {
            return "Color: (auto)";
        }
        return "Color: " + com.trailblazer.api.PathColors.nameOrHex(color);
    }
}
