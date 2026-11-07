package dev.sgsim.sim;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.silentchaos512.gear.Config;
import net.silentchaos512.gear.api.part.MaterialGrade;
import net.silentchaos512.gear.gear.material.MaterialInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mirrors Silent Gear's Material Grader rules for finished part items (GraderBlockEntity).
 */
public final class GradingRules {
    private GradingRules() {}

    /**
     * Same test as GraderBlockEntity#isGradingPartsAllowed: the "can_grade_parts" config, or Silent Gear
     * Metalworks being installed (as in ATM10 / ATM10 Aeronautics), lets the grader grade the materials
     * inside a part, one unit per pass. That is how cast parts get grades.
     */
    public static boolean partGradingAllowed() {
        try {
            if (ModList.get().isLoaded("sgearmetalworks")) return true;
            return Config.Common.isLoaded() && Config.Common.graderCanGradeParts.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The material list of a ready-made part after it has been through the grader until its units carry
     * the given grades. The grader keeps the highest grade first, so the list is sorted the same way.
     */
    public static List<MaterialInstance> gradeUnits(List<MaterialInstance> materials, List<MaterialGrade> grades) {
        List<MaterialInstance> out = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            MaterialInstance m = materials.get(i);
            MaterialGrade grade = i < grades.size() ? grades.get(i) : MaterialGrade.NONE;
            out.add(withGrade(m, grade));
        }
        out.sort(Comparator.comparingInt((MaterialInstance m) -> MaterialGrade.fromStack(m.getItem()).ordinal()).reversed());
        return out;
    }

    public static MaterialInstance withGrade(MaterialInstance m, MaterialGrade grade) {
        if (grade == MaterialGrade.NONE || m.getItem().isEmpty()) return m;
        ItemStack stack = m.getItem().copy();
        grade.setGradeOnStack(stack);
        return MaterialInstance.of(m.get(), stack);
    }
}
