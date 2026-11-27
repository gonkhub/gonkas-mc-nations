package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.TrackPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ./gradlew runCamera (with a world in run/saves/tracks): flies a spectator to each line of
 * run/viewpoints.txt ("name x y z yaw pitch"), waits for the view to finish rendering, saves
 * run/screenshots/name.png, then quits.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT)
public final class Camera {
    record View(String name, double x, double y, double z, float yaw, float pitch) {}

    static List<View> views;
    static int index = -1, wait, settled;
    static boolean setUp;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) throws Exception {
        if (!Boolean.getBoolean("track_planner.camera")) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) return;
        if (views == null) {
            views = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of("viewpoints.txt"))) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 6) views.add(new View(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5])));
            }
        }
        if (!setUp) {
            setUp = true;
            mc.options.hideGui = true;
            mc.options.renderDistance().set(10);
            server.execute(() -> {
                ServerLevel level = server.overworld();
                level.setDayTime(6000);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setWeatherParameters(60000, 0, false, false);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) p.setGameMode(GameType.SPECTATOR);
            });
            next(mc, server);
            return;
        }
        if (index >= views.size()) return;
        wait++;
        // wait until every section in view is built and stays built for 2 s, at most 200 s
        settled = mc.levelRenderer.hasRenderedAllSections() ? settled + 1 : 0;
        if ((wait > 100 && settled > 40) || wait > 4000) {
            View v = views.get(index);
            Screenshot.grab(mc.gameDirectory, v.name() + ".png", mc.getMainRenderTarget(), msg -> {});
            System.out.println("[camera] " + v.name() + " after " + wait + " ticks");
            next(mc, server);
        }
    }

    static void next(Minecraft mc, MinecraftServer server) {
        index++;
        wait = 0;
        settled = 0;
        if (index >= views.size()) {
            System.out.println("[camera] done");
            mc.execute(mc::stop);
            return;
        }
        View v = views.get(index);
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers())
                p.teleportTo(server.overworld(), v.x(), v.y(), v.z(), v.yaw(), v.pitch());
        });
    }

    private Camera() {}
}
