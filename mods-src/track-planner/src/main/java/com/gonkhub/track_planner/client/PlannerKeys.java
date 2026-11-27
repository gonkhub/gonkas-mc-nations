package com.gonkhub.track_planner.client;

import com.gonkhub.track_planner.ToggleBuildRequest;
import com.gonkhub.track_planner.TrackPlanner;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** The "toggle building" key (B by default, under Controls > Track Planner): works while holding a Rail Planner. */
public final class PlannerKeys {
    public static final KeyMapping TOGGLE_BUILD = new KeyMapping("key.track_planner.toggle_build", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.track_planner");

    @EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class Registration {
        @SubscribeEvent
        static void keys(RegisterKeyMappingsEvent e) { e.register(TOGGLE_BUILD); }
    }

    @EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT)
    static final class Presses {
        @SubscribeEvent
        static void tick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            while (TOGGLE_BUILD.consumeClick()) {
                if (mc.player == null) continue;
                if (mc.player.getMainHandItem().is(TrackPlanner.RAIL_PLANNER.get()) || mc.player.getOffhandItem().is(TrackPlanner.RAIL_PLANNER.get()))
                    PacketDistributor.sendToServer(ToggleBuildRequest.INSTANCE);
            }
        }
    }

    private PlannerKeys() {}
}
