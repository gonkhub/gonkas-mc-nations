package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.TrackPlanner;
import com.gonkhub.track_planner.Blueprint;
import com.gonkhub.track_planner.Moves;
import com.gonkhub.track_planner.Planner;
import com.gonkhub.track_planner.Terrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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

    static volatile List<View> views;
    static int index = -1, wait, settled;
    static boolean setUp;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) throws Exception {
        if (!Boolean.getBoolean("track_planner.camera")) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) return;
        String ghost = System.getProperty("track_planner.cameraBlueprint", "");
        if (views == null && !ghost.isEmpty()) {
            // plan a blueprint on the integrated server, then look at its ghost from three places along it
            views = List.of();
            String[] g = ghost.trim().split("\\s+");
            server.execute(() -> views = ghostViews(server, Integer.parseInt(g[0]), Integer.parseInt(g[1]), Integer.parseInt(g[2]), Integer.parseInt(g[3])));
            return;
        }
        if (views != null && views.isEmpty()) return;
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
        if (mapPhase >= 0) { mapTick(mc, server); return; }
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

    static List<View> ghostViews(MinecraftServer server, int sdx, int sdz, int gdx, int gdz) {
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        int sx = spawn.getX() + sdx, sz = spawn.getZ() + sdz, gx = spawn.getX() + gdx, gz = spawn.getZ() + gdz;
        BlockPos start = new BlockPos(sx, real.surface(sx, sz) + 1, sz), goal = new BlockPos(gx, real.surface(gx, gz) + 1, gz);
        Blueprint b = TrackPlanner.planBlueprint(level, start, goal, "camera", m -> System.out.println("[camera] " + m.getString()));
        List<View> out = new ArrayList<>();
        if (b == null) return out;
        int i = 0;
        for (double f : new double[]{0.2, 0.5, 0.8}) {
            Planner.Anchor a = b.anchors().get((int) (f * (b.anchors().size() - 1)));
            int[] d = Moves.DIR[a.dir()];
            double len = Math.hypot(d[0], d[1]), fx = d[0] / len, fz = d[1] / len;
            double cx = a.x() + 0.5 - fx * 16 - fz * 10, cz = a.z() + 0.5 - fz * 16 + fx * 10, cy = a.y() + 11;
            double vx = a.x() + 0.5 + fx * 8 - cx, vy = a.y() - cy, vz = a.z() + 0.5 + fz * 8 - cz;
            float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz)), pitch = (float) -Math.toDegrees(Math.atan2(vy, Math.hypot(vx, vz)));
            out.add(new View("ghost_" + (++i), cx, cy, cz, yaw, pitch));
        }
        System.out.println("[camera] ghost blueprint #" + b.id() + " planned, " + out.size() + " views");
        Planner.Anchor mid = b.anchors().get(b.anchors().size() / 2);
        mapCenter = mid.pos();
        return out;
    }

    static int mapPhase = -1, mapWait;
    static volatile BlockPos mapCenter;

    /** Opens Xaero's World Map with its own key, lets it load, and takes a screenshot of the overlay. */
    static void mapTick(Minecraft mc, MinecraftServer server) {
        mapWait++;
        if (mapPhase == 0 && mapWait > 300) {
            for (KeyMapping k : mc.options.keyMappings)
                if (k.getName().equals("gui.xaero_open_map")) KeyMapping.click(k.getKey());
            System.out.println("[camera] pressed the world map key");
            mapPhase = 1;
            mapWait = 0;
        } else if (mapPhase == 1 && mapWait > 400) {
            String screen = mc.screen == null ? "none" : mc.screen.getClass().getName();
            Screenshot.grab(mc.gameDirectory, "map_1.png", mc.getMainRenderTarget(), msg -> {});
            System.out.println("[camera] map_1 (screen: " + screen + ")");
            mapPhase = Boolean.getBoolean("track_planner.cameraMapPlan") ? 10 : 99;
            mapWait = 0;
            if (mapPhase == 99) mc.execute(mc::stop);
        } else if (mapPhase >= 10 && mapPhase < 99) {
            planOnMap(mc);
        }
    }

    // ---- planning from the map with the real mouse code: button, start, guide line, destination ----
    static double[] planStart, planGoal;
    static int versionBefore;

    static void planOnMap(Minecraft mc) {
        var v = com.gonkhub.track_planner.client.XaeroMapOverlay.view(mc.screen);
        if (v == null) { System.out.println("[camera] map not open"); mapPhase = 99; mc.execute(mc::stop); return; }
        if (planStart == null) {
            planStart = new double[]{v.camX() - 110, v.camZ() + 50};
            planGoal = new double[]{v.camX() + 95, v.camZ() - 95};
        }
        switch (mapPhase) {
            case 10 -> { mouseTo(mc, com.gonkhub.track_planner.client.MapPlanning.BUTTON_X + 10, com.gonkhub.track_planner.client.MapPlanning.BUTTON_Y + 7); step(15); }
            case 11 -> { click(mc); step(15); }
            case 12 -> { mouseTo(mc, v.screenX(planStart[0]), v.screenY(planStart[1])); step(10); }
            case 13 -> { click(mc); step(10); }
            case 14 -> {
                double fx = planStart[0] + (planGoal[0] - planStart[0]) * 0.7, fz = planStart[1] + (planGoal[1] - planStart[1]) * 0.7;
                mouseTo(mc, v.screenX(fx), v.screenY(fz));
                step(40);
            }
            case 15 -> {
                Screenshot.grab(mc.gameDirectory, "map_plan_1.png", mc.getMainRenderTarget(), msg -> {});
                System.out.println("[camera] map_plan_1 (choosing the destination)");
                step(10);
            }
            case 16 -> { mouseTo(mc, v.screenX(planGoal[0]), v.screenY(planGoal[1])); step(10); }
            case 17 -> { versionBefore = com.gonkhub.track_planner.BlueprintsPayload.version; click(mc); step(0); }
            case 18 -> {
                // wait for the new blueprint to arrive, then give the map a moment
                if (com.gonkhub.track_planner.BlueprintsPayload.version != versionBefore && mapWait > 100 || mapWait > 3000) {
                    // move the cursor onto the new line so its tooltip shows
                    mouseTo(mc, v.screenX((planStart[0] + planGoal[0]) / 2), v.screenY((planStart[1] + planGoal[1]) / 2));
                    step(20);
                }
            }
            case 19 -> {
                Screenshot.grab(mc.gameDirectory, "map_plan_2.png", mc.getMainRenderTarget(), msg -> {});
                System.out.println("[camera] map_plan_2 (blueprint arrived: " + (com.gonkhub.track_planner.BlueprintsPayload.version != versionBefore) + ")");
                mapPhase = 99;
                mc.execute(mc::stop);
            }
            default -> {}
        }
    }

    static int stepWait;

    /** Go to the next phase after waiting this many more ticks. */
    static void step(int ticks) {
        if (stepWait == 0) stepWait = ticks + 1;
        if (--stepWait <= 0) { mapPhase++; mapWait = 0; stepWait = 0; }
    }

    static void mouseTo(Minecraft mc, double guiX, double guiY) {
        try {
            double sc = mc.getWindow().getGuiScale();
            var m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
            m.setAccessible(true);
            m.invoke(mc.mouseHandler, mc.getWindow().getWindow(), guiX * sc, guiY * sc);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    static void click(Minecraft mc) {
        try {
            var m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress", long.class, int.class, int.class, int.class);
            m.setAccessible(true);
            m.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0, 1, 0);
            m.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0, 0, 0);
            System.out.printf("[camera] clicked at %.0f, %.0f%n", mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    static void next(Minecraft mc, MinecraftServer server) {
        index++;
        wait = 0;
        settled = 0;
        if (index >= views.size() && mapCenter != null && Boolean.getBoolean("track_planner.cameraMap") && mapPhase < 0) {
            // hover over the middle of the blueprint so the map has explored terrain there, then open the map
            mapPhase = 0;
            server.execute(() -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers())
                    p.teleportTo(server.overworld(), mapCenter.getX() + 0.5, mapCenter.getY() + 40, mapCenter.getZ() + 0.5, 0, 90);
            });
            return;
        }
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
