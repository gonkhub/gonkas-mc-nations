package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.Builder;
import com.gonkhub.track_planner.Planner;
import com.gonkhub.track_planner.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** Double track: a line, a second one planned beside it, both built. How far apart are they, and do both still run? */
final class ParallelTest {
    static void run(ServerLevel level, BlockPos spawn, String name, int sdx, int sdz, int gdx, int gdz, PrintWriter log) {
        log.println("\n=== parallel case " + name);
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        Planner.Weights w = new Planner.Weights();
        int sx = spawn.getX() + sdx, sz = spawn.getZ() + sdz, gx = spawn.getX() + gdx, gz = spawn.getZ() + gdz;
        BlockPos start = new BlockPos(sx, real.surface(sx, sz) + 1, sz), goal = new BlockPos(gx, real.surface(gx, gz) + 1, gz);
        Planner.Plan a = new Planner(level, Terrain.Mode.PREFETCH, w).plan(start, -1, goal);
        if (a.stats().failure != null) { log.println("FAILED to plan the first line: " + a.stats().failure); return; }
        int turns = (int) a.moves().stream().filter(m -> m.kind() != com.gonkhub.track_planner.Moves.Kind.STRAIGHT).count();
        log.printf("first line: %d pieces (%d turns), %.0f blocks%n", a.moves().size(), turns, a.moves().stream().mapToDouble(m -> m.length()).sum());
        for (int side : new int[]{1, -1}) {
            long t0 = System.nanoTime();
            Planner.Plan b = new Planner(level, Terrain.Mode.PREFETCH, w).planParallel(a, side, 4);
            String s = side > 0 ? "right" : "left";
            if (b.stats().failure != null) { log.println(s + ": FAILED: " + b.stats().failure); continue; }
            log.printf("%s line: %d pieces, planned in %.1f s; starts %s, ends %s (first line %s .. %s)%n", s, b.moves().size(), (System.nanoTime() - t0) / 1e9,
                    b.anchors().get(0).pos().toShortString(), b.anchors().get(b.anchors().size() - 1).pos().toShortString(),
                    a.anchors().get(0).pos().toShortString(), a.anchors().get(a.anchors().size() - 1).pos().toShortString());
            gaps(log, a, b);
        }
        Builder.Result ra = Builder.build(level, a, w);
        Planner.Plan b = new Planner(level, Terrain.Mode.PREFETCH, w).planParallel(a, 1, 4);
        if (b.stats().failure != null) return;
        Builder.Result rb = Builder.build(level, b, w);
        log.printf("built: first %d placed %d refused; right %d placed %d refused %s%n", ra.placements, ra.refused, rb.placements, rb.refused, rb.refusals);
        Harness.inspect(level, a, real);
        Harness.inspect(level, b, real);
        for (var p : new Planner.Plan[]{a, b}) for (var an : p.anchors()) level.setChunkForced(an.x() >> 4, an.z() >> 4, true);
        Vec3 a0 = Vec3.atBottomCenterOf(a.anchors().get(0).pos()), a1 = Vec3.atBottomCenterOf(a.anchors().get(a.anchors().size() - 1).pos());
        Vec3 b0 = Vec3.atBottomCenterOf(b.anchors().get(0).pos()), b1 = Vec3.atBottomCenterOf(b.anchors().get(b.anchors().size() - 1).pos());
        Harness.later.add(() -> Harness.graphCheck(name + " first line", a0, a1, 0));
        Harness.later.add(() -> Harness.graphCheck(name + " second line", b0, b1, 0));
    }

    /** Distance from every point of b to the nearest point of a, and height differences. */
    static void gaps(PrintWriter log, Planner.Plan a, Planner.Plan b) {
        List<double[]> pa = points(a), pb = points(b);
        double min = 1e9, max = 0, sum = 0; int far = 0, n = 0; double dyMax = 0;
        for (double[] q : pb) {
            double best = 1e9, by = 0;
            for (double[] p : pa) { double d = Math.hypot(p[0] - q[0], p[1] - q[1]); if (d < best) { best = d; by = p[2]; } }
            min = Math.min(min, best); max = Math.max(max, best); sum += best; n++;
            if (Math.abs(best - 4) > 1.5) far++;
            dyMax = Math.max(dyMax, Math.abs(by - q[2]));
        }
        log.printf("   spacing to the first line: %.1f min, %.1f average, %.1f max; %d of %d points more than 1.5 off 4; heights differ by %.1f at most%n",
                min, sum / n, max, far, n, dyMax);
    }

    static List<double[]> points(Planner.Plan p) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < p.moves().size(); i++) {
            var a = p.anchors().get(i); var c = p.anchors().get(i + 1);
            int[][] s = p.moves().get(i).samples();
            for (int j = 0; j < s.length; j++)
                out.add(new double[]{a.x() + s[j][0], a.z() + s[j][1], a.y() + (c.y() - a.y()) * (j + 1) / (double) s.length});
        }
        return out;
    }
}
