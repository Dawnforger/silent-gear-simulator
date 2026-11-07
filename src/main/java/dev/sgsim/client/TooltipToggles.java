package dev.sgsim.client;

import net.minecraft.client.Minecraft;

/**
 * Checkbox states that keep Silent Gear's tooltip sections open while the simulator is on screen,
 * so the player doesn't have to hold Ctrl / Shift / Alt. Read by {@link dev.sgsim.mixin.KeyTrackerMixin}.
 */
public final class TooltipToggles {
    public static boolean properties = true;
    public static boolean traits = false;
    public static boolean construction = false;

    private TooltipToggles() {}

    /** Only while our screen is open; Silent Gear behaves normally everywhere else. */
    public static boolean active() {
        return Minecraft.getInstance().screen instanceof SimulatorScreen;
    }
}
