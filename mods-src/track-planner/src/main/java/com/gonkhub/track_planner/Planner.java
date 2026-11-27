package com.gonkhub.track_planner;

import com.gonkhub.track_planner.Moves.Move;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Plans a line in three stages, the way a real railway is laid out:
 *  0. corridor: Dijkstra over 16x16 cells on cheap worldgen height estimates, run from the goal, giving a
 *     rough cost-to-goal for every cell and a band a few chunks wide around the best rough route;
 *  1. horizontal route: A* over (x, z, heading) using only Create-legal moves, guided by stage 0;
 *  2. height profile: dynamic programming over each anchor's height, within each move's climb limit,
 *     preferring track on the ground, pricing track that floats above it, cuttings and tunnels.
 */
public final class Planner {

    public static final class Weights {
        public double track = 1.0, turn = 3.0, rough = 1.5, water = 2.0;
        /** per block of track, per block of height it floats above the ground (nothing is built under it) */
        public double lift = 0.6;
        /** per block of track over water, on top of lift above the lowest allowed height */
        public double overWater = 1.0;
        public double cut = 0.5, tunnel = 4.0, climb = 2.0, goalHeight = 2.0;
        /** blocks cleared above the rails for trains; a cutting deeper than this is priced as a tunnel */
        public int clearance = 5;
    }

    public record Anchor(int x, int y, int z, int dir) {
        public BlockPos pos() { return new BlockPos(x, y, z); }
    }

    /** anchors.size() == moves.size() + 1; moves.get(i) runs from anchors.get(i) to anchors.get(i + 1). */
    public record Plan(List<Anchor> anchors, List<Move> moves, BlockPos goal, double cost, Stats stats) {}

    public static final class Stats {
        public long corridorMs, prefetchMs, routeMs, profileMs;
        public int corridorCells, prefetchChunks, nodesExpanded, realColumns, noiseColumns, estimatedColumns, maxPlacement;
        public String failure;
    }

    static final int CELL = 16, BAND = 4, MARGIN = 160;

    private final ServerLevel level;
    private final Terrain terrain;
    private final Terrain cheap;
    private final Weights w;

    public Planner(ServerLevel level, Terrain.Mode mode, Weights weights) {
        this.level = level;
        this.terrain = new Terrain(level, mode);
        this.cheap = mode == Terrain.Mode.ESTIMATE ? terrain : new Terrain(level, Terrain.Mode.ESTIMATE);
        this.w = weights;
    }

    public Terrain terrain() { return terrain; }

    /** @param start the first track block; @param startDir its heading, or -1 to let the planner choose */
    public Plan plan(BlockPos start, int startDir, BlockPos goal) {
        Stats st = new Stats();
        int sx = start.getX(), sz = start.getZ(), gx = goal.getX(), gz = goal.getZ();

        // ---- stage 0: corridor ----
        long t0 = System.nanoTime();
        int cx0 = Math.floorDiv(Math.min(sx, gx) - MARGIN, CELL), cz0 = Math.floorDiv(Math.min(sz, gz) - MARGIN, CELL);
        int cw = Math.floorDiv(Math.max(sx, gx) + MARGIN, CELL) - cx0 + 1, ch = Math.floorDiv(Math.max(sz, gz) + MARGIN, CELL) - cz0 + 1;
        st.corridorCells = cw * ch;
        double[] avg = new double[cw * ch], wet = new double[cw * ch];
        for (int i = 0; i < cw; i++) for (int j = 0; j < ch; j++) {
            int x = (cx0 + i) * CELL + 8, z = (cz0 + j) * CELL + 8;
            avg[i * ch + j] = cheap.surface(x, z);
            wet[i * ch + j] = cheap.wet(x, z) ? 1 : 0;
        }
        double[] togo = new double[cw * ch];
        int[] prev = new int[cw * ch];
        Arrays.fill(togo, Double.POSITIVE_INFINITY);
        int gCell = (Math.floorDiv(gx, CELL) - cx0) * ch + Math.floorDiv(gz, CELL) - cz0;
        int sCell = (Math.floorDiv(sx, CELL) - cx0) * ch + Math.floorDiv(sz, CELL) - cz0;
        togo[gCell] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        q.add(new double[]{0, gCell});
        while (!q.isEmpty()) {
            double[] e = q.poll();
            int c = (int) e[1];
            if (e[0] > togo[c]) continue;
            int ci = c / ch, cj = c % ch;
            for (int[] d : Moves.DIR) {
                int ni = ci + d[0], nj = cj + d[1];
                if (ni < 0 || nj < 0 || ni >= cw || nj >= ch) continue;
                int n = ni * ch + nj;
                double len = CELL * Math.hypot(d[0], d[1]);
                double nd = togo[c] + len * w.track + Math.abs(avg[n] - avg[c]) * w.rough * 1.5 + wet[n] * len * w.water;
                if (nd < togo[n]) { togo[n] = nd; prev[n] = c; q.add(new double[]{nd, n}); }
            }
        }
        boolean[] band = new boolean[cw * ch];
        for (int c = sCell; ; c = prev[c]) {
            int ci = c / ch, cj = c % ch;
            for (int i = -BAND; i <= BAND; i++) for (int j = -BAND; j <= BAND; j++) {
                int a = ci + i, b = cj + j;
                if (a >= 0 && b >= 0 && a < cw && b < ch) band[a * ch + b] = true;
            }
            if (c == gCell) break;
        }
        st.corridorMs = (System.nanoTime() - t0) / 1_000_000;

        // cells are chunks: bring the corridor's chunks to the NOISE stage for exact terrain heights
        it.unimi.dsi.fastutil.longs.LongArrayList bandChunks = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int i = 0; i < cw; i++) for (int j = 0; j < ch; j++)
            if (band[i * ch + j]) bandChunks.add(net.minecraft.world.level.ChunkPos.asLong(cx0 + i, cz0 + j));
        st.prefetchChunks = bandChunks.size();
        st.prefetchMs = terrain.prefetch(bandChunks);
        int maxLen = com.simibubi.create.infrastructure.config.AllConfigs.server().trains.maxTrackPlacementLength.get();
        st.maxPlacement = maxLen;

        // ---- stage 1: horizontal route ----
        long t1 = System.nanoTime();
        final int fcx0 = cx0, fcz0 = cz0, fch = ch, fcw = cw;
        java.util.function.ToDoubleBiFunction<Integer, Integer> h = (x, z) -> {
            double dx = Math.abs(x - gx), dz = Math.abs(z - gz);
            double octile = (Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz)) * w.track;
            int ci = Math.floorDiv(x, CELL) - fcx0, cj = Math.floorDiv(z, CELL) - fcz0;
            double coarse = ci >= 0 && cj >= 0 && ci < fcw && cj < fch ? togo[ci * fch + cj] * 0.9 : 0;
            return Math.max(octile, coarse);
        };
        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        best.defaultReturnValue(Double.POSITIVE_INFINITY);
        Long2ObjectOpenHashMap<long[]> came = new Long2ObjectOpenHashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int d = 0; d < 8; d++) {
            if (startDir >= 0 && d != startDir) continue;
            best.put(key(sx, sz, d, sx, sz), 0);
            open.add(new double[]{h.applyAsDouble(sx, sz), 0, sx, sz, d});
        }
        double[] goalNode = null;
        while (!open.isEmpty()) {
            double[] e = open.poll();
            int x = (int) e[2], z = (int) e[3], d = (int) e[4];
            long k = key(x, z, d, sx, sz);
            if (e[1] > best.get(k)) continue;
            st.nodesExpanded++;
            if (Math.abs(x - gx) <= 4 && Math.abs(z - gz) <= 4) { goalNode = e; break; }
            if (st.nodesExpanded > 2_000_000) break;
            List<Move> moves = Moves.MOVES.get(d);
            for (int mi = 0; mi < moves.size(); mi++) {
                Move m = moves.get(mi);
                if (m.dx() * m.dx() + m.dz() * m.dz() > (maxLen - 1) * (maxLen - 1)) continue; // Create's placement length limit
                int nx = x + m.dx(), nz = z + m.dz();
                int ci = Math.floorDiv(nx, CELL) - cx0, cj = Math.floorDiv(nz, CELL) - cz0;
                if (ci < 0 || cj < 0 || ci >= cw || cj >= ch || !band[ci * ch + cj]) continue;
                double ng = e[1] + routeCost(x, z, m);
                long nk = key(nx, nz, m.endDir(), sx, sz);
                if (ng < best.get(nk)) {
                    best.put(nk, ng);
                    came.put(nk, new long[]{k, x, z, d, mi});
                    open.add(new double[]{ng + h.applyAsDouble(nx, nz) * 1.2, ng, nx, nz, m.endDir()});
                }
            }
        }
        st.routeMs = (System.nanoTime() - t1) / 1_000_000;
        if (goalNode == null) {
            st.failure = "no route found (" + st.nodesExpanded + " nodes)";
            return new Plan(List.of(), List.of(), goal, Double.POSITIVE_INFINITY, st);
        }
        List<int[]> xz = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        long k = key((int) goalNode[2], (int) goalNode[3], (int) goalNode[4], sx, sz);
        xz.add(new int[]{(int) goalNode[2], (int) goalNode[3], (int) goalNode[4]});
        while (came.containsKey(k)) {
            long[] c = came.get(k);
            xz.add(new int[]{(int) c[1], (int) c[2], (int) c[3]});
            moves.add(Moves.MOVES.get((int) c[3]).get((int) c[4]));
            k = c[0];
        }
        Collections.reverse(xz);
        Collections.reverse(moves);

        // ---- stage 2: height profile ----
        long t2 = System.nanoTime();
        int n = xz.size();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] a : xz) { int s = terrain.surface(a[0], a[1]); lo = Math.min(lo, s); hi = Math.max(hi, s); }
        lo = Math.max(level.getMinBuildHeight() + 2, Math.min(lo, start.getY()) - 32);
        hi = Math.min(level.getMaxBuildHeight() - 8, Math.max(hi, start.getY()) + 32);
        int H = hi - lo + 1;
        double[][] dp = new double[n][H];
        int[][] from = new int[n][H];
        for (double[] r : dp) Arrays.fill(r, Double.POSITIVE_INFINITY);
        dp[0][start.getY() - lo] = 0;
        for (int i = 0; i < n - 1; i++) {
            Move m = moves.get(i);
            int[] a = xz.get(i);
            for (int y = 0; y < H; y++) {
                if (dp[i][y] == Double.POSITIVE_INFINITY) continue;
                for (int dy = -m.maxDy(); dy <= m.maxDy(); dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= H) continue;
                    if (m.dx() * m.dx() + m.dz() * m.dz() + dy * dy > (maxLen - 1) * (maxLen - 1)) continue;
                    double c = dp[i][y] + profileCost(a[0], a[1], y + lo, dy, m);
                    if (c < dp[i + 1][ny]) { dp[i + 1][ny] = c; from[i + 1][ny] = y; }
                }
            }
        }
        int by = -1;
        double bc = Double.POSITIVE_INFINITY;
        for (int y = 0; y < H; y++) {
            double c = dp[n - 1][y] + Math.abs(y + lo - goal.getY()) * w.goalHeight;
            if (c < bc) { bc = c; by = y; }
        }
        st.profileMs = (System.nanoTime() - t2) / 1_000_000;
        st.realColumns = terrain.realColumns;
        st.noiseColumns = terrain.noiseColumns;
        st.estimatedColumns = terrain.estimatedColumns + (cheap != terrain ? cheap.estimatedColumns : 0);
        if (by < 0) {
            st.failure = "no height profile fits this route";
            return new Plan(List.of(), List.of(), goal, Double.POSITIVE_INFINITY, st);
        }
        int[] ys = new int[n];
        ys[n - 1] = by;
        for (int i = n - 1; i > 0; i--) ys[i - 1] = from[i][ys[i]];
        List<Anchor> anchors = new ArrayList<>();
        for (int i = 0; i < n; i++) anchors.add(new Anchor(xz.get(i)[0], ys[i] + lo, xz.get(i)[1], xz.get(i)[2]));
        return new Plan(anchors, moves, goal, bc, st);
    }

    private static long key(int x, int z, int d, int ox, int oz) {
        return ((long) (x - ox + (1 << 23)) << 27) | ((long) (z - oz + (1 << 23)) << 3) | d;
    }

    /** Stage 1 cost: length, curves, every block of ground the line would climb or drop, water crossed. */
    private double routeCost(int x, int z, Move m) {
        double c = m.length() * w.track + (m.kind() != Moves.Kind.STRAIGHT ? w.turn : 0);
        int prev = terrain.surface(x, z);
        double step = m.length() / m.samples().length;
        for (int[] s : m.samples()) {
            int sx = x + s[0], sz = z + s[1];
            int g = terrain.surface(sx, sz);
            c += Math.abs(g - prev) * w.rough;
            if (terrain.wet(sx, sz)) c += step * w.water;
            prev = g;
        }
        return c;
    }

    /** Stage 2 cost of a move from height y0 climbing dy: how far the track floats, how much ground is dug away. */
    double profileCost(int x, int z, int y0, int dy, Move m) {
        double c = Math.abs(dy) * w.climb;
        int n = m.samples().length;
        double step = m.length() / n;
        for (int i = 0; i < n; i++) {
            int sx = x + m.samples()[i][0], sz = z + m.samples()[i][1];
            double ty = y0 + dy * (i + 1) / (double) n;
            int g = terrain.ground(sx, sz);
            int water = terrain.water(sx, sz);
            // the rails rest on block ty - 1 when the track lies on the ground
            double above = ty - 1 - g;
            if (water != Integer.MIN_VALUE) {
                // over water the track must clear the surface; it then floats, nothing is built under it
                if (ty < water + 1) return Double.POSITIVE_INFINITY;
                c += step * (w.overWater + (ty - water - 1) * w.lift);
            } else if (above > 0) {
                c += step * above * w.lift;
            } else if (above < 0) {
                double depth = -above;
                c += step * (depth <= w.clearance ? depth * w.cut : w.tunnel + w.clearance * w.cut);
            }
        }
        return c;
    }
}
