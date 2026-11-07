package dev.sgsim.sim;

import dev.sgsim.SgSimMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.silentchaos512.gear.api.item.GearItem;
import net.silentchaos512.gear.api.item.GearType;
import net.silentchaos512.gear.api.material.Material;
import net.silentchaos512.gear.api.part.MaterialGrade;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.gear.part.PartInstance;
import net.silentchaos512.gear.setup.SgRegistries;
import net.silentchaos512.gear.setup.gear.PartTypes;
import net.silentchaos512.gear.util.GearData;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the theoretical item by running the same Silent Gear code paths the server runs when the
 * player crafts it:
 * <ol>
 *   <li>Each material unit is the real material item with the grade data component a Material Grader
 *       would put on it, turned into a {@link MaterialInstance} with {@code MaterialInstance.from}-style
 *       modifier reading (ShapelessCompoundPartRecipe#assemble).</li>
 *   <li>The compound part item is created with {@link net.silentchaos512.gear.item.CompoundPartItem#create(List)}
 *       and resolved with {@link PartInstance#from(ItemStack)} (IGearRecipe#getParts).</li>
 *   <li>The gear item is made with {@link GearItem#construct} (ShapelessGearRecipe#assemble), which
 *       recalculates properties and fires the trait "on crafted" hooks.</li>
 *   <li>Non-material upgrades are added the way UpgradeSmithingRecipe does it.</li>
 * </ol>
 * Along the way it records anything the server would refuse to craft, so the preview never silently
 * shows an item that cannot exist.
 */
public final class GearSimulator {
    private GearSimulator() {}

    public record Problem(Component message, boolean blocking) {}

    public record Result(ItemStack stack, List<Problem> problems) {
        public static Result empty(Component why) {
            return new Result(ItemStack.EMPTY, List.of(new Problem(why, true)));
        }

        public boolean craftable() {
            if (stack.isEmpty()) return false;
            for (Problem p : problems) if (p.blocking()) return false;
            return true;
        }
    }

    public static Result build(GearCatalog catalog, SimState state) {
        GearItem gearItem = GearCatalog.gearItem(state.gearItem);
        if (gearItem == null) {
            return Result.empty(Component.translatable("sgsim.problem.no_gear"));
        }
        List<Problem> problems = new ArrayList<>();
        try {
            return buildUnchecked(catalog, state, gearItem, problems);
        } catch (Throwable t) {
            SgSimMod.LOGGER.error("Silent Gear failed while simulating {}", state.gearItem, t);
            problems.add(new Problem(Component.translatable("sgsim.problem.exception",
                    String.valueOf(t.getMessage())).withStyle(ChatFormatting.RED), true));
            return new Result(ItemStack.EMPTY, problems);
        }
    }

    private static Result buildUnchecked(GearCatalog catalog, SimState state, GearItem gearItem, List<Problem> problems) {
        GearType gearType = gearItem.getGearType();
        List<PartInstance> parts = new ArrayList<>();

        for (SlotDef slot : catalog.slotsFor(gearItem)) {
            SimState.PartSel sel = state.parts.get(slot.typeId());
            Component slotName = slot.displayName();

            if (sel == null || !sel.hasAnyMaterial()) {
                if (slot.required()) {
                    problems.add(blocking(Component.translatable("sgsim.problem.missing_part", slotName)));
                }
                continue;
            }

            ResourceLocation only = singleMaterial(sel);
            PartSource source = only != null ? catalog.source(slot, only) : null;

            // Routes where the part comes out ready-made: copy exactly what the server produces
            if (source == PartSource.FIXED_RECIPE || source == PartSource.SUBSTITUTE) {
                PartInstance made;
                boolean gradable = false;
                if (source == PartSource.FIXED_RECIPE) {
                    RecipeIndex.FixedPart fp = slot.fixedFor(only).getFirst();
                    gradable = GradingRules.partGradingAllowed();
                    List<MaterialInstance> materials = fp.materials();
                    if (gradable && sel.grades.stream().anyMatch(g -> g != MaterialGrade.NONE)) {
                        // Cast first, then run the finished part through the Material Grader
                        materials = GradingRules.gradeUnits(materials, sel.grades);
                        problems.add(info(Component.translatable("sgsim.problem.fixed_part_graded", slotName, fp.methodName())));
                    } else {
                        problems.add(info(Component.translatable("sgsim.problem.fixed_part", slotName, fp.methodName())));
                    }
                    made = PartInstance.from(slot.item().create(materials));
                } else {
                    ItemStack substitute = catalog.substituteStack(slot, only);
                    problems.add(info(Component.translatable("sgsim.problem.substitute", substitute.getHoverName(), slotName)));
                    // Same as putting that item into a gear recipe (PartInstance#fromMaterialSubstitute)
                    made = PartInstance.from(substitute);
                }
                for (MaterialGrade g : sel.grades) {
                    if (!gradable && g != MaterialGrade.NONE) {
                        problems.add(warning(Component.translatable("sgsim.problem.fixed_grade_ignored", slotName)));
                        break;
                    }
                }
                if (made == null || !made.isValid()) {
                    problems.add(blocking(Component.translatable("sgsim.problem.part_unresolved", slotName)));
                    continue;
                }
                if (made.getType() == PartTypes.MAIN.get() && !made.get().isCraftingAllowed(made, gearType)) {
                    problems.add(blocking(Component.translatable("sgsim.problem.main_not_allowed", slotName)));
                }
                parts.add(made);
                continue;
            }

            // Allowed by Silent Gear but made by a recipe we can't read: simulate like the grid would
            boolean otherRoute = source == PartSource.OTHER;
            if (otherRoute) {
                problems.add(warning(Component.translatable("sgsim.problem.other_route", slotName,
                        GearCatalog.materialName(only, slot.type()), catalog.otherMethodName(slot))));
            }

            int count = sel.count();
            if (!otherRoute && slot.hasRecipe() && !slot.allowedCounts().contains(count)) {
                problems.add(blocking(Component.translatable("sgsim.problem.bad_count", slotName, count,
                        slot.allowedCounts().toString())));
            }

            List<MaterialInstance> materials = new ArrayList<>();
            ResourceLocation first = null;
            boolean mixed = false;
            for (int i = 0; i < count; i++) {
                ResourceLocation id = sel.materials.get(i);
                MaterialGrade grade = sel.grades.get(i);
                if (id == null) {
                    problems.add(blocking(Component.translatable("sgsim.problem.empty_unit", slotName, i + 1)));
                    continue;
                }
                Material material = SgRegistries.MATERIAL.get(id);
                if (material == null) {
                    problems.add(blocking(Component.translatable("sgsim.problem.unknown_material", id.toString())));
                    continue;
                }
                ItemStack graded = catalog.gradedStack(id, grade);
                if (graded.isEmpty()) {
                    problems.add(blocking(Component.translatable("sgsim.problem.no_item", id.toString())));
                    continue;
                }
                if (!otherRoute && catalog.source(slot, id) != PartSource.OTHER && !slot.accepts(graded, count)) {
                    problems.add(blocking(Component.translatable("sgsim.problem.rejected",
                            GearCatalog.materialName(id, slot.type()), grade.getDisplayName(), slotName)));
                }
                // Same as ShapelessCompoundPartRecipe: the instance reads the grade off the item
                materials.add(MaterialInstance.of(material, graded));

                if (first == null) first = id;
                else if (!first.equals(id)) mixed = true;
            }
            if (materials.isEmpty()) continue;
            if (mixed) {
                // ShapelessCompoundPartRecipe#matches rejects differing materials; some add-ons add other ways
                problems.add(warning(Component.translatable("sgsim.problem.mixed", slotName)));
            }

            ItemStack partStack = slot.item().create(materials);
            PartInstance part = PartInstance.from(partStack);
            if (part == null || !part.isValid()) {
                problems.add(blocking(Component.translatable("sgsim.problem.part_unresolved", slotName)));
                continue;
            }
            if (part.getType() == PartTypes.MAIN.get() && !part.get().isCraftingAllowed(part, gearType)) {
                problems.add(blocking(Component.translatable("sgsim.problem.main_not_allowed", slotName)));
            }
            parts.add(part);
        }

        if (parts.isEmpty()) {
            return new Result(ItemStack.EMPTY, problems);
        }

        ItemStack result = gearItem.construct(parts);

        // Non-material upgrades, applied like UpgradeSmithingRecipe#applyUpgrade
        if (!state.upgrades.isEmpty()) {
            for (UpgradeDef def : catalog.upgradesFor(gearItem)) {
                if (!state.upgrades.contains(def.id())) continue;
                PartInstance upgrade = PartInstance.from(def.icon());
                if (upgrade == null || !upgrade.isValid()) {
                    upgrade = PartInstance.of(def.part(), def.icon());
                }
                if (!upgrade.get().canAddToGear(result, upgrade)) {
                    problems.add(blocking(Component.translatable("sgsim.problem.upgrade_refused", def.name())));
                    continue;
                }
                if (GearData.hasPart(result, upgrade.get())) continue;
                GearData.addPart(result, upgrade);
                GearData.recalculateGearData(result, null);
            }
        }

        return new Result(result, problems);
    }

    private static ResourceLocation singleMaterial(SimState.PartSel sel) {
        ResourceLocation id = null;
        for (ResourceLocation m : sel.materials) {
            if (m == null) return null;
            if (id == null) id = m;
            else if (!id.equals(m)) return null;
        }
        return id;
    }

    private static Problem info(Component message) {
        return new Problem(message.copy().withStyle(ChatFormatting.GRAY), false);
    }

    private static Problem blocking(Component message) {
        return new Problem(message.copy().withStyle(ChatFormatting.RED), true);
    }

    private static Problem warning(Component message) {
        return new Problem(message.copy().withStyle(ChatFormatting.GOLD), false);
    }
}
