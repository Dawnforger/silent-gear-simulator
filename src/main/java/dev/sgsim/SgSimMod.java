package dev.sgsim;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sgsim.client.SimulatorScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = SgSimMod.MOD_ID, dist = Dist.CLIENT)
public final class SgSimMod {
    public static final String MOD_ID = "sgsim";
    public static final Logger LOGGER = LoggerFactory.getLogger("SGSim");

    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.sgsim.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.sgsim"
    );

    public SgSimMod(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(OPEN_KEY));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onClientTick());
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.level != null && mc.player != null && mc.screen == null) {
                mc.setScreen(new SimulatorScreen());
            }
        }
    }
}
