package com.gonkhub.track_planner;

import java.util.ArrayList;
import java.util.List;

/**
 * The track moves the planner may chain together. Every move starts and ends on a flat track
 * block ("anchor"), so each one maps onto a single Create track placement (TrackPlacement.tryConnect).
 *
 * Limits follow Create 6.0.10's TrackPlacement.tryConnect:
 *  - 8 headings; a turn is 45 or 90 degrees
 *  - 90 degree turn: turn size >= 7, plus 2 per block of climb above 3
 *  - 45 degree turn: turn size >= 3.25, plus 1.5 per block of climb above 1.5
 *  - straight climb: horizontal run >= max(rise < 4 ? 4 * rise : 3 * rise, 6)
 * maxDy is kept one step inside each limit so rounding in Create never rejects a planned move;
 * the builder still asks Create, and reports any move Create refuses.
 */
public final class Moves {
    /** Heading i as a block step; +x is east, +z is south. Even = straight along an axis, odd = diagonal. */
    public static final int[][] DIR = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    public enum Kind { STRAIGHT, TURN45, TURN90 }

    /**
     * @param dx,dz    end anchor offset from the start anchor
     * @param endDir   heading at the end anchor
     * @param length   approximate track length in blocks
     * @param maxDy    largest climb or drop this move can take
     * @param samples  block offsets along the move, used to read terrain and to clear or support it
     */
    public record Move(Kind kind, int dir, int dx, int dz, int endDir, double length, int maxDy, int[][] samples) {}

    /** MOVES.get(heading) = every move that can start on that heading. */
    public static final List<List<Move>> MOVES = new ArrayList<>();

    static final int[] STRAIGHT_STEPS = {4, 8, 16, 32};

    static {
        for (int d = 0; d < 8; d++) {
            List<Move> list = new ArrayList<>();
            int[] u = DIR[d];
            double ul = Math.hypot(u[0], u[1]);
            for (int k : STRAIGHT_STEPS) {
                double run = k * ul;
                int maxDy = 0;
                for (int dy = 1; dy < 64; dy++)
                    if (run - 1 >= Math.max(dy < 4 ? dy * 4 : dy * 3, 6)) maxDy = dy;
                list.add(new Move(Kind.STRAIGHT, d, u[0] * k, u[1] * k, d, run, maxDy, line(u[0] * k, u[1] * k)));
            }
            for (int side : new int[]{1, -1}) {
                // 45 degrees: 7 blocks along an axis meet 5 diagonal steps (7.07), so the curve is near symmetric
                int nd = Math.floorMod(d + side, 8);
                int[] v = DIR[nd];
                double vl = Math.hypot(v[0], v[1]);
                int m1 = d % 2 == 0 ? 7 : 5, m2 = d % 2 == 0 ? 5 : 7;
                double turn = Math.min(m1 * ul, m2 * vl) - 0.5;
                int maxDy = (int) Math.floor(1.5 + (turn - 3.25) / 1.5) - 1;
                int px = u[0] * m1, pz = u[1] * m1, ex = px + v[0] * m2, ez = pz + v[1] * m2;
                list.add(new Move(Kind.TURN45, d, ex, ez, nd, 2 * turn * 0.96, maxDy, curve(px, pz, ex, ez)));
                // 90 degrees: 9 blocks either side of the corner (or 7 diagonal steps, 9.9)
                nd = Math.floorMod(d + 2 * side, 8);
                v = DIR[nd];
                int m = d % 2 == 0 ? 9 : 7;
                turn = m * ul - 0.5;
                maxDy = (int) Math.floor(3 + (turn - 7) / 2) - 1;
                px = u[0] * m; pz = u[1] * m; ex = px + v[0] * m; ez = pz + v[1] * m;
                list.add(new Move(Kind.TURN90, d, ex, ez, nd, turn * Math.PI / 2, maxDy, curve(px, pz, ex, ez)));
            }
            MOVES.add(list);
        }
    }

    /** A straight of k steps on heading d, of any length (the planner's exact finish onto existing track). */
    public static Move straight(int d, int k) {
        int[] u = DIR[d];
        double run = k * Math.hypot(u[0], u[1]);
        int maxDy = 0;
        for (int dy = 1; dy < 64; dy++)
            if (run - 1 >= Math.max(dy < 4 ? dy * 4 : dy * 3, 6)) maxDy = dy;
        return new Move(Kind.STRAIGHT, d, u[0] * k, u[1] * k, d, run, maxDy, line(u[0] * k, u[1] * k));
    }

    static int[][] line(int x1, int z1) {
        int n = Math.max(Math.abs(x1), Math.abs(z1));
        int[][] s = new int[n][];
        for (int i = 0; i < n; i++) s[i] = new int[]{Math.round(x1 * (i + 1) / (float) n), Math.round(z1 * (i + 1) / (float) n)};
        return s;
    }

    /** Quadratic Bezier through the corner; close enough to Create's cubic curve for costing and clearing. */
    static int[][] curve(int px, int pz, int ex, int ez) {
        int n = 24;
        int[][] s = new int[n][];
        for (int i = 0; i < n; i++) {
            double t = (i + 1) / (double) n, a = 2 * (1 - t) * t, b = t * t;
            s[i] = new int[]{(int) Math.round(a * px + b * ex), (int) Math.round(a * pz + b * ez)};
        }
        return s;
    }

    private Moves() {}
}
