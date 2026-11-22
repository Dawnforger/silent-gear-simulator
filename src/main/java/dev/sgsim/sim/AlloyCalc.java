package dev.sgsim.sim;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.silentchaos512.gear.api.material.Material;
import net.silentchaos512.gear.api.part.MaterialGrade;
import net.silentchaos512.gear.api.part.PartType;
import net.silentchaos512.gear.api.util.PropertyKey;
import net.silentchaos512.gear.block.alloymaker.AlloyMakerInfo;
import net.silentchaos512.gear.crafting.recipe.alloy.AlloyRecipe;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.setup.SgDataComponents;
import net.silentchaos512.gear.setup.SgRegistries;
import net.silentchaos512.gear.setup.gear.GearProperties;
import net.silentchaos512.gear.setup.gear.GearTypes;
import net.silentchaos512.gear.setup.gear.PartTypes;
import net.silentchaos512.gear.util.Const;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Alloy calculator: predicts what a Silent Gear alloy machine makes from a set of inputs, following
 * {@code AlloyMakerBlockEntity#tick} step by step. A matching fixed recipe (blaze gold, crimson steel,
 * ...) wins; otherwise two or more different simple materials the machine accepts are compounded into
 * the machine's output item by {@code CompoundMaterialItem#create}, which is what decides the stats.
 */
public final class AlloyCalc {
    private AlloyCalc() {}

    /** The alloy machines, in the order the tab offers them. */
    public enum Machine {
        ALLOY_FORGE(Const.METAL_ALLOY_MAKER_INFO, false),
        RECRYSTALLIZER(Const.GEM_ALLOY_MAKER_INFO, false),
        REFABRICATOR(Const.FABRIC_ALLOY_MAKER_INFO, false),
        CRUDE_MIXER(Const.CRUDE_MIXER_INFO, true),
        SUPER_MIXER(Const.SUPER_MIXER_INFO, false);

        public final AlloyMakerInfo<?> info;
        /** The Crude Mixer marks its output crude (CrudeMixerBlockEntity#applyModifiers). */
        public final boolean crude;

        Machine(AlloyMakerInfo<?> info, boolean crude) {
            this.info = info;
            this.crude = crude;
        }

        public int slots() {
            return info.getInputSlotCount();
        }

        public ItemStack icon() {
            return new ItemStack(info.getBlock());
        }

        public Component displayName() {
            return info.getBlock().getName();
        }

        public boolean accepts(MaterialInstance material) {
            return info.acceptsMaterial(material);
        }
    }

    /** What the player has put in the machine. Stored by id so data reloads never leave stale objects. */
    public static final class State {
        public Machine machine = Machine.ALLOY_FORGE;
        public final List<ResourceLocation> materials = new ArrayList<>();
        public final List<MaterialGrade> grades = new ArrayList<>();
        @Nullable
        public ResourceLocation partType;

        public State() {
            resize();
        }

        /** Match the slot lists to the machine, keeping what fits. */
        public void resize() {
            int n = machine.slots();
            while (materials.size() < n) materials.add(null);
            while (grades.size() < n) grades.add(MaterialGrade.NONE);
            while (materials.size() > n) materials.removeLast();
            while (grades.size() > n) grades.removeLast();
        }

        public void clear() {
            Collections.fill(materials, null);
            Collections.fill(grades, MaterialGrade.NONE);
        }
    }

    public enum Kind { EMPTY, RECIPE, COMPOUND, INVALID }

    /**
     * @param stack     what comes out of one operation (empty if nothing would)
     * @param material  the output read back as a material (null for non-material recipe outputs)
     * @param inputs    each filled slot as the machine sees it, in slot order
     * @param recipeId  the fixed recipe that matched, for {@link Kind#RECIPE}
     */
    public record Result(Kind kind, ItemStack stack, @Nullable MaterialInstance material,
                         List<MaterialInstance> inputs, @Nullable ResourceLocation recipeId,
                         List<Component> problems) {}

    public static Result compute(Minecraft mc, GearCatalog catalog, State state) {
        List<ItemStack> stacks = new ArrayList<>();
        List<MaterialInstance> inputs = new ArrayList<>();
        List<Component> problems = new ArrayList<>();
        for (int i = 0; i < state.materials.size(); i++) {
            ResourceLocation id = state.materials.get(i);
            if (id == null) continue;
            ItemStack stack = catalog.gradedStack(id, state.grades.get(i));
            MaterialInstance material = stack.isEmpty() ? null : MaterialInstance.from(stack);
            if (material == null) {
                problems.add(Component.translatable("sgsim.problem.no_item", id.toString()).withStyle(ChatFormatting.RED));
                continue;
            }
            stacks.add(stack);
            inputs.add(material);
        }
        if (!problems.isEmpty()) return new Result(Kind.INVALID, ItemStack.EMPTY, null, inputs, null, problems);
        if (stacks.isEmpty()) return new Result(Kind.EMPTY, ItemStack.EMPTY, null, inputs, null, problems);

        // 1. Fixed recipes of this machine's recipe type
        if (mc.level != null) {
            for (RecipeHolder<?> holder : recipesFor(mc, state.machine)) {
                if (holder.value() instanceof AlloyRecipe recipe && matches(recipe, stacks)) {
                    ItemStack out = recipe.getResultItem(mc.level.registryAccess()).copy();
                    return new Result(Kind.RECIPE, out, MaterialInstance.from(out), inputs, holder.id(), problems);
                }
            }
        }

        // 2. Free compounding
        for (MaterialInstance m : inputs) {
            if (!m.get().isSimple()) {
                problems.add(Component.translatable("sgsim.alloy.problem.not_simple", name(m)).withStyle(ChatFormatting.RED));
            } else if (!state.machine.accepts(m)) {
                problems.add(Component.translatable("sgsim.alloy.problem.category", name(m), state.machine.displayName())
                        .withStyle(ChatFormatting.RED));
            }
        }
        if (!problems.isEmpty()) return new Result(Kind.INVALID, ItemStack.EMPTY, null, inputs, null, problems);
        if (!hasMultipleMaterials(inputs)) {
            problems.add(Component.translatable("sgsim.alloy.problem.one_material").withStyle(ChatFormatting.GOLD));
            return new Result(Kind.INVALID, ItemStack.EMPTY, null, inputs, null, problems);
        }
        if (commonPartTypes(inputs).isEmpty()) {
            problems.add(Component.translatable("sgsim.alloy.problem.no_common_part").withStyle(ChatFormatting.RED));
            return new Result(Kind.INVALID, ItemStack.EMPTY, null, inputs, null, problems);
        }

        ItemStack out;
        try {
            out = state.machine.info.getOutputItem().create(inputs);
            if (state.machine.crude) out.set(SgDataComponents.CRUDE.get(), Unit.INSTANCE);
        } catch (Throwable t) {
            problems.add(Component.translatable("sgsim.problem.exception", String.valueOf(t.getMessage())).withStyle(ChatFormatting.RED));
            return new Result(Kind.INVALID, ItemStack.EMPTY, null, inputs, null, problems);
        }
        return new Result(Kind.COMPOUND, out, MaterialInstance.from(out), inputs, null, problems);
    }

    /** The fixed recipes the server sent for this machine. */
    public static List<RecipeHolder<?>> recipesFor(Minecraft mc, Machine machine) {
        if (mc.level == null) return List.of();
        return List.copyOf(mc.level.getRecipeManager().getAllRecipesFor(machine.info.getRecipeType()));
    }

    /** Same test as AlloyRecipe#matches: every ingredient is found, and every input is used by one. */
    private static boolean matches(AlloyRecipe recipe, List<ItemStack> stacks) {
        List<Ingredient> ingredients = recipe.getIngredients();
        Set<Integer> used = new HashSet<>();
        for (Ingredient ingredient : ingredients) {
            boolean found = false;
            for (int i = 0; i < stacks.size(); i++) {
                if (ingredient.test(stacks.get(i))) {
                    found = true;
                    used.add(i);
                }
            }
            if (!found) return false;
        }
        return used.size() == stacks.size() && used.size() == ingredients.size();
    }

    private static boolean hasMultipleMaterials(List<MaterialInstance> materials) {
        if (materials.size() < 2) return false;
        Material first = materials.getFirst().get();
        for (MaterialInstance m : materials) {
            if (m.get() != first) return true;
        }
        return false;
    }

    /**
     * Part types every input can be used in, minus those where every input is additive-only
     * (AlloyMakerBlockEntity#canCompoundMaterials). Empty means the machine refuses the mix.
     */
    public static List<PartType> commonPartTypes(List<MaterialInstance> materials) {
        Set<PartType> types = new LinkedHashSet<>(SgRegistries.PART_TYPE.stream().toList());
        for (MaterialInstance m : materials) types.retainAll(m.getPartTypes());
        PropertyKey<Boolean, ?> additive = PropertyKey.of(GearProperties.ADDITIVE.get(), GearTypes.ALL.get());
        types.removeIf(type -> {
            for (MaterialInstance m : materials) {
                if (!Boolean.TRUE.equals(m.getProperty(type, additive))) return false;
            }
            return true;
        });
        return List.copyOf(types);
    }

    /** Materials with an obtainable item that this machine accepts. */
    public static List<ResourceLocation> inputsFor(GearCatalog catalog, Machine machine) {
        List<ResourceLocation> list = new ArrayList<>();
        for (Material material : SgRegistries.MATERIAL.getValues(true)) {
            ResourceLocation id = SgRegistries.MATERIAL.getKey(material);
            if (id == null || !material.isSimple()) continue;
            ItemStack stack = catalog.displayStack(id);
            if (stack.isEmpty()) continue;
            if (machine.accepts(MaterialInstance.of(material, stack))) list.add(id);
        }
        list.sort(Comparator.comparing(id -> GearCatalog.materialName(id, PartTypes.MAIN.get()).getString()));
        return List.copyOf(list);
    }

    private static Component name(MaterialInstance m) {
        return m.getDisplayName(PartTypes.MAIN.get(), ItemStack.EMPTY);
    }
}
