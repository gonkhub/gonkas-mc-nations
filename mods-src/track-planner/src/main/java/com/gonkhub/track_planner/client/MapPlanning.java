package com.gonkhub.track_planner.client;

import com.gonkhub.track_planner.BlueprintsPayload;
import com.gonkhub.track_planner.MapPlanRequest;
import com.gonkhub.track_planner.TrackPlanner;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Planning a line from Xaero's World Map, Factorio style. The rail button (top left, under Create's
 * train map toggle) turns planning on; then left-click the start, a guide line follows the cursor, and
 * left-click the destination. The server plans it and the new blueprint appears on the map and as ghost
 * track in the world. Right-click drops the start, or turns planning off when there is none.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT)
public final class MapPlanning {
    public static final int BUTTON_X = 3, BUTTON_Y = 46, BUTTON_W = 26, BUTTON_H = 14;
    static final int BLUE = 0xFF78CDFF, DARK = 0xC0102030, WHITE = 0xFFFFFFFF, GREY = 0xFF9AA4AE;

    static boolean active;
    /** World x,z of the start, once clicked. */
    static double[] start;
    /** Waiting for the server: when the request went out, and which blueprint set was current then. */
    static long pendingSince;
    static int pendingVersion;
    /** A click handled here also has its release kept from the map. */
    static boolean swallowRelease;

    static boolean overButton(double mx, double my) {
        return mx >= BUTTON_X && my >= BUTTON_Y && mx < BUTTON_X + BUTTON_W && my < BUTTON_Y + BUTTON_H;
    }

    static boolean pending() {
        if (pendingSince == 0) return false;
        if (BlueprintsPayload.version != pendingVersion || System.currentTimeMillis() - pendingSince > 60_000) pendingSince = 0;
        return pendingSince != 0;
    }

    @SubscribeEvent
    static void click(ScreenEvent.MouseButtonPressed.Pre e) {
        Screen screen = e.getScreen();
        if (!XaeroMapOverlay.isMap(screen)) return;
        double mx = e.getMouseX(), my = e.getMouseY();
        if (overButton(mx, my) && e.getButton() == 0) {
            active = !active;
            start = null;
            swallow(e);
            return;
        }
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (e.getButton() == 1) {
            if (start != null) start = null;
            else active = false;
            swallow(e);
            return;
        }
        if (e.getButton() != 0 || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        XaeroMapOverlay.MapView v = XaeroMapOverlay.view(screen);
        if (v == null) return;
        double wx = v.worldX(mx), wz = v.worldZ(my);
        if (start == null) {
            start = new double[]{wx, wz};
        } else if (!pending()) {
            PacketDistributor.sendToServer(new MapPlanRequest((int) Math.floor(start[0]), (int) Math.floor(start[1]), (int) Math.floor(wx), (int) Math.floor(wz)));
            pendingSince = System.currentTimeMillis();
            pendingVersion = BlueprintsPayload.version;
            start = null;
        }
        swallow(e);
    }

    static void swallow(ScreenEvent.MouseButtonPressed.Pre e) {
        e.setCanceled(true);
        swallowRelease = true;
    }

    @SubscribeEvent
    static void release(ScreenEvent.MouseButtonReleased.Pre e) {
        if (swallowRelease && XaeroMapOverlay.isMap(e.getScreen())) {
            swallowRelease = false;
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void render(ScreenEvent.Render.Post e) {
        Screen screen = e.getScreen();
        XaeroMapOverlay.MapView v = XaeroMapOverlay.view(screen);
        if (v == null) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = e.getGuiGraphics();
        int mx = e.getMouseX(), my = e.getMouseY();
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        Matrix4f m = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());

        // the button: a little length of track, blue while planning is on
        boolean hover = overButton(mx, my);
        int fg = active ? BLUE : hover ? WHITE : GREY;
        XaeroMapOverlay.quad(vc, m, BUTTON_X, BUTTON_Y, BUTTON_X, BUTTON_Y + BUTTON_H, BUTTON_X + BUTTON_W, BUTTON_Y + BUTTON_H, BUTTON_X + BUTTON_W, BUTTON_Y, active ? 0xE0203850 : DARK);
        for (int i = 0; i < 5; i++) {
            float x = BUTTON_X + 4 + i * 4.5f;
            XaeroMapOverlay.quad(vc, m, x, BUTTON_Y + 3, x, BUTTON_Y + BUTTON_H - 3, x + 1.5f, BUTTON_Y + BUTTON_H - 3, x + 1.5f, BUTTON_Y + 3, fg & 0x99FFFFFF);
        }
        XaeroMapOverlay.segment(vc, m, BUTTON_X + 2, BUTTON_Y + 4.5f, BUTTON_X + BUTTON_W - 2, BUTTON_Y + 4.5f, 1.5f, fg);
        XaeroMapOverlay.segment(vc, m, BUTTON_X + 2, BUTTON_Y + BUTTON_H - 4.5f, BUTTON_X + BUTTON_W - 2, BUTTON_Y + BUTTON_H - 4.5f, 1.5f, fg);

        // while choosing the destination: a straight guide from the start to the cursor
        if (active && start != null) {
            float sx = (float) v.screenX(start[0]), sy = (float) v.screenY(start[1]);
            XaeroMapOverlay.segment(vc, m, sx, sy, mx, my, 3.5f, DARK);
            float len = (float) Math.hypot(mx - sx, my - sy);
            for (float d = 0; d < len; d += 8) {
                float t0 = d / len, t1 = Math.min(1, (d + 5) / len);
                XaeroMapOverlay.segment(vc, m, sx + (mx - sx) * t0, sy + (my - sy) * t0, sx + (mx - sx) * t1, sy + (my - sy) * t1, 1.8f, BLUE);
            }
            XaeroMapOverlay.square(vc, m, sx, sy, 9, DARK);
            XaeroMapOverlay.square(vc, m, sx, sy, 7, WHITE);
        }
        g.flush();

        String banner = null;
        if (pending()) banner = "Planning the line...";
        else if (active && start == null) banner = "Track planning: click where the line starts (right-click to stop)";
        else if (active) banner = "Click the destination (right-click to drop the start)";
        if (banner != null) {
            int w = mc.font.width(banner);
            g.fill(screen.width / 2 - w / 2 - 4, 22, screen.width / 2 + w / 2 + 4, 35, DARK);
            g.drawString(mc.font, banner, screen.width / 2 - w / 2, 25, active || pending() ? BLUE : WHITE, false);
        }
        if (active && start != null) {
            double dist = Math.hypot(v.worldX(mx) - start[0], v.worldZ(my) - start[1]);
            g.drawString(mc.font, String.format("%.0f blocks", dist), mx + 10, my + 10, WHITE, true);
        }
        g.pose().popPose();
        if (hover)
            g.renderComponentTooltip(mc.font, List.of(Component.literal(active ? "Stop planning track" : "Plan track"),
                    Component.literal("Click the start, then the destination").withStyle(s -> s.withColor(GREY))), mx, my);
    }

    private MapPlanning() {}
}
