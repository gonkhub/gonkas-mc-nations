import java.io.*;
import java.util.*;

/**
 * Feasibility prototype: A* over Create's legal track primitives on a synthetic heightmap.
 * Geometry limits are taken from Create 6.0.10 TrackPlacement.tryConnect:
 *  - 8 headings (ortho + diagonal), every segment is a Bezier between two flat track anchors
 *  - 90deg turn: turnSize >= 7 (+2 per block of climb above 3)
 *  - 45deg turn: turnSize >= 3.25 (+1.5 per block of climb above 1.5)
 *  - straight climb: horizontal run >= max(|dy|<4 ? 4|dy| : 3|dy|, 6)
 *  - a single segment <= maxTrackPlacementLength (128)
 * Usage: java TrackPlannerProto.java <size> <seed> <sx> <sz> <gx> <gz> <outPrefix>
 */
public class TrackPlannerProto {
    static int N; static int[] ground; static boolean[] water; static final int SEA = 62;
    static final int[][] DIR = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};

    // ---- cost weights (would be server config in a real mod) ----
    static double W_TRACK = 1.0, W_FILL = 0.35, W_BRIDGE = 3.0, W_CUT = 0.5, W_TUNNEL = 4.0,
            W_WATER_BRIDGE = 2.0, W_CLIMB = 2.0, W_TURN = 3.0;

    record Prim(int dx, int dz, int ndir, double len, int maxDy, int[][] samples, boolean turn) {}
    static List<List<Prim>> prims = new ArrayList<>();

    // ---------- terrain ----------
    static double noise(double x, double z, long seed) {
        Random r = new Random(seed); double v = 0, amp = 1, f = 1 / 512.0;
        for (int o = 0; o < 6; o++) {
            double a = r.nextDouble() * 6.28, b = r.nextDouble() * 6.28, c = r.nextDouble() * 6.28;
            v += amp * (Math.sin(x * f * 1.3 + a) * Math.cos(z * f + b) + 0.5 * Math.sin((x + z) * f * 0.7 + c));
            amp *= 0.5; f *= 2.03;
        }
        return v;
    }
    static void genTerrain(long seed) {
        ground = new int[N * N]; water = new boolean[N * N];
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) {
            double n = noise(x, z, seed), m = noise(x * 0.5 + 9000, z * 0.5, seed + 1);
            double h = 66 + 14 * n + Math.max(0, m) * 70;           // plains + hills + mountains
            double river = Math.abs(Math.sin((x * 0.004) + Math.sin(z * 0.006) * 2) );
            if (river < 0.06) h = Math.min(h, SEA - 3);               // a winding river
            int hi = (int) Math.round(h);
            ground[x * N + z] = Math.max(hi, 40);
            water[x * N + z] = hi < SEA;
        }
    }
    static int g(int x, int z) { return ground[Math.max(0, Math.min(N-1,x)) * N + Math.max(0, Math.min(N-1,z))]; }
    static boolean w(int x, int z) { return water[Math.max(0, Math.min(N-1,x)) * N + Math.max(0, Math.min(N-1,z))]; }

    // ---------- primitives ----------
    static int[][] line(double x0, double z0, double x1, double z1) {
        int n = (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0));
        int[][] s = new int[n][2];
        for (int i = 0; i < n; i++) { double t = (i + 1) / (double) n; s[i] = new int[]{(int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(z0 + (z1 - z0) * t)}; }
        return s;
    }
    static int[][] bez(double[] p0, double[] p1, double[] p2) { // quadratic approx of Create's cubic, fine for costing
        int n = 24; int[][] s = new int[n][2];
        for (int i = 0; i < n; i++) { double t = (i + 1) / (double) n, a = (1-t)*(1-t), b = 2*(1-t)*t, c = t*t;
            s[i] = new int[]{(int)Math.round(a*p0[0]+b*p1[0]+c*p2[0]), (int)Math.round(a*p0[1]+b*p1[1]+c*p2[1])}; }
        return s;
    }
    static void buildPrims() {
        for (int d = 0; d < 8; d++) {
            List<Prim> list = new ArrayList<>();
            int[] u = DIR[d]; double ul = Math.hypot(u[0], u[1]);
            for (int k : new int[]{4, 8, 16, 32}) {                     // straights (k grid steps)
                double hl = k * ul;
                int maxDy = 0;
                for (int dy = 1; dy < 40; dy++) if (hl >= Math.max(dy < 4 ? dy * 4 : dy * 3, 6)) maxDy = dy;
                list.add(new Prim(u[0]*k, u[1]*k, d, hl, maxDy, line(0,0,u[0]*k,u[1]*k), false));
            }
            for (int side : new int[]{1, -1}) {
                // 45 degree turn
                int nd = (d + side + 8) % 8; int[] v = DIR[nd]; double vl = Math.hypot(v[0], v[1]);
                int m1 = d % 2 == 0 ? 7 : 5, m2 = d % 2 == 0 ? 5 : 7;  // 7 vs 5*sqrt2 = 7.07
                double ts = Math.min(m1 * ul, m2 * vl);
                int maxDy = (int) Math.floor(1.5 + (ts - 3.25) / 1.5);
                double[] P = {u[0]*m1, u[1]*m1}; int ex = (int)(P[0] + v[0]*m2), ez = (int)(P[1] + v[1]*m2);
                list.add(new Prim(ex, ez, nd, ts * 2 * 0.95, maxDy, bez(new double[]{0,0}, P, new double[]{ex,ez}), true));
                // 90 degree turn
                nd = (d + 2 * side + 8) % 8; v = DIR[nd];
                int m = d % 2 == 0 ? 9 : 7;                              // 9 or 7*sqrt2 = 9.9
                ts = m * ul;
                maxDy = (int) Math.floor(3 + (ts - 7) / 2);
                P = new double[]{u[0]*m, u[1]*m}; ex = (int)(P[0] + v[0]*m); ez = (int)(P[1] + v[1]*m);
                list.add(new Prim(ex, ez, nd, ts * Math.PI / 2, maxDy, bez(new double[]{0,0}, P, new double[]{ex,ez}), true));
            }
            prims.add(list);
        }
    }

    // ---------- earthwork cost of a segment ----------
    static double segCost(int x, int z, int y0, int dy, Prim p) {
        double c = p.len() * W_TRACK + Math.abs(dy) * W_CLIMB + (p.turn() ? W_TURN : 0);
        int n = p.samples().length;
        for (int i = 0; i < n; i++) {
            int sx = x + p.samples()[i][0], sz = z + p.samples()[i][1];
            if (sx < 0 || sz < 0 || sx >= N || sz >= N) return Double.POSITIVE_INFINITY;
            double ty = y0 + dy * (i + 1) / (double) n; int gy = g(sx, sz);
            double step = p.len() / n;
            if (w(sx, sz)) c += step * (W_WATER_BRIDGE + Math.max(0, ty - SEA) * 0.1);
            else if (ty > gy) { double f = ty - gy; c += step * (f <= 6 ? f * W_FILL : W_BRIDGE + 6 * W_FILL); }
            else if (ty < gy - 1) { double cut = gy - 1 - ty; c += step * (cut <= 6 ? cut * W_CUT : W_TUNNEL + 3 * W_CUT); }
        }
        return c;
    }

    // ---------- stage 1: horizontal alignment, A* over (x, z, heading) ----------
    static double W_ROUGH = 1.5;
    static double planCost(int x, int z, Prim p) {
        double c = p.len() * W_TRACK + (p.turn() ? W_TURN : 0);
        int prev = Math.max(g(x, z), SEA); int n = p.samples().length; double step = p.len() / n;
        for (int[] s : p.samples()) {
            int sx = x + s[0], sz = z + s[1];
            if (sx < 0 || sz < 0 || sx >= N || sz >= N) return Double.POSITIVE_INFINITY;
            int gy = Math.max(g(sx, sz), SEA);
            c += Math.abs(gy - prev) * W_ROUGH;          // every block of ground the line climbs or drops
            if (w(sx, sz)) c += step * W_WATER_BRIDGE;
            prev = gy;
        }
        return c;
    }

    // ---------- stage 0: coarse corridor, Dijkstra on 16x16 cells (~chunk resolution) ----------
    static final int C = 16, BAND = 4; static boolean[] corridor; static int CN; static double[] togo;
    static void buildCorridor(int sx, int sz, int gx, int gz) {
        CN = (N + C - 1) / C; double[] avg = new double[CN * CN]; double[] wet = new double[CN * CN];
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) { int c = (x / C) * CN + z / C;
            avg[c] += Math.max(g(x, z), SEA) / (double)(C * C); if (w(x, z)) wet[c] += 1.0 / (C * C); }
        double[] dist = new double[CN * CN]; int[] prev = new int[CN * CN]; Arrays.fill(dist, Double.POSITIVE_INFINITY);
        int t = (sx / C) * CN + sz / C, s = (gx / C) * CN + gz / C; dist[s] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>(Comparator.comparingDouble(e -> e[0])); q.add(new double[]{0, s});
        while (!q.isEmpty()) { double[] e = q.poll(); int c = (int) e[1]; if (e[0] > dist[c]) continue;
            int cx = c / CN, cz = c % CN;
            for (int[] d : DIR) { int nx = cx + d[0], nz = cz + d[1]; if (nx < 0 || nz < 0 || nx >= CN || nz >= CN) continue;
                int n = nx * CN + nz; double l = C * Math.hypot(d[0], d[1]);
                double nd = dist[c] + l * W_TRACK + Math.abs(avg[n] - avg[c]) * W_ROUGH * 1.5 + wet[n] * l * W_WATER_BRIDGE;
                if (nd < dist[n]) { dist[n] = nd; prev[n] = c; q.add(new double[]{nd, n}); } } }
        boolean[] cell = new boolean[CN * CN];
        for (int c = t; ; c = prev[c]) { int cx = c / CN, cz = c % CN;
            for (int i = -BAND; i <= BAND; i++) for (int j = -BAND; j <= BAND; j++) {
                int x = cx + i, z = cz + j; if (x >= 0 && z >= 0 && x < CN && z < CN) cell[x * CN + z] = true; }
            if (c == s) break; }
        corridor = cell; togo = dist;
    }
    static boolean inCorridor(int x, int z) { return corridor[(x / C) * CN + z / C]; }
    static long key(int x, int z, int d) { return ((long) x * N + z) * 8 + d; }

    public static void main(String[] a) throws Exception {
        N = Integer.parseInt(a[0]); long seed = Long.parseLong(a[1]);
        int sx = Integer.parseInt(a[2]), sz = Integer.parseInt(a[3]), gx = Integer.parseInt(a[4]), gz = Integer.parseInt(a[5]);
        String out = a[6];
        genTerrain(seed); buildPrims();
        int sy = Math.max(g(sx, sz), SEA) + 1, gy = Math.max(g(gx, gz), SEA) + 1;

        long t0 = System.nanoTime();
        buildCorridor(sx, sz, gx, gz);
        double msC = (System.nanoTime() - t0) / 1e6;
        HashMap<Long, Double> best = new HashMap<>(); HashMap<Long, long[]> came = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(e -> e[0]));
        for (int d = 0; d < 8; d++) { best.put(key(sx, sz, d), 0.0); open.add(new double[]{h(sx,sz,gx,gz), 0, sx, sz, d}); }
        long expanded = 0; double[] goal = null;
        while (!open.isEmpty()) {
            double[] e = open.poll(); int x = (int) e[2], z = (int) e[3], d = (int) e[4];
            long k = key(x, z, d); if (e[1] > best.get(k)) continue;
            expanded++;
            if (Math.abs(x - gx) <= 4 && Math.abs(z - gz) <= 4) { goal = e; break; }
            for (int pi = 0; pi < prims.get(d).size(); pi++) { Prim p = prims.get(d).get(pi);
                int nx = x + p.dx(), nz = z + p.dz();
                if (nx < 0 || nz < 0 || nx >= N || nz >= N || !inCorridor(nx, nz)) continue;
                double c = planCost(x, z, p); if (c == Double.POSITIVE_INFINITY) continue;
                double ng = e[1] + c; long nk = key(nx, nz, p.ndir());
                Double old = best.get(nk);
                if (old == null || ng < old) { best.put(nk, ng); came.put(nk, new long[]{k, x, z, d, pi});
                    open.add(new double[]{ng + h(nx, nz, gx, gz) * 1.2, ng, nx, nz, p.ndir()}); }
            }
        }
        double ms1 = (System.nanoTime() - t0) / 1e6;
        if (goal == null) { System.out.println("NO PATH expanded=" + expanded); return; }
        List<int[]> anchors = new ArrayList<>(); List<Prim> segs = new ArrayList<>();
        long k = key((int)goal[2],(int)goal[3],(int)goal[4]);
        anchors.add(new int[]{(int)goal[2],(int)goal[3]});
        while (came.containsKey(k)) { long[] c = came.get(k); anchors.add(new int[]{(int)c[1],(int)c[2]});
            segs.add(prims.get((int)c[3]).get((int)c[4])); k = c[0]; }
        Collections.reverse(anchors); Collections.reverse(segs);

        // ---------- stage 2: vertical profile, DP over anchor heights ----------
        long t1 = System.nanoTime();
        int Y0 = 20, Y1 = 320, H = Y1 - Y0, n = anchors.size();
        double[][] dp = new double[n][H]; int[][] from = new int[n][H];
        for (double[] r : dp) Arrays.fill(r, Double.POSITIVE_INFINITY);
        dp[0][sy - Y0] = 0;
        for (int i = 0; i < n - 1; i++) { Prim p = segs.get(i); int[] A = anchors.get(i);
            for (int y = 0; y < H; y++) { if (dp[i][y] == Double.POSITIVE_INFINITY) continue;
                for (int dy = -p.maxDy(); dy <= p.maxDy(); dy++) { int ny = y + dy; if (ny < 0 || ny >= H) continue;
                    double c = dp[i][y] + segCost(A[0], A[1], y + Y0, dy, p);
                    if (c < dp[i + 1][ny]) { dp[i + 1][ny] = c; from[i + 1][ny] = y; } } } }
        int by = gy - Y0; // must arrive at the goal track's height (search +-3 for slack)
        for (int y = Math.max(0, gy - Y0 - 3); y <= Math.min(H - 1, gy - Y0 + 3); y++) if (dp[n-1][y] < dp[n-1][by]) by = y;
        int[] ys = new int[n]; ys[n-1] = by; for (int i = n - 1; i > 0; i--) ys[i-1] = from[i][ys[i]];
        double ms2 = (System.nanoTime() - t1) / 1e6;

        try (PrintWriter pw = new PrintWriter(out + "_terrain.csv")) {
            for (int x = 0; x < N; x++) { StringBuilder sb = new StringBuilder();
                for (int z = 0; z < N; z++) sb.append(z > 0 ? "," : "").append(water[x*N+z] ? -1 : ground[x*N+z]); pw.println(sb); } }
        int turns = 0, tunnelBlocks = 0, bridgeBlocks = 0; double len = 0; int maxGradeNum = 0;
        try (PrintWriter pw = new PrintWriter(out + "_path.csv")) {
            for (int i = 0; i < n; i++) { int[] A = anchors.get(i); pw.println(A[0] + "," + A[1] + "," + (ys[i] + Y0) + "," + g(A[0], A[1]));
                if (i < n - 1) { Prim p = segs.get(i); len += p.len(); if (p.turn()) turns++;
                    int m = p.samples().length; for (int j = 0; j < m; j++) { int[] s = p.samples()[j];
                        double ty = ys[i] + Y0 + (ys[i+1] - ys[i]) * (j + 1) / (double) m; int gg = g(A[0]+s[0], A[1]+s[1]);
                        if (ty < gg - 7) tunnelBlocks++; if (ty > gg + 7 || w(A[0]+s[0], A[1]+s[1])) bridgeBlocks++; } } } }
        System.out.printf("route: %d segments (%d curves), ~%.0f blocks of track vs %.0f straight-line; tunnel samples=%d bridge samples=%d; height %d->%d%n",
            n - 1, turns, len, Math.hypot(gx - sx, gz - sz), tunnelBlocks, bridgeBlocks, sy, ys[n-1] + Y0);
        System.out.printf("stage0 corridor: %.0f ms; stage1 A*: %d nodes expanded, %.0f ms total; stage2 profile DP: %.0f ms%n", msC, expanded, ms1, ms2);
    }
    static double h(int x, int z, int gx, int gz) { if (togo != null) return Math.max(oct(x,z,gx,gz), togo[(x / C) * CN + z / C] * 0.9); return oct(x,z,gx,gz); }
    static double oct(int x, int z, int gx, int gz) { double dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return (Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz)) * W_TRACK; }
}
