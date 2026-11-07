package dev.sgsim.sim;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.silentchaos512.gear.api.part.GearPart;

/** A non-material upgrade part (spoon, magnetic, wide plate, red card, ...) that can be applied to a gear item. */
public record UpgradeDef(ResourceLocation id, GearPart part, ItemStack icon, Component name) {
}
