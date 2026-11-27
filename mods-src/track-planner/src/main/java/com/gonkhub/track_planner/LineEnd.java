package com.gonkhub.track_planner;

import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * One end of a line to plan. Either a free point (the line ends near it, at whatever heading suits), or a
 * straight block of existing Create track that the line joins exactly.
 *
 * @param pos      the end's track block
 * @param dirs     headings the line may LEAVE this point on, bit mask over Moves.DIR; -1 for a free point. For
 *                 existing track these are the track's own directions that have no track beyond them: a line
 *                 carries on from the end of a track. Arriving at a goal on heading h is the same as leaving it on h+4.
 * @param turnOnly existing track with track on both sides: the line has to branch off with a curve there
 *                 (as Create makes a junction), since a straight would run over the existing rails
 * @param onTrack  whether this is existing track
 */
public record LineEnd(BlockPos pos, int dirs, boolean turnOnly, boolean onTrack) {

    public static LineEnd free(BlockPos pos) { return new LineEnd(pos, -1, false, false); }

    /** Headings the line may arrive here on, when this end is the goal. */
    public int arrivalDirs() {
        if (dirs == -1) return -1;
        return ((dirs << 4) | (dirs >>> 4)) & 0xFF;
    }

    public String describe() { return pos.toShortString() + (onTrack ? (turnOnly ? " (branching off existing track)" : " (joining existing track)") : ""); }

    /** The track's headings for a straight track block, else 0. */
    static int axis(BlockState st) {
        if (!(st.getBlock() instanceof ITrackBlock) || !st.hasProperty(TrackBlock.SHAPE)) return 0;
        return switch (st.getValue(TrackBlock.SHAPE)) {
            case XO -> 1 << 0 | 1 << 4;
            case ZO -> 1 << 2 | 1 << 6;
            case PD -> 1 << 1 | 1 << 5;
            case ND -> 1 << 3 | 1 << 7;
            default -> 0;
        };
    }

    /** pos itself when it's a straight block of existing track, else a free point there. */
    public static LineEnd at(ServerLevel level, BlockPos pos) {
        int axis = axis(level.getBlockState(pos));
        if (axis == 0) return free(pos);
        int open = 0;
        for (int d = 0; d < 8; d++) {
            if ((axis & (1 << d)) == 0) continue;
            int[] u = Moves.DIR[d];
            boolean beyond = false;
            for (int dy = -1; dy <= 1; dy++)
                beyond |= level.getBlockState(pos.offset(u[0], dy, u[1])).getBlock() instanceof ITrackBlock;
            if (!beyond) open |= 1 << d;
        }
        return open == 0 ? new LineEnd(pos, axis, true, true) : new LineEnd(pos, open, false, true);
    }

    /** The top of a column (a point clicked on the map): existing track there, else just above the ground. */
    public static LineEnd column(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = top + 1; y >= top - 3; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (level.getBlockState(p).getBlock() instanceof ITrackBlock) return at(level, p);
        }
        Terrain t = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        return free(new BlockPos(x, t.surface(x, z) + 1, z));
    }
}
