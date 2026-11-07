package dev.sgsim.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * A small scrollable, searchable list that pops up over the simulator: used for choosing the gear
 * item and for choosing a part's material. Optionally sortable by a numeric value (stats).
 * Owned and driven by {@link SimulatorScreen}; it only draws itself and answers hit tests.
 */
final class ListPopup<T> {
    static final int ROW_H = 18;
    static final int MAX_ROWS = 10;
    /** Drawn above the main screen's items, below tooltips (which render at z 400). */
    static final int Z = 200;

    /** One entry. {@code value == null} is the "None" entry, always kept at the top. */
    record Row<T>(@Nullable T value, ItemStack icon, Component name, String searchText,
                  @Nullable String tag, int tagColor) {
        static <T> Row<T> of(@Nullable T value, ItemStack icon, Component name, String extraSearch,
                             @Nullable String tag, int tagColor) {
            String search = (name.getString() + " " + extraSearch).toLowerCase(Locale.ROOT);
            return new Row<>(value, icon, name, search, tag, tagColor);
        }
    }

    /** A sort choice: a stat name and how to read that stat for a row (NaN = not applicable). */
    record Sort<T>(String key, Component name, ToDoubleFunction<T> value) {}

    enum Click { OUTSIDE, CONSUMED, PICKED }

    final EditBox search;
    final int x, y, w, h;

    private final List<Row<T>> rows;
    private final List<Row<T>> filtered = new ArrayList<>();
    private final List<Sort<T>> sorts;
    @Nullable
    private final T current;
    private final int visibleRows;
    private int scroll;

    @Nullable
    private Sort<T> sort;
    private boolean descending;
    private boolean choosingSort;
    private final Map<T, Double> sortValues = new HashMap<>();
    @Nullable
    private Row<T> picked;

    ListPopup(Font font, List<Row<T>> rows, List<Sort<T>> sorts, @Nullable T current,
              @Nullable String sortKey, boolean descending,
              int anchorX, int anchorY, int width, int screenW, int screenH, Component searchHint) {
        this.rows = rows;
        this.sorts = sorts;
        this.current = current;
        this.descending = descending;
        for (Sort<T> s : sorts) if (s.key().equals(sortKey)) this.sort = s;

        int header = headerHeight();
        int maxRowsOnScreen = Math.max(3, (screenH - 8 - header - 3) / ROW_H);
        this.visibleRows = Mth.clamp(rows.size(), 3, Math.min(MAX_ROWS, maxRowsOnScreen));
        this.w = Math.min(width, screenW - 8);
        this.h = header + visibleRows * ROW_H + 3;
        this.x = Mth.clamp(anchorX, 4, Math.max(4, screenW - 4 - w));
        this.y = Mth.clamp(anchorY, 4, Math.max(4, screenH - 4 - h));

        this.search = new EditBox(font, x + 4, y + 5, w - 8, 16, searchHint);
        this.search.setHint(searchHint.copy().withStyle(ChatFormatting.DARK_GRAY));
        this.search.setResponder(text -> refilter());
        refilter();

        // Start scrolled so the current entry is visible
        for (int i = 0; i < filtered.size(); i++) {
            if (current != null && current.equals(filtered.get(i).value()) && i >= visibleRows) {
                scroll = Math.min(maxScroll(), i - visibleRows / 2);
            }
        }
    }

    private int headerHeight() {
        return sorts.isEmpty() ? 26 : 40;
    }

    @Nullable
    String sortKey() {
        return sort != null ? sort.key() : null;
    }

    boolean descending() {
        return descending;
    }

    /** The entry the player clicked (valid after {@link #click} returned PICKED). */
    @Nullable
    Row<T> picked() {
        return picked;
    }

    // ---------------------------------------------------------------------------------------------
    // Filtering and sorting

    private void refilter() {
        filtered.clear();
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        Row<T> none = null;
        for (Row<T> r : rows) {
            if (r.value() == null) {
                none = r;
                continue;
            }
            if (q.isEmpty() || r.searchText().contains(q)) filtered.add(r);
        }
        if (sort != null) {
            Comparator<Row<T>> byValue = Comparator.comparingDouble(r -> valueOf(r));
            if (descending) byValue = byValue.reversed();
            final Comparator<Row<T>> cmp = byValue;
            // Rows where the stat doesn't apply always go last
            filtered.sort((a, b) -> {
                boolean na = Double.isNaN(valueOf(a)), nb = Double.isNaN(valueOf(b));
                if (na != nb) return na ? 1 : -1;
                return cmp.compare(a, b);
            });
        }
        if (none != null) filtered.addFirst(none);
        scroll = 0;
    }

    private double valueOf(Row<T> row) {
        if (sort == null || row.value() == null) return Double.NaN;
        return sortValues.computeIfAbsent(row.value(), v -> {
            try {
                return sort.value().applyAsDouble(v);
            } catch (Throwable t) {
                return Double.NaN;
            }
        });
    }

    private void setSort(@Nullable Sort<T> newSort) {
        sort = newSort;
        sortValues.clear();
        choosingSort = false;
        refilter();
    }

    private static String format(double v) {
        if (Double.isNaN(v)) return "";
        if (Math.abs(v) >= 100) return String.format(Locale.ROOT, "%.0f", v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    // ---------------------------------------------------------------------------------------------
    // Layout / hit testing

    private int listCount() {
        return choosingSort ? sorts.size() + 1 : filtered.size();
    }

    int maxScroll() {
        return Math.max(0, listCount() - visibleRows);
    }

    void scroll(int direction) {
        scroll = Mth.clamp(scroll - direction, 0, maxScroll());
    }

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int listTop() {
        return y + headerHeight();
    }

    private boolean overSearch(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + 23;
    }

    private int sortButtonRight() {
        return x + w - 4 - 18;
    }

    private boolean overSortButton(double mx, double my) {
        return !sorts.isEmpty() && mx >= x + 4 && mx < sortButtonRight() && my >= y + 25 && my < y + 37;
    }

    private boolean overDirButton(double mx, double my) {
        return !sorts.isEmpty() && mx >= sortButtonRight() + 2 && mx < x + w - 4 && my >= y + 25 && my < y + 37;
    }

    /** Index into the visible list under the mouse, or -1. */
    private int indexAt(double mx, double my) {
        if (mx < x + 2 || mx >= x + w - 5) return -1;
        int top = listTop();
        if (my < top || my >= top + visibleRows * ROW_H) return -1;
        int index = scroll + (int) ((my - top) / ROW_H);
        return index < listCount() ? index : -1;
    }

    /** The entry under the mouse (only while showing entries, not the sort chooser). */
    @Nullable
    Row<T> rowAt(double mx, double my) {
        if (choosingSort) return null;
        int i = indexAt(mx, my);
        return i >= 0 ? filtered.get(i) : null;
    }

    /** First real entry in the current search results (for Enter). */
    @Nullable
    Row<T> firstMatch() {
        for (Row<T> r : filtered) if (r.value() != null) return r;
        return filtered.isEmpty() ? null : filtered.getFirst();
    }

    Click click(double mx, double my, int button) {
        if (!contains(mx, my)) return Click.OUTSIDE;
        if (overSearch(mx, my)) {
            search.mouseClicked(mx, my, button);
            return Click.CONSUMED;
        }
        if (overSortButton(mx, my)) {
            choosingSort = !choosingSort;
            scroll = 0;
            return Click.CONSUMED;
        }
        if (overDirButton(mx, my)) {
            descending = !descending;
            refilter();
            return Click.CONSUMED;
        }
        int i = indexAt(mx, my);
        if (i < 0) return Click.CONSUMED;
        if (choosingSort) {
            setSort(i == 0 ? null : sorts.get(i - 1));
            return Click.CONSUMED;
        }
        picked = filtered.get(i);
        return Click.PICKED;
    }

    @Nullable
    Component hoverHelp(double mx, double my) {
        if (overSortButton(mx, my)) return Component.translatable("sgsim.sort.tooltip");
        if (overDirButton(mx, my)) return Component.translatable(descending ? "sgsim.sort.desc" : "sgsim.sort.asc");
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering

    void render(GuiGraphics g, Font font, int mx, int my, float partialTick) {
        g.pose().pushPose();
        g.pose().translate(0, 0, Z);

        // Frame, styled like a vanilla tooltip
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF2A0B4F);
        g.fill(x, y, x + w, y + h, 0xF0100010);
        g.renderOutline(x, y, w, h, 0xFF5000FF);

        search.render(g, mx, my, partialTick);

        if (!sorts.isEmpty()) {
            Component label = Component.translatable("sgsim.sort",
                    sort != null ? sort.name() : Component.translatable("sgsim.sort.name"));
            smallButton(g, font, x + 4, y + 25, sortButtonRight() - (x + 4), label.getString() + " ▾",
                    overSortButton(mx, my), choosingSort ? 0xFFFF55 : 0xFFFFFF);
            smallButton(g, font, sortButtonRight() + 2, y + 25, x + w - 4 - (sortButtonRight() + 2),
                    descending ? "▼" : "▲", overDirButton(mx, my), 0xFFFFFF);
        }

        int top = listTop();
        int hoveredIndex = indexAt(mx, my);
        for (int i = 0; i < visibleRows; i++) {
            int index = scroll + i;
            if (index >= listCount()) break;
            int ry = top + i * ROW_H;
            boolean hover = index == hoveredIndex;
            if (choosingSort) {
                renderSortRow(g, font, index, ry, hover);
            } else {
                renderRow(g, font, filtered.get(index), ry, hover);
            }
        }

        if (maxScroll() > 0) {
            int trackH = visibleRows * ROW_H;
            int barH = Math.max(8, trackH * visibleRows / listCount());
            int barY = top + (trackH - barH) * scroll / maxScroll();
            g.fill(x + w - 4, top, x + w - 2, top + trackH, 0x40FFFFFF);
            g.fill(x + w - 4, barY, x + w - 2, barY + barH, 0xC0FFFFFF);
        }
        if (!choosingSort && filtered.isEmpty()) {
            g.drawString(font, Component.translatable("sgsim.popup.no_match"), x + 6, top + 5, 0x888888);
        }

        g.pose().popPose();
    }

    private void renderRow(GuiGraphics g, Font font, Row<T> row, int ry, boolean hover) {
        boolean isCurrent = current != null && current.equals(row.value());
        if (hover) g.fill(x + 2, ry, x + w - 5, ry + ROW_H, 0x60FFFFFF);
        else if (isCurrent) g.fill(x + 2, ry, x + w - 5, ry + ROW_H, 0x4055FF55);

        int textX = x + 23;
        int textRight = x + w - 8;
        if (row.value() == null) {
            g.drawString(font, "✕", x + 8, ry + 5, 0xFF5555);
        } else if (!row.icon().isEmpty()) {
            g.renderItem(row.icon(), x + 4, ry + 1);
        }
        if (row.tag() != null) {
            int tw = font.width(row.tag());
            g.drawString(font, row.tag(), textRight - tw, ry + 5, row.tagColor());
            textRight -= tw + 4;
        }
        if (sort != null && row.value() != null) {
            String v = format(valueOf(row));
            if (!v.isEmpty()) {
                int vw = font.width(v);
                g.drawString(font, v, textRight - vw, ry + 5, 0xFFFF55);
                textRight -= vw + 4;
            }
        }
        List<FormattedCharSequence> lines = font.split(row.name(), Math.max(10, textRight - textX));
        if (!lines.isEmpty()) {
            g.drawString(font, lines.getFirst(), textX, ry + 5, isCurrent ? 0x55FF55 : 0xFFFFFF);
        }
    }

    private void renderSortRow(GuiGraphics g, Font font, int index, int ry, boolean hover) {
        Sort<T> option = index == 0 ? null : sorts.get(index - 1);
        boolean selected = option == sort;
        if (hover) g.fill(x + 2, ry, x + w - 5, ry + ROW_H, 0x60FFFFFF);
        else if (selected) g.fill(x + 2, ry, x + w - 5, ry + ROW_H, 0x4055FF55);
        Component name = option != null ? option.name() : Component.translatable("sgsim.sort.name");
        List<FormattedCharSequence> lines = font.split(name, w - 16);
        if (!lines.isEmpty()) g.drawString(font, lines.getFirst(), x + 8, ry + 5, selected ? 0x55FF55 : 0xFFFFFF);
    }

    private static void smallButton(GuiGraphics g, Font font, int bx, int by, int bw, String label, boolean hover, int color) {
        g.fill(bx, by, bx + bw, by + 12, hover ? 0x80FFFFFF : 0x30FFFFFF);
        List<FormattedCharSequence> lines = font.split(Component.literal(label), Math.max(6, bw - 6));
        if (!lines.isEmpty()) g.drawString(font, lines.getFirst(), bx + 3, by + 2, color);
    }
}
