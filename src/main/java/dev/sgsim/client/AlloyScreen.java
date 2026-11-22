package dev.sgsim.client;

import dev.sgsim.sim.AlloyCalc;
import dev.sgsim.sim.GearCatalog;
import dev.sgsim.sim.SimState;
import dev.sgsim.sim.StatValues;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.silentchaos512.gear.api.item.GearItem;
import net.silentchaos512.gear.api.item.GearType;
import net.silentchaos512.gear.api.part.MaterialGrade;
import net.silentchaos512.gear.api.part.PartType;
import net.silentchaos512.gear.api.property.NumberProperty;
import net.silentchaos512.gear.client.tooltip.FormatColorScheme;
import net.silentchaos512.gear.client.tooltip.GearComponentTooltips;
import net.silentchaos512.gear.client.tooltip.GearTooltipStyle;
import net.silentchaos512.gear.client.tooltip.MaterialTooltips;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.setup.SgRegistries;
import net.silentchaos512.gear.setup.gear.PartTypes;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;

/**
 * Alloy calculator tab.
 * <p>
 * Left: the machine and its input slots (one material item per slot, as the machine consumes one
 * of each per operation, so repeating a material weights the mix), the resulting ratio, and the
 * machine's fixed recipes. Right: the output, a stat table comparing every input with the alloy for
 * one part type, and Silent Gear's own material tooltip for the alloy on that page.
 */
public class AlloyScreen extends Screen {
    private static final AlloyCalc.State STATE = new AlloyCalc.State();

    private static final int PAD = 6;
    private static final int TOP = Tabs.CONTENT_TOP;
    private static final int CELL = 18;
    private static final int CELL_STEP = 20;
    private static final int LINE_H = 10;
    private static final int RECIPE_H = 20;
    private static final int COL_W = 38;

    private GearCatalog catalog;
    private AlloyCalc.Result result;
    private boolean dirty = true;
    private int ticks;

    private int leftX, leftW, rightX, rightW;
    private int listTop, listBottom, listScroll, listContentH;
    private int detailTop, detailBottom, detailScroll, detailContentH;
    private int previewX, previewY;

    private final List<Hit> hits = new ArrayList<>();

    @Nullable
    private ListPopup<?> popup;
    /** Slot the material popup edits; -1 = the machine popup. */
    private int popupSlot = -1;

    private static String sortKey = null;
    private static boolean sortDescending = true;

    private record Hit(int x, int y, int w, int h, @Nullable ClickAction click, @Nullable ScrollAction scroll,
                       @Nullable Supplier<List<Component>> tooltip) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    @FunctionalInterface
    private interface ClickAction {
        void click(int button);
    }

    @FunctionalInterface
    private interface ScrollAction {
        void scroll(int direction);
    }

    public AlloyScreen() {
        super(Component.translatable("sgsim.alloy.title"));
    }

    // =============================================================================================
    // Setup

    @Override
    protected void init() {
        popup = null;
        catalog = GearCatalog.get(minecraft);
        STATE.resize();

        leftX = PAD;
        leftW = Mth.clamp(width / 2 - PAD - PAD / 2, 190, 300);
        rightX = leftX + leftW + PAD;
        rightW = width - rightX - PAD;

        int half = (leftW - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("sgsim.alloy.clear"), b -> {
            STATE.clear();
            markDirty();
        }).bounds(leftX, TOP + 26, leftW, 20).build());

        listTop = TOP + 50;
        listBottom = height - PAD - 24;

        addRenderableWidget(Button.builder(Component.translatable("sgsim.alloy.to_gear"), b ->
                minecraft.setScreen(new SimulatorScreen())).bounds(leftX, height - PAD - 20, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(leftX + half + 4, height - PAD - 20, half, 20).build());

        previewX = rightX + 4;
        previewY = TOP + 4;
        markDirty();
    }

    private void markDirty() {
        dirty = true;
    }

    private void recompute() {
        dirty = false;
        result = AlloyCalc.compute(minecraft, catalog, STATE);
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0 && GearCatalog.isStale(minecraft)) {
            catalog = GearCatalog.get(minecraft);
            markDirty();
        }
        if (dirty) recompute();
    }

    // =============================================================================================
    // Rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (dirty || result == null) recompute();
        boolean overPopup = popup != null && popup.contains(mouseX, mouseY);
        int ux = overPopup ? -1000 : mouseX;
        int uy = overPopup ? -1000 : mouseY;

        super.render(g, ux, uy, partialTick);
        Tabs.render(g, font, Tabs.Tab.ALLOYS, ux, uy);
        hits.clear();

        renderMachineSelector(g, ux, uy);
        renderInputs(g, ux, uy);
        renderOutput(g, ux, uy);

        if (popup != null) {
            popup.render(g, font, mouseX, mouseY, partialTick);
            Component help = popup.hoverHelp(mouseX, mouseY);
            ListPopup.Row<?> row = popup.rowAt(mouseX, mouseY);
            if (help != null) {
                g.renderComponentTooltip(font, List.of(help), mouseX, mouseY);
            } else if (row != null && row.value() instanceof ResourceLocation id) {
                g.renderComponentTooltip(font, materialTooltip(id, popupGrade()), mouseX, mouseY);
            } else if (row != null && row.value() instanceof AlloyCalc.Machine m) {
                g.renderComponentTooltip(font, machineTooltip(m), mouseX, mouseY);
            }
            return;
        }

        if (isOverPreview(mouseX, mouseY) && !result.stack().isEmpty()) {
            g.renderTooltip(font, result.stack(), mouseX, mouseY);
            return;
        }
        for (Hit hit : hits) {
            if (hit.tooltip() != null && hit.contains(mouseX, mouseY)) {
                g.renderComponentTooltip(font, hit.tooltip().get(), mouseX, mouseY);
                break;
            }
        }
    }

    private void renderMachineSelector(GuiGraphics g, int mx, int my) {
        int x = leftX, y = TOP, w = leftW, h = 22;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        panel(g, x, y, w, h, hover ? 0xC0303040 : 0xC0101018);
        g.renderItem(STATE.machine.icon(), x + 3, y + 3);
        g.drawString(font, STATE.machine.displayName(), x + 24, y + 7, 0xFFFFFF);
        g.drawString(font, "▼", x + w - 12, y + 7, 0xAAAAAA);
        hits.add(new Hit(x, y, w, h, b -> openMachinePopup(), null, () -> machineTooltip(STATE.machine)));
    }

    private List<Component> machineTooltip(AlloyCalc.Machine m) {
        List<Component> lines = new ArrayList<>();
        lines.add(m.displayName());
        lines.add(Component.translatable("sgsim.alloy.slots", m.slots()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("sgsim.alloy.machine." + m.name().toLowerCase(Locale.ROOT))
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private void renderInputs(GuiGraphics g, int mx, int my) {
        panel(g, leftX, listTop - 2, leftW, listBottom - listTop + 4, 0x90000000);
        g.enableScissor(leftX, listTop, leftX + leftW, listBottom);
        int x = leftX + 4;
        int w = leftW - 8;
        int y = listTop - listScroll;

        // --- input cells -------------------------------------------------------------------------
        g.drawString(font, Component.translatable("sgsim.alloy.inputs").withStyle(ChatFormatting.UNDERLINE), x, y + 2, 0xFFFFAA);
        y += 13;
        int perRow = Math.max(1, (w + CELL_STEP - CELL) / CELL_STEP);
        int slots = STATE.machine.slots();
        for (int i = 0; i < slots; i++) {
            int cellX = x + (i % perRow) * CELL_STEP;
            int cellY = y + (i / perRow) * CELL_STEP;
            renderCell(g, i, cellX, cellY, mx, my);
        }
        y += ((slots + perRow - 1) / perRow) * CELL_STEP + 4;
        g.drawString(font, Component.translatable("sgsim.alloy.cell_help").withStyle(ChatFormatting.DARK_GRAY), x, y, 0xFFFFFF);
        y += LINE_H + 6;

        // --- ratio -------------------------------------------------------------------------------
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (ResourceLocation id : STATE.materials) {
            if (id == null) continue;
            counts.merge(id, 1, Integer::sum);
            total++;
        }
        if (total > 0) {
            g.drawString(font, Component.translatable("sgsim.alloy.ratio").withStyle(ChatFormatting.UNDERLINE), x, y, 0xFFFFAA);
            y += 12;
            for (Map.Entry<ResourceLocation, Integer> e : counts.entrySet()) {
                g.renderItem(catalog.displayStack(e.getKey()), x, y - 4);
                String pct = String.format(Locale.ROOT, "%d × (%.0f%%)", e.getValue(), 100.0 * e.getValue() / total);
                g.drawString(font, GearCatalog.materialName(e.getKey(), PartTypes.MAIN.get()), x + 20, y, 0xFFFFFF);
                g.drawString(font, pct, x + w - font.width(pct), y, 0xAAAAAA);
                y += 14;
            }
            y += 4;
        }

        // --- fixed recipes -----------------------------------------------------------------------
        List<RecipeHolder<?>> recipes = AlloyCalc.recipesFor(minecraft, STATE.machine);
        g.drawString(font, Component.translatable("sgsim.alloy.recipes", recipes.size()).withStyle(ChatFormatting.UNDERLINE),
                x, y, 0xFFFFAA);
        y += 12;
        if (recipes.isEmpty()) {
            g.drawString(font, Component.translatable("sgsim.alloy.recipes.none").withStyle(ChatFormatting.GRAY), x, y, 0xFFFFFF);
            y += LINE_H;
        }
        for (RecipeHolder<?> holder : recipes) {
            renderRecipe(g, holder, x, y, w, mx, my);
            y += RECIPE_H;
        }
        g.disableScissor();

        listContentH = y + listScroll - listTop;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, listContentH - (listBottom - listTop)));
        renderScrollbar(g, leftX + leftW - 3, listTop, listBottom, listScroll, listContentH);
    }

    private void renderCell(GuiGraphics g, int slot, int cellX, int cellY, int mx, int my) {
        boolean hover = mx >= cellX && mx < cellX + CELL && my >= cellY && my < cellY + CELL
                && my >= listTop && my < listBottom;
        g.fill(cellX, cellY, cellX + CELL, cellY + CELL, hover ? 0xFF8B8B8B : 0xFF373737);
        g.fill(cellX + 1, cellY + 1, cellX + CELL - 1, cellY + CELL - 1, hover ? 0xFFA0A0A0 : 0xFF8B8B8B);
        ResourceLocation id = STATE.materials.get(slot);
        MaterialGrade grade = STATE.grades.get(slot);
        if (id == null) {
            g.drawCenteredString(font, "+", cellX + CELL / 2, cellY + 5, 0xFFFFFF);
        } else {
            g.renderItem(catalog.displayStack(id), cellX + 1, cellY + 1);
            if (grade != MaterialGrade.NONE) {
                String gt = grade.name();
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.drawString(font, gt, cellX + CELL - font.width(gt), cellY + CELL - 8, gradeColor(grade));
                g.pose().popPose();
            }
        }
        addHit(cellX, cellY, CELL, CELL, b -> {
            if (b == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                if (hasShiftDown()) {
                    STATE.materials.set(slot, null);
                    STATE.grades.set(slot, MaterialGrade.NONE);
                } else if (STATE.materials.get(slot) != null) {
                    STATE.grades.set(slot, SimState.cycleGrade(STATE.grades.get(slot), 1));
                }
                markDirty();
            } else if (b == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                openMaterialPopup(slot, cellX + CELL + 2, cellY);
            }
        }, dir -> {
            if (STATE.materials.get(slot) == null) return;
            STATE.grades.set(slot, SimState.cycleGrade(STATE.grades.get(slot), dir));
            markDirty();
        }, () -> {
            List<Component> lines = new ArrayList<>();
            if (id != null) {
                lines.addAll(materialTooltip(id, grade));
                lines.add(Component.translatable("sgsim.cell.grade", grade.getDisplayName()).withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("sgsim.alloy.empty_slot"));
            }
            lines.add(Component.translatable("sgsim.cell.help1").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("sgsim.alloy.cell_help2").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        });
    }

    private void renderRecipe(GuiGraphics g, RecipeHolder<?> holder, int x, int y, int w, int mx, int my) {
        if (y + RECIPE_H < listTop || y > listBottom) return;
        List<Ingredient> ingredients = holder.value().getIngredients();
        ItemStack out = minecraft.level != null ? holder.value().getResultItem(minecraft.level.registryAccess()) : ItemStack.EMPTY;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + RECIPE_H && my >= listTop && my < listBottom;
        if (hover) g.fill(x - 2, y, x + w, y + RECIPE_H, 0x30FFFFFF);
        int ix = x;
        for (Ingredient ing : ingredients) {
            ItemStack[] items = ing.getItems();
            if (items.length > 0) g.renderItem(items[(ticks / 20) % items.length], ix, y + 2);
            ix += 17;
        }
        g.drawString(font, "→", ix + 2, y + 6, 0xAAAAAA);
        g.renderItem(out, ix + 12, y + 2);
        g.renderItemDecorations(font, out, ix + 12, y + 2);

        List<ResourceLocation> loadable = loadableMaterials(ingredients);
        addHit(x, y, w, RECIPE_H, b -> {
            if (loadable == null || loadable.size() > STATE.machine.slots()) return;
            STATE.clear();
            for (int i = 0; i < loadable.size(); i++) STATE.materials.set(i, loadable.get(i));
            markDirty();
        }, null, () -> {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, out));
            lines.add(Component.literal(holder.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable(loadable != null ? "sgsim.alloy.recipe.load" : "sgsim.alloy.recipe.cant_load")
                    .withStyle(loadable != null ? ChatFormatting.GRAY : ChatFormatting.GOLD));
            return lines;
        });
    }

    /** Each ingredient as a material the calculator can hold, or null if any ingredient isn't one. */
    @Nullable
    private static List<ResourceLocation> loadableMaterials(List<Ingredient> ingredients) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            ResourceLocation found = null;
            for (ItemStack item : ing.getItems()) {
                MaterialInstance m = MaterialInstance.from(item.copyWithCount(1));
                if (m != null && m.get().isSimple()) {
                    found = m.getId();
                    break;
                }
            }
            if (found == null) return null;
            ids.add(found);
        }
        return ids;
    }

    private void renderOutput(GuiGraphics g, int mx, int my) {
        panel(g, rightX, TOP, rightW, height - TOP - PAD, 0x90000000);

        int box = 72;
        g.fill(previewX, previewY, previewX + box, previewY + box, 0xFF373737);
        g.fill(previewX + 1, previewY + 1, previewX + box - 1, previewY + box - 1, 0xFF8B8B8B);
        ItemStack stack = result.stack();
        if (!stack.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(previewX + 4, previewY + 4, 0);
            g.pose().scale(4f, 4f, 1f);
            g.renderItem(stack, 0, 0);
            g.pose().popPose();
        }

        int textX = previewX + box + 6;
        int textW = Math.max(40, rightX + rightW - textX - 4);
        int ty = previewY + 2;
        if (!stack.isEmpty()) {
            for (FormattedCharSequence line : font.split(stack.getHoverName(), textW)) {
                g.drawString(font, line, textX, ty, 0xFFFFFF);
                ty += LINE_H;
            }
            String count = Component.translatable("sgsim.alloy.yield", stack.getCount()).getString();
            g.drawString(font, count, textX, ty, 0xAAAAAA);
            ty += LINE_H;
        }
        Component status = switch (result.kind()) {
            case EMPTY -> Component.translatable("sgsim.alloy.status.empty").withStyle(ChatFormatting.GRAY);
            case RECIPE -> Component.translatable("sgsim.alloy.status.recipe").withStyle(ChatFormatting.AQUA);
            case COMPOUND -> Component.translatable("sgsim.alloy.status.compound").withStyle(ChatFormatting.GREEN);
            case INVALID -> Component.translatable("sgsim.alloy.status.invalid").withStyle(ChatFormatting.RED);
        };
        for (FormattedCharSequence line : font.split(status, textW)) {
            g.drawString(font, line, textX, ty + 2, 0xFFFFFF);
            ty += LINE_H;
        }

        int innerX = rightX + 6;
        int innerW = rightW - 12;
        int y = previewY + box + 6;
        for (Component p : result.problems()) {
            for (FormattedCharSequence line : font.split(Component.literal("• ").append(p), innerW)) {
                g.drawString(font, line, innerX, y, 0xFFFFFF);
                y += LINE_H;
            }
        }

        // Part type + gear type the stats are for
        List<PartType> pages = partPages();
        PartType page = currentPage(pages);
        if (page != null) {
            Component pageLabel = Component.translatable("sgsim.alloy.part", page.getDisplayName());
            int pw = font.width(pageLabel) + 8;
            boolean hover = mx >= innerX && mx < innerX + pw && my >= y && my < y + 12;
            g.fill(innerX, y, innerX + pw, y + 12, hover ? 0x80FFFFFF : 0x30FFFFFF);
            g.drawString(font, pageLabel, innerX + 4, y + 2, 0xFFFFFF);
            hits.add(new Hit(innerX, y, pw, 12, b -> cyclePage(pages, b == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1),
                    dir -> cyclePage(pages, -dir), () -> List.of(Component.translatable("sgsim.alloy.part.tooltip"))));
            GearItem planned = SimulatorScreen.plannedGearItem();
            Component gearLabel = Component.translatable("sgsim.alloy.for_gear", planned != null
                    ? GearCatalog.itemName(planned.asItem()) : Component.translatable("sgsim.alloy.any_gear"));
            g.drawString(font, gearLabel.copy().withStyle(ChatFormatting.DARK_GRAY), innerX + pw + 6, y + 2, 0xFFFFFF);
            hits.add(new Hit(innerX + pw + 6, y, font.width(gearLabel), 12, null, null,
                    () -> List.of(Component.translatable("sgsim.alloy.for_gear.tooltip"))));
            y += 16;
        }

        detailTop = y;
        detailBottom = height - PAD - 4;
        g.fill(innerX - 2, detailTop - 2, innerX + innerW + 2, detailBottom + 2, 0xF0100010);
        g.renderOutline(innerX - 3, detailTop - 3, innerW + 6, detailBottom - detailTop + 6, 0x505000FF);

        g.enableScissor(innerX - 2, detailTop, innerX + innerW + 2, detailBottom);
        int dy = detailTop + 1 - detailScroll;
        if (page != null) dy = renderStatTable(g, page, innerX + 1, dy, innerW - 4, mx, my);
        if (result.material() != null && page != null) {
            dy += 4;
            for (Component c : materialPage(result.material(), page)) {
                List<FormattedCharSequence> wrapped = font.split(c, innerW - 6);
                if (wrapped.isEmpty()) dy += LINE_H;
                for (FormattedCharSequence line : wrapped) {
                    if (dy + LINE_H >= detailTop && dy <= detailBottom) g.drawString(font, line, innerX + 1, dy, 0xFFFFFF);
                    dy += LINE_H;
                }
            }
        }
        g.disableScissor();
        detailContentH = dy + detailScroll - detailTop;
        detailScroll = Mth.clamp(detailScroll, 0, Math.max(0, detailContentH - (detailBottom - detailTop)));
        renderScrollbar(g, innerX + innerW, detailTop, detailBottom, detailScroll, detailContentH);
    }

    /**
     * One row per stat any column has on this part: each distinct input (at its grade), then the
     * alloy. Returns the y below the table.
     */
    private int renderStatTable(GuiGraphics g, PartType page, int x, int y, int w, int mx, int my) {
        List<MaterialInstance> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MaterialInstance m : result.inputs()) {
            String key = m.getId() + "|" + ItemStack.hashItemAndComponents(m.getItem()); // same material at another grade = own column
            if (seen.add(key)) columns.add(m);
        }
        if (columns.isEmpty()) {
            g.drawString(font, Component.translatable("sgsim.alloy.add_inputs").withStyle(ChatFormatting.GRAY), x, y + 2, 0xFFFFFF);
            return y + 14;
        }
        MaterialInstance alloy = result.material();
        int maxInputCols = Math.max(1, (w - 80) / COL_W - (alloy != null ? 1 : 0));
        int hidden = Math.max(0, columns.size() - maxInputCols);
        if (hidden > 0) columns = columns.subList(0, maxInputCols);
        int colCount = columns.size() + (alloy != null ? 1 : 0);
        int labelW = w - colCount * COL_W;
        GearType gearType = SimulatorScreen.plannedGearType();

        // Header: icons
        int cx = x + labelW;
        for (MaterialInstance m : columns) {
            int ix = cx + (COL_W - 16) / 2;
            g.renderItem(m.getItem(), ix, y);
            MaterialInstance col = m;
            hits.add(clipped(ix, y, 16, 16, () -> List.of(col.getDisplayNameWithModifiers(page, ItemStack.EMPTY))));
            cx += COL_W;
        }
        if (alloy != null) {
            int ix = cx + (COL_W - 16) / 2;
            g.fill(cx + 1, y - 1, cx + COL_W - 1, y + 17, 0x40FFFF55);
            g.renderItem(result.stack(), ix, y);
            hits.add(clipped(ix, y, 16, 16, () -> List.of(Component.translatable("sgsim.alloy.result_column"))));
        }
        if (hidden > 0) {
            g.drawString(font, Component.translatable("sgsim.alloy.hidden_cols", hidden).withStyle(ChatFormatting.DARK_GRAY),
                    x, y + 4, 0xFFFFFF);
        }
        y += 19;

        List<NumberProperty> props = new ArrayList<>();
        for (NumberProperty p : StatValues.numberProperties()) {
            boolean any = alloy != null && !Double.isNaN(StatValues.value(alloy, page, gearType, p));
            for (MaterialInstance m : columns) any |= !Double.isNaN(StatValues.value(m, page, gearType, p));
            if (any) props.add(p);
        }
        props.sort(Comparator.comparing(p -> p.getDisplayName().getString()));

        boolean shade = false;
        for (NumberProperty p : props) {
            if (shade) g.fill(x - 1, y - 1, x + w, y + LINE_H, 0x18FFFFFF);
            shade = !shade;
            String name = font.plainSubstrByWidth(p.getDisplayName().getString(), labelW - 4);
            g.drawString(font, name, x, y, 0xCCCCCC);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            cx = x + labelW;
            for (MaterialInstance m : columns) {
                double v = StatValues.value(m, page, gearType, p);
                if (!Double.isNaN(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                drawValue(g, v, cx, y, 0xFFFFFF);
                cx += COL_W;
            }
            if (alloy != null) {
                double v = StatValues.value(alloy, page, gearType, p);
                int color = 0xFFFF55;
                if (!Double.isNaN(v) && lo <= hi) {
                    if (v > hi + 1e-6) color = 0x55FF55;
                    else if (v < lo - 1e-6) color = 0xFF5555;
                }
                drawValue(g, v, cx, y, color);
            }
            y += LINE_H + 1;
        }
        if (props.isEmpty()) {
            g.drawString(font, Component.translatable("sgsim.alloy.no_stats").withStyle(ChatFormatting.GRAY), x, y, 0xFFFFFF);
            y += LINE_H;
        }
        return y + 2;
    }

    private void drawValue(GuiGraphics g, double v, int colX, int y, int color) {
        String s = Double.isNaN(v) ? "-" : formatValue(v);
        s = font.plainSubstrByWidth(s, COL_W - 2);
        g.drawString(font, s, colX + COL_W - 2 - font.width(s), y, Double.isNaN(v) ? 0x555555 : color);
    }

    static String formatValue(double v) {
        double a = Math.abs(v);
        String s = a >= 1000 ? String.format(Locale.ROOT, "%.0f", v)
                : a >= 100 ? String.format(Locale.ROOT, "%.1f", v)
                : String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** Silent Gear's material tooltip for one part page (header, stats with modifiers, traits). */
    private List<Component> materialPage(MaterialInstance material, PartType page) {
        List<Component> lines = new ArrayList<>();
        try {
            boolean advanced = minecraft.options.advancedItemTooltips;
            MaterialTooltips.materialHeader(lines, material, true);
            MaterialTooltips.materialModifierLines(lines, material);
            MaterialTooltips.materialCategories(lines, material);
            MaterialTooltips.propertiesHeader(lines, material);
            MaterialTooltips.propertiesLines(lines, new GearTooltipStyle(advanced, true, true),
                    FormatColorScheme.LIGHT, page, material);
        } catch (Throwable t) {
            lines.add(Component.translatable("sgsim.problem.exception", String.valueOf(t.getMessage()))
                    .withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    private List<Component> materialTooltip(ResourceLocation id, MaterialGrade grade) {
        List<Component> lines = new ArrayList<>();
        lines.add(GearCatalog.materialName(id, PartTypes.MAIN.get()));
        ItemStack stack = catalog.gradedStack(id, grade);
        MaterialInstance m = stack.isEmpty() ? null : MaterialInstance.from(stack);
        if (m == null) return lines;
        List<PartType> types = GearComponentTooltips.getSortedPartTypes(m.getPartTypes());
        PartType page = currentPage(types);
        if (page == null) return lines;
        lines.addAll(materialPage(m, page));
        return lines;
    }

    /** Part types to page through: the alloy's, else those every input shares. */
    private List<PartType> partPages() {
        if (result.material() != null) {
            return GearComponentTooltips.getSortedPartTypes(result.material().getPartTypes());
        }
        if (!result.inputs().isEmpty()) {
            List<PartType> common = AlloyCalc.commonPartTypes(result.inputs());
            if (!common.isEmpty()) return GearComponentTooltips.getSortedPartTypes(new LinkedHashSet<>(common));
            return GearComponentTooltips.getSortedPartTypes(result.inputs().getFirst().getPartTypes());
        }
        return List.of();
    }

    @Nullable
    private PartType currentPage(List<PartType> pages) {
        if (pages.isEmpty()) return null;
        if (STATE.partType != null) {
            for (PartType t : pages) {
                if (STATE.partType.equals(SgRegistries.PART_TYPE.getKey(t))) return t;
            }
        }
        return pages.contains(PartTypes.MAIN.get()) ? PartTypes.MAIN.get() : pages.getFirst();
    }

    private void cyclePage(List<PartType> pages, int dir) {
        PartType cur = currentPage(pages);
        if (cur == null) return;
        PartType next = pages.get(Math.floorMod(pages.indexOf(cur) + dir, pages.size()));
        STATE.partType = SgRegistries.PART_TYPE.getKey(next);
        detailScroll = 0;
    }

    private boolean isOverPreview(double mx, double my) {
        return mx >= previewX && mx < previewX + 72 && my >= previewY && my < previewY + 72;
    }

    // =============================================================================================
    // Helpers

    private static void panel(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
        g.renderOutline(x, y, w, h, 0x40FFFFFF);
    }

    private void renderScrollbar(GuiGraphics g, int x, int top, int bottom, int scroll, int contentH) {
        int viewH = bottom - top;
        if (contentH <= viewH || viewH <= 0) return;
        int barH = Math.max(10, viewH * viewH / contentH);
        int barY = top + (viewH - barH) * scroll / Math.max(1, contentH - viewH);
        g.fill(x, top, x + 2, bottom, 0x40FFFFFF);
        g.fill(x, barY, x + 2, barY + barH, 0xC0FFFFFF);
    }

    /** Registers a clickable region, clipped to the scrolling input list. */
    private void addHit(int x, int y, int w, int h, @Nullable ClickAction click, @Nullable ScrollAction scroll,
                        @Nullable Supplier<List<Component>> tooltip) {
        int top = Math.max(y, listTop);
        int bottom = Math.min(y + h, listBottom);
        if (bottom <= top) return;
        hits.add(new Hit(x, top, w, bottom - top, click, scroll, tooltip));
    }

    /** A tooltip-only region, clipped to the scrolling detail box. */
    private Hit clipped(int x, int y, int w, int h, Supplier<List<Component>> tooltip) {
        int top = Math.max(y, detailTop);
        int bottom = Math.max(top, Math.min(y + h, detailBottom));
        return new Hit(x, top, w, bottom - top, null, null, tooltip);
    }

    private static int gradeColor(MaterialGrade grade) {
        return switch (grade) {
            case NONE -> 0xAAAAAA;
            case E, D -> 0xFFFFFF;
            case C, B -> 0x55FF55;
            case A -> 0x55FFFF;
            case S, SS -> 0xFFAA00;
            case SSS -> 0xFF55FF;
            case MAX -> 0xFF5555;
        };
    }

    // =============================================================================================
    // Popups

    private MaterialGrade popupGrade() {
        return popupSlot >= 0 && popupSlot < STATE.grades.size() ? STATE.grades.get(popupSlot) : MaterialGrade.NONE;
    }

    private void openMachinePopup() {
        List<ListPopup.Row<AlloyCalc.Machine>> rows = new ArrayList<>();
        for (AlloyCalc.Machine m : AlloyCalc.Machine.values()) {
            rows.add(ListPopup.Row.of(m, m.icon(), m.displayName(), m.name(),
                    Component.translatable("sgsim.alloy.slots", m.slots()).getString(), 0xAAAAAA));
        }
        closePopup();
        popupSlot = -1;
        ListPopup<AlloyCalc.Machine> p = new ListPopup<>(font, rows, List.of(), STATE.machine, null, false,
                leftX, TOP + 24, Math.max(160, leftW), width, height, Component.translatable("sgsim.search"));
        popup = p;
        addWidget(p.search);
        setFocused(p.search);
    }

    private void openMaterialPopup(int slot, int anchorX, int anchorY) {
        MaterialGrade grade = STATE.grades.get(slot);
        PartType page = PartTypes.MAIN.get();
        GearType gearType = SimulatorScreen.plannedGearType();
        List<ListPopup.Row<ResourceLocation>> rows = new ArrayList<>();
        rows.add(ListPopup.Row.of(null, ItemStack.EMPTY, Component.translatable("sgsim.none"), "", null, 0));
        Map<ResourceLocation, MaterialInstance> previews = new HashMap<>();
        for (ResourceLocation id : AlloyCalc.inputsFor(catalog, STATE.machine)) {
            ItemStack stack = catalog.gradedStack(id, grade);
            MaterialInstance preview = stack.isEmpty() ? null : MaterialInstance.from(stack);
            if (preview != null) previews.put(id, preview);
            String extra = id + (preview != null ? StatValues.traitText(preview, page, gearType) : "");
            rows.add(ListPopup.Row.of(id, catalog.displayStack(id), GearCatalog.materialName(id, page), extra, null, 0));
        }

        List<ListPopup.Sort<ResourceLocation>> sorts = new ArrayList<>();
        for (NumberProperty property : StatValues.numberProperties()) {
            boolean relevant = false;
            for (MaterialInstance m : previews.values()) {
                if (!Double.isNaN(StatValues.value(m, page, gearType, property))) {
                    relevant = true;
                    break;
                }
            }
            if (!relevant) continue;
            sorts.add(new ListPopup.Sort<>(StatValues.key(property), property.getDisplayName(), id -> {
                MaterialInstance m = previews.get(id);
                return m != null ? StatValues.value(m, page, gearType, property) : Double.NaN;
            }));
        }
        sorts.sort(Comparator.comparing(srt -> srt.name().getString()));

        closePopup();
        popupSlot = slot;
        ListPopup<ResourceLocation> p = new ListPopup<>(font, rows, sorts, STATE.materials.get(slot), sortKey, sortDescending,
                anchorX, anchorY, 210, width, height, Component.translatable("sgsim.search.materials"));
        popup = p;
        addWidget(p.search);
        setFocused(p.search);
    }

    private void closePopup() {
        if (popup != null) {
            if (popupSlot >= 0) {
                sortKey = popup.sortKey();
                sortDescending = popup.descending();
            }
            removeWidget(popup.search);
            popup = null;
            setFocused(null);
        }
    }

    private void pickFromPopup(@Nullable Object value) {
        int slot = popupSlot;
        closePopup();
        if (slot < 0) {
            if (value instanceof AlloyCalc.Machine m && m != STATE.machine) {
                STATE.machine = m;
                STATE.resize();
                listScroll = 0;
            }
        } else {
            STATE.materials.set(slot, (ResourceLocation) value);
            if (value == null) STATE.grades.set(slot, MaterialGrade.NONE);
        }
        markDirty();
    }

    // =============================================================================================
    // Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (popup != null) {
            ListPopup.Click click = popup.click(mouseX, mouseY, button);
            if (click == ListPopup.Click.OUTSIDE) {
                closePopup();
            } else if (click == ListPopup.Click.PICKED && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && popup.picked() != null) {
                pickFromPopup(popup.picked().value());
            } else {
                setFocused(popup.search);
            }
            return true;
        }
        if (Tabs.click(minecraft, font, Tabs.Tab.ALLOYS, mouseX, mouseY)) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        for (Hit hit : List.copyOf(hits)) {
            if (hit.click() != null && hit.contains(mouseX, mouseY)) {
                hit.click().click(button);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dir = (int) Math.signum(scrollY);
        if (dir == 0) return false;
        if (popup != null) {
            if (popup.contains(mouseX, mouseY)) popup.scroll(dir);
            return true;
        }
        for (Hit hit : hits) {
            if (hit.scroll() != null && hit.contains(mouseX, mouseY)) {
                hit.scroll().scroll(dir);
                return true;
            }
        }
        if (mouseX >= leftX && mouseX < leftX + leftW && mouseY >= listTop && mouseY < listBottom) {
            listScroll = Mth.clamp(listScroll - dir * 12, 0, Math.max(0, listContentH - (listBottom - listTop)));
            return true;
        }
        if (mouseX >= rightX && mouseY >= detailTop && mouseY < detailBottom) {
            detailScroll = Mth.clamp(detailScroll - dir * LINE_H * 2, 0,
                    Math.max(0, detailContentH - (detailBottom - detailTop)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (popup != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closePopup();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                ListPopup.Row<?> first = popup.firstMatch();
                if (first != null) pickFromPopup(first.value());
                else closePopup();
                return true;
            }
            super.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (dev.sgsim.SgSimMod.OPEN_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
