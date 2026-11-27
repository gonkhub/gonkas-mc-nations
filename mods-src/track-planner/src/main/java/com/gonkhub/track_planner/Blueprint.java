package com.gonkhub.track_planner;

import com.gonkhub.track_planner.Moves.Move;
import com.gonkhub.track_planner.Planner.Anchor;
import com.gonkhub.track_planner.Planner.Plan;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned line that has not been built: the plan itself (to build it later) plus the pieces a client
 * needs to draw it as ghost track. Pieces are what Create would lay: level straights as one run, every
 * curve or slope as the Bezier curve Create's own placement makes between the same two track blocks.
 */
public record Blueprint(int id, String owner, BlockPos start, BlockPos goal, List<Anchor> anchors, List<Move> moves,
                        double length, int trackItems) {

    public Plan plan() {
        return new Plan(anchors, moves, goal, 0, new Planner.Stats());
    }

    public static Blueprint of(int id, String owner, Plan plan, BlockPos start) {
        double length = plan.moves().stream().mapToDouble(Move::length).sum();
        int items = 0;
        for (CompoundTag piece : pieces(plan.anchors(), plan.moves())) {
            if (piece.getString("type").equals("curve"))
                items += new BezierConnection(piece.getCompound("curve"), BlockPos.ZERO).getTrackItemCost() + 1;
            else {
                int[] a = piece.getIntArray("from"), b = piece.getIntArray("to");
                items += Math.max(Math.abs(b[0] - a[0]), Math.abs(b[2] - a[2]));
            }
        }
        return new Blueprint(id, owner, start, plan.goal(), plan.anchors(), plan.moves(), length, items);
    }

    /** Level straights on one heading become one run; everything else is a curve as Create would lay it. */
    public static List<CompoundTag> pieces(List<Anchor> anchors, List<Move> moves) {
        List<CompoundTag> out = new ArrayList<>();
        int i = 0;
        while (i < moves.size()) {
            Anchor a = anchors.get(i);
            CompoundTag piece = new CompoundTag();
            if (isLevelStraight(moves.get(i), a, anchors.get(i + 1))) {
                int j = i;
                while (j + 1 < moves.size() && moves.get(j + 1).dir() == moves.get(i).dir()
                        && isLevelStraight(moves.get(j + 1), anchors.get(j + 1), anchors.get(j + 2)))
                    j++;
                Anchor b = anchors.get(j + 1);
                piece.putString("type", "straight");
                piece.putIntArray("from", new int[]{a.x(), a.y(), a.z()});
                piece.putIntArray("to", new int[]{b.x(), b.y(), b.z()});
                i = j + 1;
            } else {
                piece.putString("type", "curve");
                piece.put("curve", curve(a, anchors.get(i + 1)).write(BlockPos.ZERO));
                i++;
            }
            out.add(piece);
        }
        return out;
    }

    static boolean isLevelStraight(Move m, Anchor a, Anchor b) { return m.kind() == Moves.Kind.STRAIGHT && a.y() == b.y(); }

    /** The curve TrackPlacement.tryConnect makes between two flat track blocks, before any slope tilting. */
    static BezierConnection curve(Anchor a, Anchor b) {
        int[] da = Moves.DIR[a.dir()], db = Moves.DIR[b.dir()];
        Vec3 axisA = new Vec3(da[0], 0, da[1]), axisB = new Vec3(-db[0], 0, -db[1]);  // both point into the curve
        Vec3 endA = Vec3.atBottomCenterOf(a.pos()).add(axisA.scale(.5)), endB = Vec3.atBottomCenterOf(b.pos()).add(axisB.scale(.5));
        Vec3 up = new Vec3(0, 1, 0);
        return new BezierConnection(Couple.create(a.pos(), b.pos()), Couple.create(endA, endB),
                Couple.create(axisA.normalize(), axisB.normalize()), Couple.create(up, up), true, false, TrackMaterial.ANDESITE);
    }

    // ---- storage ----

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("owner", owner);
        t.putIntArray("start", new int[]{start.getX(), start.getY(), start.getZ()});
        t.putIntArray("goal", new int[]{goal.getX(), goal.getY(), goal.getZ()});
        ListTag an = new ListTag();
        for (Anchor a : anchors) an.add(new IntArrayTag(new int[]{a.x(), a.y(), a.z(), a.dir()}));
        t.put("anchors", an);
        ListTag mv = new ListTag();
        for (Move m : moves) mv.add(new IntArrayTag(new int[]{m.dir(), Moves.MOVES.get(m.dir()).indexOf(m)}));
        t.put("moves", mv);
        t.putDouble("length", length);
        t.putInt("trackItems", trackItems);
        return t;
    }

    public static Blueprint load(CompoundTag t) {
        List<Anchor> anchors = new ArrayList<>();
        for (Tag e : t.getList("anchors", Tag.TAG_INT_ARRAY)) {
            int[] v = ((IntArrayTag) e).getAsIntArray();
            anchors.add(new Anchor(v[0], v[1], v[2], v[3]));
        }
        List<Move> moves = new ArrayList<>();
        for (Tag e : t.getList("moves", Tag.TAG_INT_ARRAY)) {
            int[] v = ((IntArrayTag) e).getAsIntArray();
            moves.add(Moves.MOVES.get(v[0]).get(v[1]));
        }
        int[] s = t.getIntArray("start"), g = t.getIntArray("goal");
        return new Blueprint(t.getInt("id"), t.getString("owner"), new BlockPos(s[0], s[1], s[2]), new BlockPos(g[0], g[1], g[2]),
                anchors, moves, t.getDouble("length"), t.getInt("trackItems"));
    }

    /** What a client gets: enough to draw and label it, not the plan. */
    public CompoundTag forClient() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("owner", owner);
        t.putDouble("length", length);
        t.putInt("trackItems", trackItems);
        ListTag ps = new ListTag();
        ps.addAll(pieces(anchors, moves));
        t.put("pieces", ps);
        return t;
    }
}
