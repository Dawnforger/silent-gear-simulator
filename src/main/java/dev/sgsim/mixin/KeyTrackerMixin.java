package dev.sgsim.mixin;

import dev.sgsim.client.TooltipToggles;
import net.silentchaos512.gear.client.KeyTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Silent Gear decides whether to expand its tooltips by asking KeyTracker whether Ctrl / Shift / Alt
 * are held. While the simulator is open and the matching checkbox is ticked, answer "yes".
 */
@Mixin(value = KeyTracker.class, remap = false)
public abstract class KeyTrackerMixin {
    @Inject(method = "isDisplayPropertiesDown", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sgsim$properties(CallbackInfoReturnable<Boolean> cir) {
        if (TooltipToggles.properties && TooltipToggles.active()) cir.setReturnValue(true);
    }

    @Inject(method = "isDisplayTraitDescriptionsDown", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sgsim$traits(CallbackInfoReturnable<Boolean> cir) {
        if (TooltipToggles.traits && TooltipToggles.active()) cir.setReturnValue(true);
    }

    @Inject(method = "isDisplayConstructionDown", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sgsim$construction(CallbackInfoReturnable<Boolean> cir) {
        if (TooltipToggles.construction && TooltipToggles.active()) cir.setReturnValue(true);
    }

    /** Silent Gear also shows trait descriptions when Alt itself is held (TraitInstance#addInformation). */
    @Inject(method = "isAltDown", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sgsim$alt(CallbackInfoReturnable<Boolean> cir) {
        if (TooltipToggles.traits && TooltipToggles.active()) cir.setReturnValue(true);
    }
}
