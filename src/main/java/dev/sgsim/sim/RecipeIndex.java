package dev.sgsim.sim;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.silentchaos512.gear.crafting.ingredient.PartMaterialIngredient;
import net.silentchaos512.gear.crafting.recipe.ShapelessCompoundPartRecipe;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.item.CompoundPartItem;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Indexes the recipes the server sent to this client (1.21.1 still syncs every recipe) that make
 * Silent Gear parts. There are two kinds:
 * <ul>
 *   <li><b>Crafting-grid part recipes</b> ({@code silentgear:compound_part}): the player supplies N
 *       material items, so the material and the grade of each unit are free choices. We keep the
 *       recipe's own ingredient objects so we can ask them exactly what they accept.</li>
 *   <li><b>Fixed-result part recipes</b>: any other recipe whose output is a part item that already
 *       carries its material list, e.g. Silent Gear Metalworks' {@code productivemetalworks:item_casting}
 *       foundry recipes used by ATM10. The output is exactly what the player gets, including the
 *       (usually absent) grades, so the simulator copies it as-is.</li>
 * </ul>
 */
public final class RecipeIndex {
    private static final ResourceLocation EXAMPLE_MATERIAL =
            ResourceLocation.fromNamespaceAndPath("silentgear", "example");

    /** One crafting-grid part recipe: how many material ingredients it has, and those ingredients. */
    public record PartRecipe(int materialCount, List<PartMaterialIngredient> materialIngredients) {
        public boolean accepts(ItemStack materialStack) {
            for (PartMaterialIngredient ing : materialIngredients) {
                if (ing.test(materialStack)) return true;
            }
            return false;
        }
    }

    /** A recipe that outputs a finished part with a fixed material list (casting, etc.). */
    public record FixedPart(ResourceLocation recipeId, @Nullable ResourceLocation recipeType,
                            List<MaterialInstance> materials) {
        /** The material if every unit uses the same one, else null. */
        @Nullable
        public ResourceLocation singleMaterial() {
            ResourceLocation id = null;
            for (MaterialInstance m : materials) {
                if (id == null) id = m.getId();
                else if (!id.equals(m.getId())) return null;
            }
            return id;
        }

        public String methodName() {
            return recipeType != null ? recipeType.toString() : recipeId.toString();
        }
    }

    private final Map<Item, List<PartRecipe>> crafting = new HashMap<>();
    private final Map<Item, List<FixedPart>> fixed = new HashMap<>();
    /** Non-grid recipe types that output this part but only decide its material while running. */
    private final Map<Item, Set<ResourceLocation>> dynamic = new HashMap<>();

    public List<PartRecipe> get(Item compoundPartItem) {
        return crafting.getOrDefault(compoundPartItem, List.of());
    }

    public List<FixedPart> fixed(Item compoundPartItem) {
        return fixed.getOrDefault(compoundPartItem, List.of());
    }

    public Set<ResourceLocation> dynamicTypes(Item compoundPartItem) {
        return dynamic.getOrDefault(compoundPartItem, Set.of());
    }

    public static RecipeIndex build(Minecraft mc) {
        RecipeIndex index = new RecipeIndex();
        if (mc.level == null) {
            return index;
        }
        RegistryAccess registryAccess = mc.level.registryAccess();
        // On the client this is the recipe manager filled by the server's recipe sync packet
        Collection<RecipeHolder<?>> recipes = mc.level.getRecipeManager().getRecipes();

        for (RecipeHolder<?> holder : recipes) {
            Recipe<?> recipe = holder.value();
            try {
                if (recipe instanceof ShapelessCompoundPartRecipe partRecipe) {
                    index.addCraftingRecipe(partRecipe, registryAccess);
                } else {
                    index.addFixedRecipe(holder, recipe, registryAccess);
                }
            } catch (Throwable ignored) {
                // Some mods' recipes can't produce a result outside a real crafting context; skip them.
            }
        }

        for (List<PartRecipe> list : index.crafting.values()) {
            list.sort(Comparator.comparingInt(PartRecipe::materialCount));
        }
        return index;
    }

    private void addCraftingRecipe(ShapelessCompoundPartRecipe recipe, RegistryAccess registryAccess) {
        ItemStack result = recipe.getResultItem(registryAccess);
        if (result == null || result.isEmpty()) return;

        List<PartMaterialIngredient> materialIngredients = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.getCustomIngredient() instanceof PartMaterialIngredient pmi) {
                materialIngredients.add(pmi);
            }
        }
        if (materialIngredients.isEmpty()) return;

        crafting.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                .add(new PartRecipe(materialIngredients.size(), List.copyOf(materialIngredients)));
    }

    private void addFixedRecipe(RecipeHolder<?> holder, Recipe<?> recipe, RegistryAccess registryAccess) {
        ItemStack result = recipe.getResultItem(registryAccess);
        if (result == null || result.isEmpty() || !(result.getItem() instanceof CompoundPartItem)) return;

        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        List<MaterialInstance> materials = CompoundPartItem.getMaterials(result);
        boolean placeholder = materials.isEmpty();
        for (MaterialInstance m : materials) {
            // Example/placeholder outputs: the real material is decided when the recipe runs
            if (!m.isValid() || EXAMPLE_MATERIAL.equals(m.getId())) placeholder = true;
        }
        if (placeholder) {
            if (type != null && !"silentgear".equals(type.getNamespace())) {
                dynamic.computeIfAbsent(result.getItem(), k -> new TreeSet<>()).add(type);
            }
            return;
        }

        fixed.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                .add(new FixedPart(holder.id(), type, List.copyOf(materials)));
    }
}
