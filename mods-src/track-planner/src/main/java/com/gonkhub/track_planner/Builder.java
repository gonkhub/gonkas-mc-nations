package com.gonkhub.track_planner;

import com.gonkhub.track_planner.Moves.Move;
import com.gonkhub.track_planner.Planner.Anchor;
import com.gonkhub.track_planner.Planner.Plan;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.FakeTrackBlock;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.content.trains.track.TrackShape;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds a plan in the world, creative-style (nothing is charged yet; the report counts what it would cost).
 *
 * Nothing is ever built under the track: it lies on the ground, or runs just above it, and players can
 * add embankments or bridges later. The only earthworks are clearing: a 3-wide, clearance-tall gauge along
 * the line before the track goes in, and again along each curve as Create actually laid it, taking any sand
 * or gravel that would fall onto the rails. The one thing ever placed is a block to keep water out of a cutting. Every move is placed through Create's own
 * TrackPlacement.tryConnect, the code that runs when a player joins two tracks, so each piece passes
 * Create's checks and is laid exactly as a player would lay it.
 */
public final class Builder {

    public static final class Result {
        public int placements, refused, trackItems;
        public final List<String> refusals = new ArrayList<>();
        public final TreeMap<String, Integer> blocks = new TreeMap<>();
        public long earthworksMs, trackMs;
        public final List<Long> chunks = new ArrayList<>();
    }

    /** Builds the whole plan now, on this (the server) thread. */
    public static Result build(ServerLevel level, Plan plan, Planner.Weights w) {
        Task t = new Task(level, plan, w);
        while (!t.step(Long.MAX_VALUE)) { }
        return t.result();
    }

    /**
     * A build that can be done a little at a time: step() works until a deadline and returns, and picks up
     * where it stopped next time. In order: clear the gauge along the planned line, lay the track through
     * Create's own placement, then clear again along the curves Create actually laid. Server thread only;
     * the chunks it touches must be loaded (see chunks()).
     */
    public static final class Task {
        final ServerLevel level;
        final Plan plan;
        final Planner.Weights w;
        final Result r = new Result();
        final int maxLen;
        final long[] cols;     // core columns first, then the halo either side
        final int[] colY;
        final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet chunkSet = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        int phase, index, total;
        FakePlayer player;
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        public Task(ServerLevel level, Plan plan, Planner.Weights w) {
            this.level = level;
            this.plan = plan;
            this.w = w;
            this.maxLen = com.simibubi.create.infrastructure.config.AllConfigs.server().trains.maxTrackPlacementLength.get();
            List<Anchor> anchors = plan.anchors();
            List<Move> moves = plan.moves();
            // which columns the line occupies, and the track height in each
            Long2IntLinkedOpenHashMap core = new Long2IntLinkedOpenHashMap();   // under the rails
            Long2IntLinkedOpenHashMap halo = new Long2IntLinkedOpenHashMap();   // one block either side
            core.put(ChunkPos.asLong(anchors.get(0).x(), anchors.get(0).z()), anchors.get(0).y());
            for (int i = 0; i < moves.size(); i++) {
                Anchor a = anchors.get(i), b = anchors.get(i + 1);
                int[][] s = moves.get(i).samples();
                for (int j = 0; j < s.length; j++) {
                    int y = (int) Math.floor(a.y() + (b.y() - a.y()) * (j + 1) / (double) s.length);
                    core.put(ChunkPos.asLong(a.x() + s[j][0], a.z() + s[j][1]), y);
                }
            }
            core.forEach((k, y) -> {
                int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    long n = ChunkPos.asLong(x + dx, z + dz);
                    if (!core.containsKey(n) && !halo.containsKey(n)) halo.put(n, y.intValue());
                }
            });
            cols = new long[core.size() + halo.size()];
            colY = new int[cols.length];
            int i = 0;
            for (var m : List.of(core, halo))
                for (var e : m.long2IntEntrySet()) { cols[i] = e.getLongKey(); colY[i++] = e.getIntValue(); }
            for (long k : core.keySet()) {
                long c = ChunkPos.asLong(ChunkPos.getX(k) >> 4, ChunkPos.getZ(k) >> 4);
                if (!r.chunks.contains(c)) r.chunks.add(c);
            }
            // curves bow a little off the sampled line, so load one chunk either side as well
            for (long k : cols) {
                int cx = ChunkPos.getX(k) >> 4, cz = ChunkPos.getZ(k) >> 4;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) chunkSet.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
            total = cols.length + moves.size() + anchors.size();
        }

        /** Every chunk this build reads or writes. */
        public it.unimi.dsi.fastutil.longs.LongCollection chunks() { return chunkSet; }

        public Result result() { return r; }

        /** Units of work that took over 50 ms, for diagnostics. */
        final List<String> slow = new ArrayList<>();
        public List<String> slowUnits() { return slow; }

        void timed(long t0, String what) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (ms > 50) slow.add(ms + " ms: " + what);
        }

        /** Rough share of the work done, 0 to 1. */
        public double progress() {
            int done = phase == 0 ? index : phase == 1 ? cols.length + index : phase == 2 ? cols.length + plan.moves().size() + index : total;
            return Math.min(1, done / (double) total);
        }

        /** Works until the deadline (System.nanoTime()) or the end. True once the whole line is built. */
        public boolean step(long deadline) {
            List<Anchor> anchors = plan.anchors();
            List<Move> moves = plan.moves();
            while (phase < 3) {
                if (System.nanoTime() > deadline) return false;
                long t0 = System.nanoTime();
                if (phase == 0) {
                    // clear the loading gauge along the planned line (nothing is ever placed under the track)
                    if (index >= cols.length) { phase = 1; index = 0; continue; }
                    int x = ChunkPos.getX(cols[index]), z = ChunkPos.getZ(cols[index]), ty = colY[index];
                    for (int y = ty; y < ty + w.clearance; y++) clear(level, r, p.set(x, y, z));
                    index++;
                    r.earthworksMs += (System.nanoTime() - t0) / 1_000_000;
                    timed(t0, "clearing column " + x + "," + z);
                } else if (phase == 1) {
                    // track, through Create's own placement
                    if (player == null) {
                        player = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("track_planner".getBytes()), "[Track Planner]"));
                        player.setGameMode(GameType.CREATIVE);
                        setTrack(level, anchors.get(0));
                    }
                    if (index >= moves.size()) { phase = 2; index = 0; continue; }
                    // join runs of level straights on one heading into one placement, within Create's maxTrackPlacementLength
                    int i = index, j = i;
                    while (j + 1 < moves.size() && isLevelStraight(moves.get(j), anchors.get(j), anchors.get(j + 1))
                            && isLevelStraight(moves.get(j + 1), anchors.get(j + 1), anchors.get(j + 2))
                            && moves.get(j + 1).dir() == moves.get(i).dir()
                            && Math.hypot(anchors.get(j + 2).x() - anchors.get(i).x(), anchors.get(j + 2).z() - anchors.get(i).z()) < maxLen - 1)
                        j++;
                    Anchor a = anchors.get(i), b = anchors.get(j + 1);
                    String refusal = connect(level, player, a, b, r);
                    r.placements++;
                    if (refusal != null) {
                        r.refused++;
                        r.refusals.add(String.format("%s %s(%d,%d,%d) -> (%d,%d,%d) dy=%d: %s", moves.get(i).kind(), j > i ? "x" + (j - i + 1) + " " : "",
                                a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), b.y() - a.y(), refusal));
                    }
                    index = j + 1;
                    r.trackMs += (System.nanoTime() - t0) / 1_000_000;
                    timed(t0, "placing " + moves.get(i).kind() + (j > i ? " x" + (j - i + 1) : "") + " " + a.pos().toShortString() + " -> " + b.pos().toShortString());
                    // one track piece per call when building gradually: every new track block makes Create walk
                    // its rail network on the next tick, and hundreds at once stall that tick
                    if (deadline != Long.MAX_VALUE) return false;
                } else {
                    // clear along the curves Create actually laid: a Bezier rises and falls unlike the
                    // straight-line estimate above, and any block left at rail height hides the rails
                    if (index >= anchors.size()) { phase = 3; break; }
                    if (level.getBlockEntity(anchors.get(index).pos()) instanceof TrackBlockEntity tbe)
                        for (BezierConnection bc : tbe.getConnections().values())
                            if (bc.isPrimary()) clearAlong(level, r, bc, w.clearance);
                    timed(t0, "clearing along curves at " + anchors.get(index).pos().toShortString());
                    index++;
                    r.earthworksMs += (System.nanoTime() - t0) / 1_000_000;
                }
            }
            return true;
        }
    }

    /** Every block a train would pass through along a curve, one block either side of the rails. */
    static void clearAlong(ServerLevel level, Result r, BezierConnection placed, int clearance) {
        BezierConnection bc = settled(placed);
        int n = Math.max(8, (int) Math.ceil(bc.getLength() * 4));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            Vec3 at = bc.getPosition(t), ahead = bc.getPosition(Math.min(1, t + 0.01)).subtract(bc.getPosition(Math.max(0, t - 0.01)));
            Vec3 side = new Vec3(-ahead.z, 0, ahead.x).normalize();
            for (int off = -1; off <= 1; off++)
                for (int dy = 0; dy < clearance; dy++)
                    clear(level, r, p.set(Mth.floor(at.x + side.x * off), Mth.floor(at.y + 0.05 + dy), Mth.floor(at.z + side.z * off)));
        }
    }

    /**
     * The curve as the game will draw it. Where two slopes meet, Create tilts the joint and edits both curves
     * in place, but a curve's shape is computed once and cached, so the placed object can still report the
     * shape from before the tilt. A copy made through Create's own save format (what a restart loads) is exact.
     */
    public static BezierConnection settled(BezierConnection placed) {
        return new BezierConnection(placed.write(BlockPos.ZERO), BlockPos.ZERO);
    }

    static void clear(ServerLevel level, Result r, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        // fluid inside the gauge (a cave's water or lava the cut runs into) goes too; the seal below stops more flowing in
        if (st.isAir() || st.getBlock() instanceof ITrackBlock || st.getBlock() instanceof FakeTrackBlock) return;
        if (level.getBlockEntity(pos) != null) { count(r, "left in place (has contents)"); return; }   // chests, stations, machines
        count(r, "removed " + category(st));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        // sand and gravel resting on what was removed would fall onto the rails later: take the whole stack
        BlockPos.MutableBlockPos up = pos.mutable().move(Direction.UP);
        while (level.getBlockState(up).getBlock() instanceof FallingBlock) {
            count(r, "removed falling block above the cut");
            level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            up.move(Direction.UP);
        }

        // water or lava beside or above the new gap would flow onto the rails: wall it off with what was dug out
        BlockState seal = st.getBlock() instanceof FallingBlock || !st.getFluidState().isEmpty() || !st.isCollisionShapeFullBlock(level, pos)
                ? Blocks.STONE.defaultBlockState() : st;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos n = pos.relative(d);
            if (!level.getFluidState(n).isEmpty() && !(level.getBlockState(n).getBlock() instanceof ITrackBlock)) {
                count(r, "placed water seal");
                level.setBlock(n, seal, Block.UPDATE_CLIENTS);
            }
        }
    }

    static boolean isLevelStraight(Move m, Anchor a, Anchor b) { return m.kind() == Moves.Kind.STRAIGHT && a.y() == b.y(); }

    static TrackShape shape(int dir) {
        return switch (dir) {
            case 0, 4 -> TrackShape.XO;
            case 2, 6 -> TrackShape.ZO;
            case 1, 5 -> TrackShape.PD;
            default -> TrackShape.ND;
        };
    }

    static void setTrack(ServerLevel level, Anchor a) { setTrack(level, a, AllBlocks.TRACK.getDefaultState()); }

    /** Puts a flat track block of the given kind at the anchor unless one is there. True when one was placed. */
    static boolean setTrack(ServerLevel level, Anchor a, BlockState trackBase) {
        BlockState st = level.getBlockState(a.pos());
        if (st.getBlock() instanceof TrackBlock) return false;
        level.setBlock(a.pos(), trackBase.setValue(TrackBlock.SHAPE, shape(a.dir())), Block.UPDATE_ALL);
        return true;
    }

    static String connect(ServerLevel level, FakePlayer player, Anchor a, Anchor b, Result r) {
        return connect(level, player, a, b, r, AllBlocks.TRACK.asItem());
    }

    /**
     * One player-style connection from a to b with the given track item, through Create's TrackPlacement.tryConnect.
     * A survival player pays for the track out of their inventory, exactly as when placing it by hand.
     * Returns null when Create accepted it, else Create's reason.
     */
    static String connect(ServerLevel level, net.minecraft.server.level.ServerPlayer player, Anchor a, Anchor b, Result r, net.minecraft.world.item.Item trackItem) {
        BlockState base = trackItem instanceof net.minecraft.world.item.BlockItem bi ? bi.getBlock().defaultBlockState() : AllBlocks.TRACK.getDefaultState();
        setTrack(level, b, base);
        BlockState s1 = level.getBlockState(a.pos()), s2 = level.getBlockState(b.pos());
        if (!(s1.getBlock() instanceof ITrackBlock t1) || !(s2.getBlock() instanceof ITrackBlock)) return "anchor is not a track";
        int[] d = Moves.DIR[a.dir()];
        Vec3 axis = new Vec3(d[0], 0, d[1]);
        ItemStack stack = new ItemStack(trackItem, 64);
        stack.set(AllDataComponents.TRACK_CONNECTING_FROM, new TrackPlacement.ConnectingFrom(
                a.pos(), axis, new Vec3(0, 1, 0), t1.getCurveStart(level, a.pos(), s1, axis)));
        // look along the line, as a player making this connection would; put the real view back after
        float yRot = player.getYRot(), xRot = player.getXRot();
        Vec3 look = Vec3.atCenterOf(b.pos()).subtract(Vec3.atCenterOf(a.pos()));
        player.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
        player.setXRot(0);
        TrackPlacement.PlacementInfo info;
        try {
            info = TrackPlacement.tryConnect(level, player, b.pos(), s2, stack, false, true);
        } finally {
            player.setYRot(yRot);
            player.setXRot(xRot);
        }
        r.trackItems += info.requiredTracks;
        return Boolean.TRUE.equals(read(info, "valid")) ? null : String.valueOf(read(info, "message"));
    }

    private static Object read(Object o, String field) {
        try {
            Field f = TrackPlacement.PlacementInfo.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            return "?" + e;
        }
    }

    static void count(Result r, String key) { r.blocks.merge(key, 1, Integer::sum); }

    static String category(BlockState st) {
        if (!st.getFluidState().isEmpty()) return "fluid";
        if (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES)) return "tree";
        if (st.is(net.minecraft.tags.BlockTags.REPLACEABLE)) return "plants";
        return "terrain";
    }
}
