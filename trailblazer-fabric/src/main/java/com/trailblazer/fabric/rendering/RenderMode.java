package com.trailblazer.fabric.rendering;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Path visualization modes.
 */
public enum RenderMode {
    SOLID_LINE("Solid Line", ChatFormatting.GREEN),
    DASHED_LINE("Dashed Line", ChatFormatting.GREEN),
    SPACED_MARKERS("Spaced Markers", ChatFormatting.YELLOW),
    DIRECTIONAL_ARROWS("Directional Arrows", ChatFormatting.AQUA);

    private final String displayName;
    private final ChatFormatting displayColor;

    RenderMode(String displayName, ChatFormatting displayColor) {
        this.displayName = displayName;
        this.displayColor = displayColor;
    }

    public RenderMode next() {
        RenderMode[] values = values();
        int nextOrdinal = (this.ordinal() + 1) % values.length;
        return values[nextOrdinal];
    }

    public Component getDisplayText() {
        return Component.literal(this.displayName).withStyle(this.displayColor);
    }
}