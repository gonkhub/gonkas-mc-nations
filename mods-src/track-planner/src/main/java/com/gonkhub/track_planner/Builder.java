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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * the line before the track goes in, and again along each curve as Create actually laid it. Every move is placed through Create's own
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

    public static Result build(ServerLevel level, Plan plan, Planner.Weights w) {
        Result r = new Result();
        int maxLen = com.simibubi.create.infrastructure.config.AllConfigs.server().trains.maxTrackPlacementLength.get();
        List<Anchor> anchors = plan.anchors();
        List<Move> moves = plan.moves();

        // ---- which columns the line occupies, and the track height in each ----
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
        core.keySet().forEach(k -> {
            long c = ChunkPos.asLong(ChunkPos.getX(k) >> 4, ChunkPos.getZ(k) >> 4);
            if (!r.chunks.contains(c)) r.chunks.add(c);
        });

        // ---- clear the loading gauge along the planned line (nothing is ever placed under the track) ----
        long t0 = System.nanoTime();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Long2IntLinkedOpenHashMap cols : List.of(core, halo)) {
            cols.forEach((k, ty) -> {
                int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
                for (int y = ty; y < ty + w.clearance; y++) clear(level, r, p.set(x, y, z));
            });
        }
        r.earthworksMs = (System.nanoTime() - t0) / 1_000_000;

        // ---- track, through Create's own placement ----
        long t1 = System.nanoTime();
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("track_planner".getBytes()), "[Track Planner]"));
        player.setGameMode(GameType.CREATIVE);
        setTrack(level, anchors.get(0));
        int i = 0;
        while (i < moves.size()) {
            // join runs of level straights on one heading into one placement, within Create's maxTrackPlacementLength
            int j = i;
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
            i = j + 1;
        }
        r.trackMs = (System.nanoTime() - t1) / 1_000_000;

        // ---- clear along the curves Create actually laid: a Bezier rises and falls unlike the straight-line
        // estimate above, and any block left at rail height hides the rails ----
        long t2 = System.nanoTime();
        for (Anchor a : anchors)
            if (level.getBlockEntity(a.pos()) instanceof TrackBlockEntity tbe)
                for (BezierConnection bc : tbe.getConnections().values())
                    if (bc.isPrimary()) clearAlong(level, r, bc, w.clearance);
        r.earthworksMs += (System.nanoTime() - t2) / 1_000_000;
        return r;
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
        // water is left alone: the planner keeps the track above it, and draining it would flood the cut
        if (st.isAir() || st.getBlock() instanceof ITrackBlock || st.getBlock() instanceof FakeTrackBlock || !st.getFluidState().isEmpty()) return;
        count(r, "removed " + category(st));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
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

    static void setTrack(ServerLevel level, Anchor a) {
        BlockState st = level.getBlockState(a.pos());
        if (st.getBlock() instanceof TrackBlock) return;
        level.setBlock(a.pos(), AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, shape(a.dir())), Block.UPDATE_ALL);
    }

    /** One player-style connection from a to b. Returns null when Create accepted it, else Create's reason. */
    static String connect(ServerLevel level, FakePlayer player, Anchor a, Anchor b, Result r) {
        setTrack(level, b);
        BlockState s1 = level.getBlockState(a.pos()), s2 = level.getBlockState(b.pos());
        if (!(s1.getBlock() instanceof ITrackBlock t1) || !(s2.getBlock() instanceof ITrackBlock)) return "anchor is not a track";
        int[] d = Moves.DIR[a.dir()];
        Vec3 axis = new Vec3(d[0], 0, d[1]);
        ItemStack stack = AllBlocks.TRACK.asStack(64);
        stack.set(AllDataComponents.TRACK_CONNECTING_FROM, new TrackPlacement.ConnectingFrom(
                a.pos(), axis, new Vec3(0, 1, 0), t1.getCurveStart(level, a.pos(), s1, axis)));
        // look along the line, as a player making this connection would
        Vec3 look = Vec3.atCenterOf(b.pos()).subtract(Vec3.atCenterOf(a.pos()));
        player.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
        player.setXRot(0);
        TrackPlacement.PlacementInfo info = TrackPlacement.tryConnect(level, player, b.pos(), s2, stack, false, true);
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
