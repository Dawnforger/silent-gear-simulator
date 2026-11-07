package dev.sgsim.sim;

import net.minecraft.resources.ResourceLocation;
import net.silentchaos512.gear.api.item.GearType;
import net.silentchaos512.gear.api.part.PartType;
import net.silentchaos512.gear.api.property.ComputeContext;
import net.silentchaos512.gear.api.property.GearProperty;
import net.silentchaos512.gear.api.property.NumberProperty;
import net.silentchaos512.gear.api.property.NumberPropertyValue;
import net.silentchaos512.gear.api.traits.TraitInstance;
import net.silentchaos512.gear.api.util.PartGearKey;
import net.silentchaos512.gear.api.util.PropertyKey;
import net.silentchaos512.gear.gear.material.MaterialInstance;
import net.silentchaos512.gear.setup.SgRegistries;
import net.silentchaos512.gear.setup.gear.GearTypes;

import java.util.ArrayList;
import java.util.List;

/** Reads a material's stats and traits for one part type, including grade bonuses. */
public final class StatValues {
    private StatValues() {}

    /** Every number stat Silent Gear (and add-ons) registered, e.g. durability, harvest speed, armor. */
    public static List<NumberProperty> numberProperties() {
        List<NumberProperty> list = new ArrayList<>();
        for (GearProperty<?, ?> p : SgRegistries.GEAR_PROPERTY) {
            if (p instanceof NumberProperty np) list.add(np);
        }
        return list;
    }

    public static String key(NumberProperty property) {
        ResourceLocation id = SgRegistries.GEAR_PROPERTY.getKey(property);
        return id != null ? id.toString() : property.getDisplayName().getString();
    }

    /**
     * The value this material gives the stat in this part, for this gear type, or NaN if the material
     * doesn't touch the stat. Uses {@link MaterialInstance#getPropertyModifiers}, which is where grade
     * bonuses are applied, so different grades sort differently. Gear-type-specific entries (e.g. armor
     * values per armor piece) are included when they match the gear type being planned.
     */
    @SuppressWarnings("unchecked")
    public static double value(MaterialInstance material, PartType partType, GearType gearType, NumberProperty property) {
        List<NumberPropertyValue> mods = new ArrayList<>(
                material.getPropertyModifiers(partType, PropertyKey.of(property, GearTypes.ALL.get())));
        if (material.isValid()) {
            for (PropertyKey<?, ?> key : material.get().getPropertyKeys(material, partType)) {
                if (key.property() == property && key.gearType() != GearTypes.ALL.get() && gearType.matches(key.gearType())) {
                    mods.addAll(material.getPropertyModifiers(partType, (PropertyKey<Float, NumberPropertyValue>) key));
                }
            }
        }
        if (mods.isEmpty()) return Double.NaN;
        return property.compute(ComputeContext.material(material, partType), mods);
    }

    /** Trait names (with level) this material gives in this part, for searching. */
    public static String traitText(MaterialInstance material, PartType partType, GearType gearType) {
        StringBuilder sb = new StringBuilder();
        try {
            for (TraitInstance trait : material.getTraits(PartGearKey.of(gearType, partType))) {
                sb.append(' ').append(trait.getDisplayName().getString());
            }
        } catch (Throwable ignored) {
            // A broken trait shouldn't break searching
        }
        return sb.toString();
    }
}
