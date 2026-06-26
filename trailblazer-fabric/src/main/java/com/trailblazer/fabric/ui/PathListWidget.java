package com.trailblazer.fabric.ui;

import com.trailblazer.api.PathData;
import com.trailblazer.fabric.ClientPathManager;
import com.trailblazer.fabric.ClientPathManager.PathOrigin;
import com.trailblazer.fabric.networking.payload.c2s.SharePathRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.List;

public class PathListWidget extends ContainerObjectSelectionList<PathListWidget.PathEntry> {
    private double targetScrollAmount = -1.0;
    private double scrollVelocity = 0.0;
    private int lastMouseX;
    private int lastMouseY;
    private float lastTickDelta;
    private static final double FRICTION_PER_SEC = 1.2;
    private static final double MIN_VELOCITY = 0.05;
    private static final double MAX_VELOCITY = 500.0;

    private static final int CONTAINER_BG      = 0x2E000000;
    private static final int ROW_BG            = 0x50000000;
    private static final int ROW_BG_HOVER      = 0x5A000000;
    private static final int SEPARATOR         = 0x40FFFFFF;

    public PathListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
        super(client, width, height, top, Math.max(itemHeight, 36));
    }

    @Override
    protected void extractListBackground(GuiGraphicsExtractor context) {
        // Suppressed - using custom background rendering
    }

    @Override
    protected void extractListItems(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.lastTickDelta = delta;

        int left = 0;
        int right = this.width;
        int top = this.getY();
        int bottom = this.getY() + this.getHeight();
        context.fill(left, top, right, bottom, CONTAINER_BG);
        context.fill(left, top, right, top + 1, SEPARATOR);
        context.fill(left, bottom - 1, right, bottom, SEPARATOR);

        if (targetScrollAmount < 0.0) {
            targetScrollAmount = this.scrollAmount();
        }

        double dt = Math.max(0.0, Math.min(delta, 1.0));
        if (scrollVelocity != 0.0) {
            targetScrollAmount += scrollVelocity * dt;
            double decay = Math.exp(-FRICTION_PER_SEC * dt);
            scrollVelocity *= decay;
            if (Math.abs(scrollVelocity) < 80.0) scrollVelocity *= 0.80;
            if (Math.abs(scrollVelocity) < MIN_VELOCITY) scrollVelocity = 0.0;
        }

        double max = this.maxScrollAmount();
        if (targetScrollAmount <= 0.0) {
            targetScrollAmount = 0.0;
            if (scrollVelocity < 0) scrollVelocity *= -0.2;
        } else if (targetScrollAmount >= max) {
            targetScrollAmount = max;
            if (scrollVelocity > 0) scrollVelocity *= -0.2;
        }
        this.setScrollAmount(targetScrollAmount);
        this.enableScissor(context);
        super.extractListItems(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!this.isMouseOver(mouseX, mouseY)) return false;
        if (targetScrollAmount < 0.0) targetScrollAmount = this.scrollAmount();
        double base = Math.max(this.defaultEntryHeight * 0.35, 8.0);
        double deltaV = -verticalAmount * base * 1.2;
        if (deltaV > 180.0) deltaV = 180.0;
        if (deltaV < -180.0) deltaV = -180.0;
        scrollVelocity += deltaV;
        if (scrollVelocity > MAX_VELOCITY) scrollVelocity = MAX_VELOCITY;
        if (scrollVelocity < -MAX_VELOCITY) scrollVelocity = -MAX_VELOCITY;
        return true;
    }

    @Override
    public int getRowTop(int index) {
        return super.getRowTop(index) - 3;
    }

    @Override
    public int getRowBottom(int index) {
        return super.getRowBottom(index) - 3;
    }

    @Override
    public int getRowWidth() {
        return this.width - 20;
    }

    public void clearEntries() {
        super.clearEntries();
    }

    public int addEntry(PathEntry entry) {
        return super.addEntry(entry);
    }

    public class PathEntry extends ContainerObjectSelectionList.Entry<PathEntry> {
        private final PathData path;
        private final ClientPathManager pathManager;
        private final boolean isMyPath;
        private final PathOrigin origin;
        private final Button toggleButton;
        private final Button shareButton;
        private final Component shareDisabledTooltip;
        private final Button editButton;
        private final Button deleteButton;
        private boolean awaitingDeleteConfirm = false;
        private long deleteConfirmStartMs = 0L;
        private static final long CONFIRM_TIMEOUT_MS = 5000L;

        public PathEntry(PathData path, ClientPathManager pathManager, boolean isMyPath) {
            this.path = path;
            this.pathManager = pathManager;
            this.isMyPath = isMyPath;
            this.origin = pathManager.getPathOrigin(path.getPathId());

            this.toggleButton = Button.builder(getToggleButtonText(), button -> {
                pathManager.togglePathVisibility(path.getPathId());
                button.setMessage(getToggleButtonText());
            }).build();

            this.shareButton = Button.builder(Component.literal("Share"), button -> {
                Minecraft.getInstance().setScreenAndShow(new PlayerSelectionScreen(path, Minecraft.getInstance().gui.screen()));
            }).build();
            boolean canSend = ClientPlayNetworking.canSend(SharePathRequestPayload.TYPE);
            boolean originAllows = (origin == PathOrigin.LOCAL || origin == PathOrigin.SERVER_OWNED);
            boolean canShare = canSend && originAllows;
            this.shareButton.active = canShare;
            if (!canSend) {
                this.shareDisabledTooltip = Component.literal("Server-side plugin required");
            } else {
                this.shareDisabledTooltip = null;
            }
 
            this.editButton = Button.builder(Component.literal("Edit"), button -> {
                Minecraft.getInstance().setScreenAndShow(new PathCreationScreen(pathManager, updatedPath -> {
                    pathManager.onPathUpdated(updatedPath);
                }, path, Minecraft.getInstance().gui.screen()));
            }).build();
            if (!(origin == PathOrigin.LOCAL || origin == PathOrigin.SERVER_OWNED)) {
                this.editButton.active = false;
                this.editButton.setMessage(Component.literal("View"));
            }

            this.deleteButton = Button.builder(Component.literal("Delete"), button -> {
                long now = System.currentTimeMillis();
                if (!awaitingDeleteConfirm || now - deleteConfirmStartMs > CONFIRM_TIMEOUT_MS) {
                    awaitingDeleteConfirm = true;
                    deleteConfirmStartMs = now;
                    button.setMessage(Component.literal("Confirm"));
                    return;
                }
                awaitingDeleteConfirm = false;
                switch (origin) {
                    case LOCAL -> {
                        pathManager.deletePath(path.getPathId());
                        button.setMessage(Component.literal("Delete"));
                    }
                    case SERVER_OWNED -> {
                        if (ClientPlayNetworking.canSend(com.trailblazer.fabric.networking.payload.c2s.DeletePathPayload.TYPE)) {
                            ClientPlayNetworking.send(
                                    new com.trailblazer.fabric.networking.payload.c2s.DeletePathPayload(path.getPathId()));
                        }
                        pathManager.removeServerPath(path.getPathId());
                        button.setMessage(Component.literal("Delete"));
                    }
                    case SERVER_SHARED -> {
                        pathManager.removeSharedPath(path.getPathId());
                        button.setMessage(Component.literal("Remove"));
                    }
                }
            }).build();
            if (origin == PathOrigin.SERVER_SHARED) {
                this.deleteButton.setMessage(Component.literal("Remove"));
            }
            if (origin == PathOrigin.SERVER_SHARED) {
                this.shareButton.active = false;
            }
        }

        @Override
        public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            tickDelta = PathListWidget.this.lastTickDelta;

            int rowLeft = this.getX();
            int rowTop = this.getY();
            int rowRight = rowLeft + this.getWidth();
            int rowBottom = rowTop + this.getHeight();

            int bgLeft = rowLeft + 4;
            int bgRight = rowRight - 4;

            int bgColor = hovered ? ROW_BG_HOVER : ROW_BG;
            context.fill(bgLeft, rowTop, bgRight, rowBottom, bgColor);
            context.fill(bgLeft, rowBottom - 1, bgRight, rowBottom, SEPARATOR);

            final int topPadding = 4;
            final int buttonYOffset = topPadding;
            final int textBaselineY = rowTop + topPadding + 5;
            int baseX = bgLeft + 4;

            if (origin != null) {
                int badgeColor = path.getColorArgb();
                context.fill(baseX, textBaselineY - 1, baseX + 8, textBaselineY - 1 + 8, badgeColor);
                if (mouseX >= baseX && mouseX <= baseX + 8 && mouseY >= textBaselineY - 1 && mouseY <= textBaselineY - 1 + 8) {
                    context.setTooltipForNextFrame(Minecraft.getInstance().font, getOriginTooltipText(), mouseX, mouseY);
                }
                baseX += 12;
            }

            int buttonWidth = 60;
            int buttonHeight = 18;
            int buttonSpacing = buttonWidth + 8;
            int buttonAreaWidth = isMyPath ? (buttonSpacing * 4) : (buttonSpacing * 2);
            int availableTextWidth = (bgRight - bgLeft) - (baseX - bgLeft) - buttonAreaWidth - 8;
            var font = Minecraft.getInstance().font;
            String displayName = path.getPathName();
            if (font.width(displayName) > availableTextWidth) {
                displayName = font.plainSubstrByWidth(displayName, availableTextWidth - font.width("...")) + "...";
            }
            context.text(font, displayName, baseX, textBaselineY, 0xFFFFFFFF, false);
            if (!isMyPath || origin == PathOrigin.SERVER_SHARED) {
                String ownerName = null;
                String ownerText = null;
                
                if (origin == PathOrigin.SERVER_SHARED) {
                    ownerName = path.getOriginOwnerName();
                    if (ownerName != null && !ownerName.isBlank()) {
                        ownerText = " (shared by " + ownerName + ")";
                    }
                } else {
                    ownerName = path.getOwnerName();
                    if (ownerName != null && !ownerName.isBlank()) {
                        ownerText = " (by " + ownerName + ")";
                    }
                }
                
                if (ownerText != null) {
                    int ownerX = baseX + font.width(displayName);
                    int remainingWidth = availableTextWidth - font.width(displayName);
                    if (font.width(ownerText) > remainingWidth) {
                        ownerText = font.plainSubstrByWidth(ownerText, remainingWidth - font.width("...")) + "...";
                    }
                    context.text(font, ownerText, ownerX, textBaselineY, 0xFF999999, false);
                }
            }

            int buttonX = bgRight - (buttonWidth + 2);
            int buttonY = rowTop + buttonYOffset;
            toggleButton.setX(buttonX); toggleButton.setY(buttonY); toggleButton.setWidth(buttonWidth); toggleButton.setHeight(buttonHeight);
            toggleButton.extractRenderState(context, mouseX, mouseY, tickDelta);
            if (isMyPath) {
                buttonX -= buttonSpacing; shareButton.setX(buttonX); shareButton.setY(buttonY); shareButton.setWidth(buttonWidth); shareButton.setHeight(buttonHeight); shareButton.extractRenderState(context, mouseX, mouseY, tickDelta);
                buttonX -= buttonSpacing; editButton.setX(buttonX); editButton.setY(buttonY); editButton.setWidth(buttonWidth); editButton.setHeight(buttonHeight); editButton.extractRenderState(context, mouseX, mouseY, tickDelta);
            }
            buttonX -= buttonSpacing; deleteButton.setX(buttonX); deleteButton.setY(buttonY); deleteButton.setWidth(buttonWidth); deleteButton.setHeight(buttonHeight); deleteButton.extractRenderState(context, mouseX, mouseY, tickDelta);

            int coordMaxY = rowBottom - font.lineHeight - 1;
            int originalBaseline = rowBottom - 1 - 3 - font.lineHeight;
            int coordBaseline = Math.min(originalBaseline + 2, coordMaxY);
            if (coordBaseline >= rowTop + topPadding) {
                drawCoordinates(context, baseX, bgRight - 10, coordBaseline, font);
            }

            if (awaitingDeleteConfirm && System.currentTimeMillis() - deleteConfirmStartMs > CONFIRM_TIMEOUT_MS) {
                awaitingDeleteConfirm = false;
                deleteButton.setMessage(origin == PathOrigin.SERVER_SHARED ? Component.literal("Remove") : Component.literal("Delete"));
            }

            if (isMyPath && shareDisabledTooltip != null) {
                int sx = shareButton.getX();
                int sy = shareButton.getY();
                int sw = shareButton.getWidth();
                int sh = shareButton.getHeight();
                if (mouseX >= sx && mouseX <= sx + sw && mouseY >= sy && mouseY <= sy + sh) {
                    context.setTooltipForNextFrame(Minecraft.getInstance().font, shareDisabledTooltip, mouseX, mouseY);
                }
            }
        }

        private Component getToggleButtonText() {
            boolean isVisible = pathManager.isPathVisible(path.getPathId());
            return Component.literal("Toggle: " + (isVisible ? "ON" : "OFF"))
                .withStyle(isVisible ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED);
        }

        @Override
        public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
            if (isMyPath) {
                return List.of(toggleButton, shareButton, editButton, deleteButton);
            }
            return List.of(toggleButton, deleteButton);
        }

        @Override
        public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
            if (isMyPath) {
                return List.of(toggleButton, shareButton, editButton, deleteButton);
            }
            return List.of(toggleButton, deleteButton);
        }

        private Component getOriginTooltipText() {
            return switch (origin) {
                case LOCAL -> Component.literal("Stored on this client");
                case SERVER_OWNED -> Component.literal("Provided by server (your copy)");
                case SERVER_SHARED -> Component.literal("Live server share (read-only)");
            };
        }

        private void drawCoordinates(GuiGraphicsExtractor context, int baseX, int contentRightX, int yCoord, net.minecraft.client.gui.Font tr) {
            if (path.getPoints().isEmpty()) return;
            var startPoint = path.getPoints().get(0);
            var endPoint = path.getPoints().get(path.getPoints().size() - 1);
            String startText = String.format("Start: %.0f, %.0f, %.0f", startPoint.getX(), startPoint.getY(), startPoint.getZ());
            String endText = String.format("End: %.0f, %.0f, %.0f", endPoint.getX(), endPoint.getY(), endPoint.getZ());
            if (contentRightX <= baseX) return;
            int maxWidth = contentRightX - baseX;
            if (maxWidth <= 70) return;

            String distanceText = "";
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                double dx = client.player.getX() - startPoint.getX();
                double dy = client.player.getY() - startPoint.getY();
                double dz = client.player.getZ() - startPoint.getZ();
                int distance = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
                distanceText = distance + " blocks away";
            }

            int distanceWidth = tr.width(distanceText);
            int endWidth = tr.width(endText);
            int availableForStart = maxWidth - endWidth - distanceWidth - 16;
            if (availableForStart < 40) return;

            if (tr.width(startText) > availableForStart) {
                startText = tr.plainSubstrByWidth(startText, Math.max(availableForStart - tr.width("..."), 0)) + "...";
            }

            int startWidth = tr.width(startText);
            int startX = baseX;
            int endX = contentRightX - endWidth;

            int distanceX = baseX + (maxWidth - distanceWidth) / 2;
            int minDistanceX = startX + startWidth + 4;
            int maxDistanceX = endX - distanceWidth - 4;
            if (distanceX < minDistanceX) {
                distanceX = minDistanceX;
            } else if (distanceX > maxDistanceX) {
                distanceX = maxDistanceX;
            }

            context.text(tr, startText, startX, yCoord, 0xFFB0B0B0, false);
            context.text(tr, distanceText, distanceX, yCoord, 0xFF909090, false);
            context.text(tr, endText, endX, yCoord, 0xFFB0B0B0, false);
        }
    }
}
