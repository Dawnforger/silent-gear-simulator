package dev.sgsim.sim;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.silentchaos512.gear.api.part.GearPart;
import net.silentchaos512.gear.api.part.PartType;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.item.CompoundPartItem;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A part slot a gear item can take (head, rod, binding, tip, coating, ...), backed by the
 * Silent Gear compound part item that is crafted for it.
 */
public record SlotDef(
        PartType type,
        ResourceLocation typeId,
        CompoundPartItem item,
        GearPart part,
        boolean required,
        List<RecipeIndex.PartRecipe> recipes,
        List<RecipeIndex.FixedPart> fixedParts
) {
    /** Free-choice fallback when the server sent no recipe for this part. */
    public static final int FALLBACK_MAX_COUNT = 9;

    public Component displayName() {
        return GearCatalog.itemName(item);
    }

    /** Fixed-result recipes (e.g. foundry casting) that make this part entirely from one material. */
    public List<RecipeIndex.FixedPart> fixedFor(ResourceLocation materialId) {
        List<RecipeIndex.FixedPart> list = new ArrayList<>();
        for (RecipeIndex.FixedPart fp : fixedParts) {
            if (materialId.equals(fp.singleMaterial())) list.add(fp);
        }
        return list;
    }

    public boolean hasRecipe() {
        return !recipes.isEmpty();
    }

    /** Material counts that real recipes on this server accept, ascending. */
    public List<Integer> allowedCounts() {
        if (recipes.isEmpty()) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= FALLBACK_MAX_COUNT; i++) all.add(i);
            return all;
        }
        TreeSet<Integer> set = new TreeSet<>();
        for (RecipeIndex.PartRecipe r : recipes) set.add(r.materialCount());
        return List.copyOf(set);
    }

    public int defaultCount() {
        List<Integer> counts = allowedCounts();
        return hasRecipe() ? counts.getLast() : 1;
    }

    /**
     * Would the server's part recipe accept this (graded) material item in this slot?
     * Uses the recipe's own ingredient objects when available, otherwise Silent Gear's material rules.
     */
    public boolean accepts(ItemStack materialStack, int materialCount) {
        if (materialStack.isEmpty()) return false;
        if (!recipes.isEmpty()) {
            boolean anyOfCount = false;
            for (RecipeIndex.PartRecipe r : recipes) {
                if (r.materialCount() != materialCount) continue;
                anyOfCount = true;
                if (r.accepts(materialStack)) return true;
            }
            if (anyOfCount) return false;
            // Count not offered by any recipe: judge against any recipe so the count warning is the only one shown.
            for (RecipeIndex.PartRecipe r : recipes) {
                if (r.accepts(materialStack)) return true;
            }
            return false;
        }
        MaterialInstance mat = MaterialInstance.from(materialStack);
        return mat != null && mat.allowedInPart(type) && mat.isCraftingAllowed(type, item.getGearType());
    }
}
