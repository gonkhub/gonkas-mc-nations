package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.Builder;
import com.gonkhub.track_planner.LineEnd;
import com.gonkhub.track_planner.Moves;
import com.gonkhub.track_planner.Planner;
import com.gonkhub.track_planner.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.io.PrintWriter;

/**
 * Joining existing track: build a line A, then plan and build B from open ground to the MIDDLE of one of
 * A's straights (it must branch in with a curve), and C carrying on from A's END. Create's graph must then
 * hold A, B and C as one network.
 */
final class JoinTest {
    static void run(ServerLevel level, BlockPos spawn, String name, int sdx, int sdz, int gdx, int gdz, PrintWriter log) {
        log.println("\n=== join case " + name);
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        Planner.Weights w = new Planner.Weights();
        BlockPos a0 = ground(real, spawn.getX() + sdx, spawn.getZ() + sdz), a1 = ground(real, spawn.getX() + gdx, spawn.getZ() + gdz);
        Planner.Plan pa = new Planner(level, Terrain.Mode.PREFETCH, w).plan(a0, -1, a1);
        if (pa.stats().failure != null) { log.println("FAILED to plan A: " + pa.stats().failure); return; }
        Builder.Result ra = Builder.build(level, pa, w);
        log.printf("A: %d pieces, %d placed, %d refused%n", pa.moves().size(), ra.placements, ra.refused);

        // the middle of A's longest level straight
        BlockPos mid = null;
        int longest = 0;
        for (int i = 0; i < pa.moves().size(); i++) {
            var m = pa.moves().get(i);
            var an = pa.anchors().get(i);
            int k = Math.max(Math.abs(m.dx()), Math.abs(m.dz()));
            if (m.kind() == Moves.Kind.STRAIGHT && an.y() == pa.anchors().get(i + 1).y() && k > longest) {
                longest = k;
                int[] u = Moves.DIR[m.dir()];
                mid = new BlockPos(an.x() + u[0] * (k / 2), an.y(), an.z() + u[1] * (k / 2));
            }
        }
        if (mid == null || longest < 8) { log.println("A has no straight long enough to branch from"); return; }
        LineEnd midEnd = LineEnd.at(level, mid);
        log.println("B's goal, middle of A: " + midEnd.describe() + ", dirs " + Integer.toBinaryString(midEnd.dirs()) + ", turnOnly " + midEnd.turnOnly());

        // B: open ground 150 blocks off to the side -> the middle of A
        BlockPos b0 = ground(real, mid.getX() - 110, mid.getZ() + 110);
        Planner.Plan pb = new Planner(level, Terrain.Mode.PREFETCH, w).plan(LineEnd.free(b0), midEnd);
        report(log, "B", pb, mid);
        if (pb.stats().failure == null) {
            Builder.Result rb = Builder.build(level, pb, w);
            log.printf("B built: %d placed, %d refused %s%n", rb.placements, rb.refused, rb.refusals);
        }

        // C: on from A's end -> open ground
        var last = pa.anchors().get(pa.anchors().size() - 1);
        LineEnd endA = LineEnd.at(level, last.pos());
        log.println("C's start, end of A: " + endA.describe() + ", dirs " + Integer.toBinaryString(endA.dirs()) + ", turnOnly " + endA.turnOnly());
        BlockPos c1 = ground(real, last.x() + 160, last.z() + 60);
        Planner.Plan pc = new Planner(level, Terrain.Mode.PREFETCH, w).plan(endA, LineEnd.free(c1));
        report(log, "C", pc, last.pos());
        if (pc.stats().failure == null) {
            Builder.Result rc = Builder.build(level, pc, w);
            log.printf("C built: %d placed, %d refused %s%n", rc.placements, rc.refused, rc.refusals);
        }

        for (var p : new Planner.Plan[]{pa, pb, pc})
            for (var an : p.anchors()) level.setChunkForced(an.x() >> 4, an.z() >> 4, true);
        if (pb.stats().failure == null && pc.stats().failure == null) {
            Vec3 vb = Vec3.atBottomCenterOf(pb.anchors().get(0).pos()), vc = Vec3.atBottomCenterOf(pc.anchors().get(pc.anchors().size() - 1).pos());
            Vec3 va = Vec3.atBottomCenterOf(a0);
            Harness.later.add(() -> Harness.graphCheck(name + " B start -> C end (through A)", vb, vc, 0));
            Harness.later.add(() -> Harness.graphCheck(name + " A start -> C end", va, vc, 0));
        }
    }

    static void report(PrintWriter log, String line, Planner.Plan p, BlockPos joins) {
        if (p.stats().failure != null) { log.println(line + ": FAILED to plan: " + p.stats().failure); return; }
        var first = p.anchors().get(0);
        var end = p.anchors().get(p.anchors().size() - 1);
        BlockPos at = line.equals("C") ? first.pos() : end.pos();
        var mv = line.equals("C") ? p.moves().get(0) : p.moves().get(p.moves().size() - 1);
        log.printf("%s: %d pieces; %s exactly at the existing track: %s; joining piece is a %s%n", line, p.moves().size(),
                line.equals("C") ? "starts" : "ends", at.equals(joins), mv.kind());
    }

    static BlockPos ground(Terrain t, int x, int z) { return new BlockPos(x, t.surface(x, z) + 1, z); }
}
