package dev.sgsim.sim;

import net.minecraft.resources.ResourceLocation;
import net.silentchaos512.gear.api.part.MaterialGrade;

import javax.annotation.Nullable;
import java.util.*;

/**
 * What the player has chosen in the simulator. Everything is stored by id, never by object, so a
 * data reload from the server (new Material/GearPart objects) never leaves stale references behind.
 */
public final class SimState {
    @Nullable
    public ResourceLocation gearItem;
    /** Keyed by part type id (silentgear:main, silentgear:rod, ...). Absent = slot left empty. */
    public final Map<ResourceLocation, PartSel> parts = new LinkedHashMap<>();
    /** Ids of non-material upgrade parts to apply. */
    public final Set<ResourceLocation> upgrades = new LinkedHashSet<>();
    /** When false, choosing a material for one unit of a part sets every unit of that part. */
    public boolean mixedMaterials = false;

    /** One part: N material units, each with its own material and grade. */
    public static final class PartSel {
        public final List<ResourceLocation> materials = new ArrayList<>();
        public final List<MaterialGrade> grades = new ArrayList<>();

        public PartSel(int count, @Nullable ResourceLocation material) {
            resize(count);
            for (int i = 0; i < count; i++) materials.set(i, material);
        }

        public int count() {
            return materials.size();
        }

        public void resize(int count) {
            count = Math.max(1, count);
            ResourceLocation fill = materials.isEmpty() ? null : materials.getLast();
            MaterialGrade gradeFill = grades.isEmpty() ? MaterialGrade.NONE : grades.getLast();
            while (materials.size() < count) materials.add(fill);
            while (grades.size() < count) grades.add(gradeFill);
            while (materials.size() > count) materials.removeLast();
            while (grades.size() > count) grades.removeLast();
        }

        public boolean hasAnyMaterial() {
            for (ResourceLocation id : materials) if (id != null) return true;
            return false;
        }

        public void setAllMaterials(@Nullable ResourceLocation id) {
            Collections.fill(materials, id);
        }

        public void setAllGrades(MaterialGrade grade) {
            Collections.fill(grades, grade);
        }
    }

    public static MaterialGrade cycleGrade(MaterialGrade grade, int direction) {
        MaterialGrade[] values = MaterialGrade.values();
        int i = Math.floorMod(grade.ordinal() + direction, values.length);
        return values[i];
    }
}
