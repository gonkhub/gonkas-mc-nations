package com.gonkhub.track_planner.client;

import com.gonkhub.track_planner.BlueprintsPayload;
import com.gonkhub.track_planner.TrackPlanner;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.track.BezierConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws every blueprint as ghost track: two light-blue rails and sleepers along each planned piece,
 * plus a beam at each end. Pure overlay, nothing is placed in the world.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID, value = Dist.CLIENT)
public final class GhostTrackRenderer {
    static final double RAIL_OFFSET = 0.5, RAIL_HALF_WIDTH = 0.07, RAIL_Y = 0.16, SLEEPER_HALF = 0.78, SLEEPER_STEP = 0.75, SLEEPER_Y = 0.07;
    static final int R = 120, G = 205, B = 255;
    static final double VIEW_DISTANCE = 192;

    /** One planned line: points along the rails (at the track bed) and its two ends. */
    record Line(List<Vec3> points, Vec3 start, Vec3 end) {}

    static int builtFor = -1;
    static List<Line> lines = List.of();

    static void rebuild() {
        List<Line> out = new ArrayList<>();
        for (Tag bt : BlueprintsPayload.latest.getList("blueprints", Tag.TAG_COMPOUND)) {
            List<Vec3> pts = new ArrayList<>();
            for (Tag pt : ((CompoundTag) bt).getList("pieces", Tag.TAG_COMPOUND)) {
                CompoundTag piece = (CompoundTag) pt;
                if (piece.getString("type").equals("straight")) {
                    int[] a = piece.getIntArray("from"), b = piece.getIntArray("to");
                    Vec3 va = Vec3.atBottomCenterOf(new BlockPos(a[0], a[1], a[2])), vb = Vec3.atBottomCenterOf(new BlockPos(b[0], b[1], b[2]));
                    int n = Math.max(1, (int) Math.ceil(va.distanceTo(vb) * 2));
                    for (int i = 0; i <= n; i++) pts.add(va.lerp(vb, i / (double) n));
                } else {
                    BezierConnection bc = new BezierConnection(piece.getCompound("curve"), BlockPos.ZERO);
                    int n = Math.max(4, (int) Math.ceil(bc.getLength() * 2));
                    // the curve runs between the two track blocks' curve starts; draw from block centre to centre
                    pts.add(Vec3.atBottomCenterOf(bc.bePositions.getFirst()));
                    for (int i = 0; i <= n; i++) pts.add(bc.getPosition(i / (double) n));
                    pts.add(Vec3.atBottomCenterOf(bc.bePositions.getSecond()));
                }
            }
            if (pts.size() >= 2) out.add(new Line(pts, pts.get(0), pts.get(pts.size() - 1)));
        }
        lines = out;
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (builtFor != BlueprintsPayload.version) { builtFor = BlueprintsPayload.version; rebuild(); }
        if (lines.isEmpty()) return;

        Vec3 cam = e.getCamera().getPosition();
        PoseStack pose = e.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        // faint copy through terrain first, so the whole route (cuttings included) is always visible,
        // then the bright copy wherever nothing is in front of it
        draw(buffers.getBuffer(GhostRenderTypes.XRAY), m, cam, 0.35f);
        // vanilla's "always" depth state only works where depth testing is already off; it is on here
        RenderSystem.disableDepthTest();
        buffers.endBatch(GhostRenderTypes.XRAY);
        RenderSystem.enableDepthTest();
        draw(buffers.getBuffer(GhostRenderTypes.SOLID), m, cam, 1f);
        buffers.endBatch(GhostRenderTypes.SOLID);
        pose.popPose();
    }

    static void draw(VertexConsumer vc, Matrix4f m, Vec3 cam, float alphaScale) {
        double view2 = VIEW_DISTANCE * VIEW_DISTANCE;
        for (Line line : lines) {
            List<Vec3> p = line.points();
            double sinceSleeper = SLEEPER_STEP;
            for (int i = 0; i + 1 < p.size(); i++) {
                Vec3 a = p.get(i), b = p.get(i + 1);
                if (a.distanceToSqr(cam) > view2) continue;
                Vec3 d = b.subtract(a);
                double len = d.length();
                if (len < 1e-4) continue;
                Vec3 dir = d.scale(1 / len);
                Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
                for (int s = -1; s <= 1; s += 2) {
                    Vec3 o = side.scale(s * RAIL_OFFSET);
                    strip(vc, m, a.add(o).add(0, RAIL_Y, 0), b.add(o).add(0, RAIL_Y, 0), side.scale(RAIL_HALF_WIDTH), (int) (210 * alphaScale));
                }
                sinceSleeper += len;
                while (sinceSleeper >= SLEEPER_STEP) {
                    sinceSleeper -= SLEEPER_STEP;
                    Vec3 c = b.subtract(dir.scale(sinceSleeper)).add(0, SLEEPER_Y, 0);
                    strip(vc, m, c.subtract(side.scale(SLEEPER_HALF)), c.add(side.scale(SLEEPER_HALF)), dir.scale(0.13), (int) (120 * alphaScale));
                }
            }
            for (Vec3 endPoint : new Vec3[]{line.start(), line.end()})
                if (endPoint.distanceToSqr(cam) < view2) beam(vc, m, endPoint, (int) (150 * alphaScale));
        }
    }

    /** A flat quad from a to b, halfWidth to either side, drawn both ways round. */
    static void strip(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 halfWidth, int alpha) {
        Vec3 a1 = a.subtract(halfWidth), a2 = a.add(halfWidth), b1 = b.subtract(halfWidth), b2 = b.add(halfWidth);
        quad(vc, m, a1, a2, b2, b1, alpha);
        quad(vc, m, b1, b2, a2, a1, alpha);
    }

    /** A thin vertical marker two blocks tall over a line end. */
    static void beam(VertexConsumer vc, Matrix4f m, Vec3 at, int alpha) {
        double h = 0.12;
        Vec3 top = new Vec3(0, 2.5, 0);
        for (Vec3 side : new Vec3[]{new Vec3(h, 0, 0), new Vec3(0, 0, h)}) {
            Vec3 a1 = at.subtract(side), a2 = at.add(side);
            quad(vc, m, a1, a2, a2.add(top), a1.add(top), alpha);
            quad(vc, m, a1.add(top), a2.add(top), a2, a1, alpha);
        }
    }

    static void quad(VertexConsumer vc, Matrix4f m, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, int alpha) {
        for (Vec3 p : new Vec3[]{p1, p2, p3, p4})
            vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(R, G, B, alpha);
    }

    private GhostTrackRenderer() {}
}
