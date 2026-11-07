package dev.sgsim.sim;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.silentchaos512.gear.api.item.GearItem;
import net.silentchaos512.gear.api.item.GearType;
import net.silentchaos512.gear.api.material.Material;
import net.silentchaos512.gear.api.part.GearPart;
import net.silentchaos512.gear.api.part.MaterialGrade;
import net.silentchaos512.gear.api.part.PartType;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.gear.part.PartInstance;
import net.silentchaos512.gear.item.CompoundPartItem;
import net.silentchaos512.gear.setup.SgRegistries;
import net.silentchaos512.gear.setup.gear.PartTypes;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Snapshot of everything the simulator can offer, read from Silent Gear's client-side registries.
 * <p>
 * Silent Gear sends its materials, parts and traits from the server to every client on join and on
 * /reload (SyncMaterialsPayload etc.), and 1.21.1 sends all recipes. So this catalog reflects the
 * server's data packs and add-ons, not just what is installed locally. It is rebuilt whenever that
 * data changes (see {@link #fingerprint}).
 */
public final class GearCatalog {
    private static GearCatalog current;

    private final long fingerprint;
    private final RecipeIndex recipes;
    private final List<Item> gearItems;
    private final Map<Item, List<SlotDef>> slotCache = new HashMap<>();
    private final Map<Item, List<UpgradeDef>> upgradeCache = new HashMap<>();
    private final Map<String, List<ResourceLocation>> materialCache = new HashMap<>();
    private final Map<ResourceLocation, ItemStack> displayStackCache = new HashMap<>();
    /** Per slot: how each offered material is obtained. */
    private final Map<String, Map<ResourceLocation, PartSource>> sourceCache = new HashMap<>();

    private GearCatalog(long fingerprint, RecipeIndex recipes, List<Item> gearItems) {
        this.fingerprint = fingerprint;
        this.recipes = recipes;
        this.gearItems = gearItems;
    }

    /** Returns the current catalog, rebuilding it if the server has sent new data since it was made. */
    public static GearCatalog get(Minecraft mc) {
        long fp = fingerprint(mc);
        if (current == null || current.fingerprint != fp) {
            current = build(mc, fp);
        }
        return current;
    }

    public static boolean isStale(Minecraft mc) {
        return current == null || current.fingerprint != fingerprint(mc);
    }

    private static GearCatalog build(Minecraft mc, long fp) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof GearItem) items.add(item);
        }
        items.sort(Comparator.comparing(i -> itemName(i).getString()));
        return new GearCatalog(fp, RecipeIndex.build(mc), List.copyOf(items));
    }

    /**
     * Cheap identity hash over the synced data. Silent Gear replaces Material/GearPart objects when a
     * sync packet arrives, and the recipe manager is refilled on recipe sync, so any change shows up here.
     */
    private static long fingerprint(Minecraft mc) {
        long h = 17;
        for (Material m : SgRegistries.MATERIAL) h = h * 31 + System.identityHashCode(m);
        for (GearPart p : SgRegistries.PART) h = h * 31 + System.identityHashCode(p);
        if (mc.level != null) {
            var all = mc.level.getRecipeManager().getRecipes();
            h = h * 31 + all.size();
            var it = all.iterator();
            if (it.hasNext()) h = h * 31 + System.identityHashCode(it.next());
        }
        return h;
    }

    public RecipeIndex recipes() {
        return recipes;
    }

    public List<Item> gearItems() {
        return gearItems;
    }

    // ---------------------------------------------------------------------------------------------
    // Slots

    public List<SlotDef> slotsFor(GearItem gearItem) {
        return slotCache.computeIfAbsent(gearItem.asItem(), k -> computeSlots(gearItem));
    }

    private List<SlotDef> computeSlots(GearItem gearItem) {
        GearType gearType = gearItem.getGearType();
        ItemStack probe = new ItemStack(gearItem);
        List<PartType> required = new ArrayList<>(gearItem.getRequiredParts());
        List<SlotDef> requiredSlots = new ArrayList<>();
        List<SlotDef> optionalSlots = new ArrayList<>();

        for (PartType partType : SgRegistries.PART_TYPE) {
            if (partType == PartTypes.NONE.get()) continue;
            ResourceLocation typeId = SgRegistries.PART_TYPE.getKey(partType);
            if (typeId == null) continue;

            Optional<? extends CompoundPartItem> compound = partType.getCompoundPartItem(gearType);
            if (compound.isEmpty()) continue;
            CompoundPartItem partItem = compound.get();

            ItemStack sample = new ItemStack(partItem);
            GearPart gearPart = SgRegistries.PART.fromItem(sample);
            if (gearPart == null) continue;

            boolean isRequired = required.contains(partType);
            if (!isRequired) {
                // Silent Gear's own check for whether this gear item accepts this kind of part
                PartInstance instance = PartInstance.of(gearPart, sample);
                if (!gearItem.supportsPart(probe, instance)) continue;
            }

            SlotDef def = new SlotDef(partType, typeId, partItem, gearPart, isRequired,
                    recipes.get(partItem), recipes.fixed(partItem));
            (isRequired ? requiredSlots : optionalSlots).add(def);
        }

        requiredSlots.sort(Comparator.comparingInt(d -> required.indexOf(d.type())));
        List<SlotDef> all = new ArrayList<>(requiredSlots);
        all.addAll(optionalSlots);
        return List.copyOf(all);
    }

    // ---------------------------------------------------------------------------------------------
    // Non-material upgrades

    public List<UpgradeDef> upgradesFor(GearItem gearItem) {
        return upgradeCache.computeIfAbsent(gearItem.asItem(), k -> computeUpgrades(gearItem));
    }

    private List<UpgradeDef> computeUpgrades(GearItem gearItem) {
        ItemStack probe = new ItemStack(gearItem);
        List<UpgradeDef> list = new ArrayList<>();
        for (GearPart part : SgRegistries.PART) {
            ItemStack[] stacks = part.getIngredient().getItems();
            if (stacks.length == 0) continue;
            ItemStack icon = stacks[0].copyWithCount(1);
            if (icon.getItem() instanceof CompoundPartItem) continue; // material-based parts are slots, not upgrades

            ResourceLocation id = SgRegistries.PART.getKey(part);
            if (id == null) continue;
            PartInstance instance = PartInstance.of(part, icon);
            if (!gearItem.supportsPart(probe, instance)) continue;

            list.add(new UpgradeDef(id, part, icon, part.getDisplayName(instance)));
        }
        list.sort(Comparator.comparing(u -> u.name().getString()));
        return List.copyOf(list);
    }

    // ---------------------------------------------------------------------------------------------
    // Materials

    /**
     * The item a player would actually put in the crafting grid for this material: the first item of
     * its ingredient that Silent Gear resolves back to this same material (children like specific wood
     * types win over their parent otherwise).
     */
    public ItemStack displayStack(ResourceLocation materialId) {
        return displayStackCache.computeIfAbsent(materialId, id -> {
            Material material = SgRegistries.MATERIAL.get(id);
            if (material == null) return ItemStack.EMPTY;
            for (ItemStack candidate : material.getIngredient().getItems()) {
                ItemStack one = candidate.copyWithCount(1);
                MaterialInstance resolved = MaterialInstance.from(one);
                if (resolved != null && id.equals(resolved.getId())) {
                    return one;
                }
            }
            return ItemStack.EMPTY;
        });
    }

    /** A material item carrying the given grade, exactly as it would come out of a Material Grader. */
    public ItemStack gradedStack(ResourceLocation materialId, MaterialGrade grade) {
        ItemStack base = displayStack(materialId);
        if (base.isEmpty()) return ItemStack.EMPTY;
        return grade.copyWithGrade(base);
    }

    /** Materials the server would accept for this slot (at some grade). */
    public List<ResourceLocation> materialsFor(SlotDef slot) {
        return materialCache.computeIfAbsent(slotKey(slot), k -> computeMaterials(slot));
    }

    /** How this material is obtained for this slot, or null if it is not offered. */
    @Nullable
    public PartSource source(SlotDef slot, @Nullable ResourceLocation materialId) {
        if (materialId == null) return null;
        materialsFor(slot); // fills sourceCache
        return sourceCache.getOrDefault(slotKey(slot), Map.of()).get(materialId);
    }

    /** Recipe types that make this part from a material chosen while running (for OTHER sources). */
    public net.minecraft.network.chat.Component otherMethodName(SlotDef slot) {
        Set<ResourceLocation> types = recipes.dynamicTypes(slot.item());
        if (types.isEmpty()) return net.minecraft.network.chat.Component.translatable("sgsim.other_method.unknown");
        StringJoiner joiner = new StringJoiner(", ");
        for (ResourceLocation t : types) joiner.add(t.toString());
        return net.minecraft.network.chat.Component.literal(joiner.toString());
    }

    /** True when the part comes out exactly as a recipe/item makes it, so grades can't be chosen. */
    public boolean isGradeLocked(SlotDef slot, @Nullable ResourceLocation materialId) {
        PartSource src = source(slot, materialId);
        if (src == PartSource.FIXED_RECIPE) return !GradingRules.partGradingAllowed(); // grader can grade parts
        return src != null && src.gradeLocked;
    }

    /** The item that Silent Gear accepts as this part directly (e.g. a blaze rod as a rod), or empty. */
    public ItemStack substituteStack(SlotDef slot, ResourceLocation materialId) {
        Material material = SgRegistries.MATERIAL.get(materialId);
        if (material == null || !material.hasPartSubstitutes()) return ItemStack.EMPTY;
        Optional<net.minecraft.world.item.crafting.Ingredient> ing = material.getPartSubstitute(slot.type());
        if (ing.isEmpty()) return ItemStack.EMPTY;
        for (ItemStack candidate : ing.get().getItems()) {
            ItemStack one = candidate.copyWithCount(1);
            // Same lookup Silent Gear does when that item is put in a gear recipe
            PartInstance part = PartInstance.from(one);
            if (part != null && part.isValid() && part.getType() == slot.type()) return one;
        }
        return ItemStack.EMPTY;
    }

    private static String slotKey(SlotDef slot) {
        return BuiltInRegistries.ITEM.getKey(slot.item()) + "|" + slot.typeId();
    }

    private List<ResourceLocation> computeMaterials(SlotDef slot) {
        Map<ResourceLocation, PartSource> sources = new LinkedHashMap<>();
        int count = slot.defaultCount();
        for (Material material : SgRegistries.MATERIAL.getValues(true)) {
            ResourceLocation id = SgRegistries.MATERIAL.getKey(material);
            if (id == null) continue;
            ItemStack plain = displayStack(id);
            // 1. The crafting-grid part recipe takes it (at some grade)
            if (!plain.isEmpty() && (slot.accepts(plain, count)
                    || slot.accepts(MaterialGrade.getMax().copyWithGrade(plain), count))) {
                sources.put(id, PartSource.CRAFTING);
                continue;
            }
            // 2. A recipe outputs this part made of it (casting etc.)
            if (!slot.fixedFor(id).isEmpty()) {
                sources.put(id, PartSource.FIXED_RECIPE);
                continue;
            }
            // 3. Its item is the part (blaze rod -> rod)
            if (!substituteStack(slot, id).isEmpty()) {
                sources.put(id, PartSource.SUBSTITUTE);
                continue;
            }
            // 4. Silent Gear allows it here and it is obtainable, but the route isn't a recipe we can read
            if (!plain.isEmpty()) {
                MaterialInstance inst = MaterialInstance.of(material, plain);
                if (inst.allowedInPart(slot.type()) && inst.isCraftingAllowed(slot.type(), slot.item().getGearType())) {
                    sources.put(id, PartSource.OTHER);
                }
            }
        }
        sourceCache.put(slotKey(slot), sources);

        List<ResourceLocation> list = new ArrayList<>(sources.keySet());
        list.sort(Comparator.comparing(id -> materialName(id, slot.type()).getString()));
        return List.copyOf(list);
    }

    /** Icon for a material in a slot: its crafting item, or the item stored in a cast part's data. */
    public ItemStack icon(SlotDef slot, ResourceLocation materialId) {
        ItemStack plain = displayStack(materialId);
        if (!plain.isEmpty()) return plain;
        ItemStack substitute = substituteStack(slot, materialId);
        if (!substitute.isEmpty()) return substitute;
        for (RecipeIndex.FixedPart fp : slot.fixedFor(materialId)) {
            if (!fp.materials().isEmpty() && !fp.materials().getFirst().getItem().isEmpty()) {
                return fp.materials().getFirst().getItem();
            }
        }
        return ItemStack.EMPTY;
    }

    public static net.minecraft.network.chat.Component materialName(@Nullable ResourceLocation id, PartType partType) {
        if (id == null) return net.minecraft.network.chat.Component.translatable("sgsim.none");
        Material material = SgRegistries.MATERIAL.get(id);
        if (material == null) return net.minecraft.network.chat.Component.literal(id.toString());
        return MaterialInstance.of(material).getDisplayName(partType, ItemStack.EMPTY);
    }

    /** Plain translated item name (no per-stack data). */
    public static net.minecraft.network.chat.Component itemName(Item item) {
        return net.minecraft.network.chat.Component.translatable(item.getDescriptionId());
    }

    @Nullable
    public static GearItem gearItem(@Nullable ResourceLocation id) {
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item instanceof GearItem gi ? gi : null;
    }
}
