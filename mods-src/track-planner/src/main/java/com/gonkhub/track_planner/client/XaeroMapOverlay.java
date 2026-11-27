package com.gonkhub.track_planner.client;

import com.gonkhub.track_planner.TrackPlanner;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Blueprints on Xaero's World Map: each one drawn as a light-blue line with a dark edge, a marker at each
 * end and its number at the start; hovering a line shows what it is. Xaero has no overlay API, so (like
 * Create's own train map) this reads the map screen's camera and zoom: here by reflection after the screen
 * has drawn, so there is no dependency on Xaero at all. If the map mod is absent or its fields change, the
 * overlay turns itself off and says so once in the log.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT)
public final class XaeroMapOverlay {
    static final String GUI_MAP = "xaero.map.gui.GuiMap";
    static final int LINE = 0xFF78CDFF, EDGE = 0xC0102030, MARK = 0xFFFFFFFF;
    static final float LINE_PX = 2.2f, EDGE_PX = 4.2f, MARK_PX = 7f;

    static Field cameraX, cameraZ, scale;
    static boolean broken;

    @SubscribeEvent
    static void render(ScreenEvent.Render.Post e) {
        Screen screen = e.getScreen();
        if (broken || !screen.getClass().getName().equals(GUI_MAP)) return;
        Minecraft mc = Minecraft.getInstance();
        // blueprints are planned in the overworld
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;
        List<GhostTrackRenderer.Line> lines = GhostTrackRenderer.lines();
        if (lines.isEmpty()) return;
        double camX, camZ, mapScale;
        try {
            if (cameraX == null) {
                cameraX = field(screen.getClass(), "cameraX");
                cameraZ = field(screen.getClass(), "cameraZ");
                scale = field(screen.getClass(), "scale");
            }
            camX = cameraX.getDouble(screen);
            camZ = cameraZ.getDouble(screen);
            mapScale = scale.getDouble(screen);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
            LogUtils.getLogger().warn("Track Planner: can't read Xaero's World Map camera, map overlay off ({})", ex.toString());
            return;
        }

        // the same conversion Create's train map uses: map units to GUI pixels
        Window window = mc.getWindow();
        double guiScale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
        double interfaceScale = (double) window.getWidth() / window.getScreenWidth();
        double s = mapScale / guiScale / interfaceScale;
        double cx = screen.width / 2.0, cz = screen.height / 2.0;

        GuiGraphics g = e.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Matrix4f m = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        int mouseX = e.getMouseX(), mouseY = e.getMouseY();
        GhostTrackRenderer.Line hovered = null;
        double hoveredDist = 6;

        for (int pass = 0; pass < 2; pass++) {
            float px = pass == 0 ? EDGE_PX : LINE_PX;
            int color = pass == 0 ? EDGE : LINE;
            for (GhostTrackRenderer.Line line : lines) {
                List<float[]> sp = screenPoints(line.points(), camX, camZ, s, cx, cz, screen.width, screen.height);
                for (int i = 0; i + 1 < sp.size(); i++) {
                    float[] a = sp.get(i), b = sp.get(i + 1);
                    if (a == null || b == null) continue;
                    segment(vc, m, a[0], a[1], b[0], b[1], px, color);
                    if (pass == 1) {
                        double d = distance(mouseX, mouseY, a[0], a[1], b[0], b[1]);
                        if (d < hoveredDist) { hoveredDist = d; hovered = line; }
                    }
                }
                if (pass == 1)
                    for (Vec3 end : new Vec3[]{line.start(), line.end()}) {
                        float ex = (float) ((end.x - camX) * s + cx), ez = (float) ((end.z - camZ) * s + cz);
                        square(vc, m, ex, ez, MARK_PX + 2, EDGE);
                        square(vc, m, ex, ez, MARK_PX, end == line.start() ? MARK : LINE);
                    }
            }
        }
        g.flush();
        for (GhostTrackRenderer.Line line : lines) {
            float ex = (float) ((line.start().x - camX) * s + cx), ez = (float) ((line.start().z - camZ) * s + cz);
            if (ex > -20 && ez > -20 && ex < screen.width + 20 && ez < screen.height + 20)
                g.drawString(mc.font, "#" + line.id(), (int) ex + 6, (int) ez - 12, MARK, true);
        }
        g.pose().popPose();

        if (hovered != null)
            g.renderComponentTooltip(mc.font, List.of(
                    Component.literal("Track blueprint #" + hovered.id()),
                    Component.literal(String.format("%.0f blocks, about %d track items", hovered.length(), hovered.trackItems())),
                    Component.literal("planned by " + hovered.owner()),
                    Component.literal("/trackplan build " + hovered.id())), mouseX, mouseY);
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Line points in screen pixels, thinned to roughly one per two pixels; null where far off screen. */
    static List<float[]> screenPoints(List<Vec3> pts, double camX, double camZ, double s, double cx, double cz, int w, int h) {
        List<float[]> out = new ArrayList<>();
        float lastX = Float.NaN, lastZ = Float.NaN;
        for (int i = 0; i < pts.size(); i++) {
            Vec3 p = pts.get(i);
            float x = (float) ((p.x - camX) * s + cx), z = (float) ((p.z - camZ) * s + cz);
            boolean last = i == pts.size() - 1;
            if (!last && !Float.isNaN(lastX) && Math.abs(x - lastX) + Math.abs(z - lastZ) < 2) continue;
            boolean visible = x > -w && z > -h && x < 2 * w && z < 2 * h;
            out.add(visible ? new float[]{x, z} : null);
            lastX = x; lastZ = z;
        }
        return out;
    }

    static void segment(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float width, int argb) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float nx = -dy / len * width / 2, ny = dx / len * width / 2;
        // extend a little so joints between segments have no gaps
        float ex = dx / len * width / 2, ey = dy / len * width / 2;
        quad(vc, m, x1 - ex + nx, y1 - ey + ny, x1 - ex - nx, y1 - ey - ny, x2 + ex - nx, y2 + ey - ny, x2 + ex + nx, y2 + ey + ny, argb);
    }

    static void square(VertexConsumer vc, Matrix4f m, float x, float y, float size, int argb) {
        float h = size / 2;
        quad(vc, m, x - h, y - h, x - h, y + h, x + h, y + h, x + h, y - h, argb);
    }

    static void quad(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int argb) {
        // the GUI render type culls back faces, and a segment's winding depends on its direction: draw both
        vc.addVertex(m, x1, y1, 0).setColor(argb);
        vc.addVertex(m, x2, y2, 0).setColor(argb);
        vc.addVertex(m, x3, y3, 0).setColor(argb);
        vc.addVertex(m, x4, y4, 0).setColor(argb);
        vc.addVertex(m, x4, y4, 0).setColor(argb);
        vc.addVertex(m, x3, y3, 0).setColor(argb);
        vc.addVertex(m, x2, y2, 0).setColor(argb);
        vc.addVertex(m, x1, y1, 0).setColor(argb);
    }

    static double distance(double px, double py, float x1, float y1, float x2, float y2) {
        double dx = x2 - x1, dy = y2 - y1, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / l2));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private XaeroMapOverlay() {}
}
