package dev.sgsim.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * The tab strip along the top of the simulator screens. Each tab is its own Screen; switching
 * replaces the screen, and the open key brings back whichever tab was used last.
 */
public final class Tabs {
    private Tabs() {}

    /** Height of the strip; screens start their content below {@link #CONTENT_TOP}. */
    public static final int HEIGHT = 14;
    public static final int CONTENT_TOP = HEIGHT + 4;
    private static final int X = 6;

    public enum Tab {
        GEAR("sgsim.tab.gear", SimulatorScreen::new),
        ALLOYS("sgsim.tab.alloys", AlloyScreen::new);

        final String key;
        final Supplier<Screen> factory;

        Tab(String key, Supplier<Screen> factory) {
            this.key = key;
            this.factory = factory;
        }
    }

    private static Tab last = Tab.GEAR;

    /** The screen for the tab the player used last. */
    public static Screen openLast() {
        return last.factory.get();
    }

    public static void render(GuiGraphics g, Font font, Tab active, int mx, int my) {
        last = active;
        int x = X;
        for (Tab tab : Tab.values()) {
            Component label = Component.translatable(tab.key);
            int w = font.width(label) + 16;
            boolean on = tab == active;
            boolean hover = !on && mx >= x && mx < x + w && my >= 2 && my < 2 + HEIGHT;
            g.fill(x, 2, x + w, 2 + HEIGHT, on ? 0xE0303048 : (hover ? 0xC0282830 : 0xA0101018));
            g.renderOutline(x, 2, w, HEIGHT + (on ? 1 : 0), on ? 0x80FFFFFF : 0x40FFFFFF);
            g.drawCenteredString(font, label, x + w / 2, 5, on ? 0xFFFFA0 : (hover ? 0xFFFFFF : 0xAAAAAA));
            x += w + 2;
        }
        g.fill(X, 2 + HEIGHT, g.guiWidth() - X, 3 + HEIGHT, 0x40FFFFFF);
    }

    /** Switches tab if the click hit another one; returns true if the click was on the strip. */
    public static boolean click(Minecraft mc, Font font, Tab active, double mx, double my) {
        if (my < 2 || my >= 2 + HEIGHT) return false;
        int x = X;
        for (Tab tab : Tab.values()) {
            int w = font.width(Component.translatable(tab.key)) + 16;
            if (mx >= x && mx < x + w) {
                if (tab != active) {
                    last = tab;
                    mc.setScreen(tab.factory.get());
                }
                return true;
            }
            x += w + 2;
        }
        return false;
    }
}
