package com.gonkhub.track_planner.client;

import com.gonkhub.track_planner.PlannerKeyRequest;
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

/** Rail Planner keys, under Controls > Track Planner; they work while holding a Rail Planner. */
public final class PlannerKeys {
    /** B: building on or off. */
    public static final KeyMapping TOGGLE_BUILD = new KeyMapping("key.track_planner.toggle_build", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.track_planner");
    /** N: single track, double track on the right, double track on the left. */
    public static final KeyMapping CYCLE_PARALLEL = new KeyMapping("key.track_planner.cycle_parallel", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.track_planner");

    @EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class Registration {
        @SubscribeEvent
        static void keys(RegisterKeyMappingsEvent e) { e.register(TOGGLE_BUILD); e.register(CYCLE_PARALLEL); }
    }

    @EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT)
    static final class Presses {
        @SubscribeEvent
        static void tick(ClientTickEvent.Post e) {
            send(TOGGLE_BUILD, PlannerKeyRequest.TOGGLE_BUILD);
            send(CYCLE_PARALLEL, PlannerKeyRequest.CYCLE_PARALLEL);
        }

        static void send(KeyMapping key, int which) {
            Minecraft mc = Minecraft.getInstance();
            while (key.consumeClick()) {
                if (mc.player == null) continue;
                if (mc.player.getMainHandItem().is(TrackPlanner.RAIL_PLANNER.get()) || mc.player.getOffhandItem().is(TrackPlanner.RAIL_PLANNER.get()))
                    PacketDistributor.sendToServer(new PlannerKeyRequest(which));
            }
        }
    }

    private PlannerKeys() {}
}
