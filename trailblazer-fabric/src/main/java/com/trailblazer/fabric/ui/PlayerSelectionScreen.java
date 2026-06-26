package com.trailblazer.fabric.ui;

import com.trailblazer.api.PathData;
import com.trailblazer.fabric.sharing.PathShareSender;
import com.trailblazer.fabric.networking.payload.c2s.SharePathRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerSelectionScreen extends Screen {
    private final PathData path;
    private final Screen parent;
    private final List<PlayerInfo> onlinePlayers;
    private final List<UUID> selectedPlayers = new ArrayList<>();
    private Button shareButton;
    private final net.minecraft.network.chat.Component shareDisabledTooltipPlugin = net.minecraft.network.chat.Component.literal("Server-side plugin required");
    private final net.minecraft.network.chat.Component shareDisabledTooltipSelection = net.minecraft.network.chat.Component.literal("Select at least one player");
    private final net.minecraft.network.chat.Component shareDisabledTooltipNoPlayers = net.minecraft.network.chat.Component.literal("No players online");

    public PlayerSelectionScreen(PathData path, Screen parent) {
        super(net.minecraft.network.chat.Component.literal("Share Path with Players"));
        this.path = path;
        this.parent = parent;
        var handler = Minecraft.getInstance().getConnection();
        this.onlinePlayers = handler != null ? new ArrayList<>(handler.getOnlinePlayers()) : new ArrayList<>();
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 200;
        int buttonHeight = 20;
        int buttonX = this.width / 2 - buttonWidth / 2;
        int y = Math.max(40, this.height / 2 - (onlinePlayers.size() * (buttonHeight + 5)) / 2);

        for (PlayerInfo playerEntry : onlinePlayers) {
            UUID playerUUID = playerEntry.getProfile().id();
            Button playerButton = Button.builder(net.minecraft.network.chat.Component.literal(playerEntry.getProfile().name()), button -> {
                if (selectedPlayers.contains(playerUUID)) {
                    selectedPlayers.remove(playerUUID);
                    button.setMessage(net.minecraft.network.chat.Component.literal(playerEntry.getProfile().name()));
                } else {
                    selectedPlayers.add(playerUUID);
                    button.setMessage(net.minecraft.network.chat.Component.literal("[X] " + playerEntry.getProfile().name()));
                }
                updateShareState();
            }).bounds(buttonX, y, buttonWidth, buttonHeight).build();
            this.addRenderableWidget(playerButton);
            y += buttonHeight + 5;
        }

        if (onlinePlayers.isEmpty()) {
            this.addRenderableWidget(Button.builder(net.minecraft.network.chat.Component.literal("No players online"), button -> {})
                .bounds(buttonX, y, buttonWidth, buttonHeight)
                .build()).active = false;
        }

        boolean canShare = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SharePathRequestPayload.TYPE);
        shareButton = Button.builder(net.minecraft.network.chat.Component.literal("Share"), button -> {
            if (!selectedPlayers.isEmpty()) {
                PathShareSender.sharePath(path, selectedPlayers);
            }
            this.minecraft.setScreenAndShow(parent);
        }).bounds(buttonX, this.height - 65, buttonWidth, buttonHeight).build();
        shareButton.active = canShare && !selectedPlayers.isEmpty() && !onlinePlayers.isEmpty();
        this.addRenderableWidget(shareButton);

        this.addRenderableWidget(Button.builder(net.minecraft.network.chat.Component.literal("Cancel"), button -> this.minecraft.setScreenAndShow(parent))
            .bounds(buttonX, this.height - 40, buttonWidth, buttonHeight).build());
    }

    private void updateShareState() {
        if (shareButton != null) {
            boolean canShare = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SharePathRequestPayload.TYPE);
            shareButton.active = canShare && !selectedPlayers.isEmpty() && !onlinePlayers.isEmpty();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Avoid triggering the vanilla blur twice in a single frame.
        // We draw a light translucent backdrop ourselves (same pattern as MainMenuScreen)
        // to keep readability when opened over the in-game world.
        context.fill(0, 0, this.width, this.height, 0x26000000);
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // tooltip for disabled share button when no server-side support
        if (!shareButton.active) {
            int sx = shareButton.getX();
            int sy = shareButton.getY();
            int sw = shareButton.getWidth();
            int sh = shareButton.getHeight();
            if (mouseX >= sx && mouseX <= sx + sw && mouseY >= sy && mouseY <= sy + sh) {
                boolean canShare = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SharePathRequestPayload.TYPE);
                net.minecraft.network.chat.Component tip = !canShare
                        ? shareDisabledTooltipPlugin
                        : (onlinePlayers.isEmpty() ? shareDisabledTooltipNoPlayers : shareDisabledTooltipSelection);
                context.setTooltipForNextFrame(this.font, tip, mouseX, mouseY);
            }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Only invoke the vanilla background (which applies a per-frame blur) when not in a world.
        // When in-game, we rely on our own translucent fill to avoid the "Can only blur once per frame" crash
        // that occurs when two screens request a blur during the same frame.
        if (this.minecraft == null || this.minecraft.level == null) {
            super.extractBackground(context, mouseX, mouseY, delta);
        }
    }
}
