package com.gonkhub.track_planner;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

/**
 * Ground heights for the planner, from the best source available for each column:
 *  1. a chunk that is loaded (or prefetched past the features stage): the real world, top solid block
 *     ignoring leaves, logs and water;
 *  2. a prefetched chunk at the NOISE stage: the world generator's own terrain heightmaps, exact apart
 *     from trees, structures and player builds;
 *  3. otherwise a quick estimate from the world generator's density function (+-8 blocks or so).
 * One Terrain is used for one plan; values are cached.
 */
public final class Terrain {
    public enum Mode {
        /** never generate anything: loaded chunks, else the quick estimate */
        ESTIMATE,
        /** prefetch() chunks to the NOISE stage and read their heightmaps (generates terrain, in parallel) */
        PREFETCH,
        /** load every chunk fully (slow, exact; the harness uses it to check the others) */
        LOAD_CHUNKS
    }

    static final double SURFACE_DENSITY = 0.390625; // vanilla's preliminary-surface threshold

    private final ServerLevel level;
    private final Mode mode;
    private final int sea;
    private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<ChunkAccess> fetched = new Long2ObjectOpenHashMap<>();
    private final DensityFunction density;
    public int realColumns, noiseColumns, estimatedColumns;

    public Terrain(ServerLevel level, Mode mode) {
        this.level = level;
        this.mode = mode;
        this.sea = level.getSeaLevel();
        cache.defaultReturnValue(Integer.MIN_VALUE);
        var source = level.getChunkSource();
        density = source.getGenerator() instanceof NoiseBasedChunkGenerator
                ? source.randomState().router().initialDensityWithoutJaggedness() : null;
    }

    public int seaLevel() { return sea; }

    /**
     * Brings the given chunks to at least the NOISE stage, generated in parallel on the world generation
     * threads. This prototype waits on the server thread while it runs (the harness measures how long);
     * a release would spread it over ticks instead.
     */
    public long prefetch(LongCollection chunks) {
        if (mode != Mode.PREFETCH) return 0;
        long t0 = System.nanoTime();
        ServerChunkCache source = level.getChunkSource();
        List<Long> todo = new ArrayList<>();
        for (long c : chunks) if (!fetched.containsKey(c)) todo.add(c);
        for (int from = 0; from < todo.size(); from += 256) {
            List<Long> batch = todo.subList(from, Math.min(todo.size(), from + 256));
            List<CompletableFuture<ChunkResult<ChunkAccess>>> futures = new ArrayList<>();
            for (long c : batch) futures.add(source.getChunkFuture(ChunkPos.getX(c), ChunkPos.getZ(c), ChunkStatus.NOISE, true));
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            while (!all.isDone()) if (!source.pollTask()) LockSupport.parkNanos(50_000);
            for (int i = 0; i < batch.size(); i++) {
                ChunkAccess chunk = futures.get(i).join().orElse(null);
                if (chunk != null) fetched.put((long) batch.get(i), chunk);
            }
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Packed (ground y, water top y or -1 when dry). */
    private int column(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        int v = cache.get(key);
        if (v != Integer.MIN_VALUE) return v;
        long ck = ChunkPos.asLong(x >> 4, z >> 4);
        ChunkAccess chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) chunk = fetched.get(ck);
        if (chunk == null && mode == Mode.LOAD_CHUNKS) chunk = level.getChunk(x >> 4, z >> 4);
        int ground, water = -1;
        if (chunk != null && chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
            realColumns++;
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
            int min = level.getMinBuildHeight();
            while (y > min) {
                BlockState st = chunk.getBlockState(p.setY(y));
                if (!st.getFluidState().isEmpty()) { if (water < 0) water = y; }
                else if (!st.isAir() && !st.is(BlockTags.LOGS) && !st.is(BlockTags.LEAVES) && st.blocksMotion()) break;
                y--;
            }
            ground = y;
        } else if (chunk != null && chunk.getPersistedStatus().isOrAfter(ChunkStatus.NOISE)) {
            noiseColumns++;
            // ChunkAccess.getHeight already returns the top block's y (the heightmap's first free y, minus one)
            ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
            if (top > ground) water = top;
        } else {
            estimatedColumns++;
            ground = estimate(x, z);
            if (ground < sea - 1) water = sea - 1;
        }
        v = ((ground + 2048) << 12) | (water + 2048);
        cache.put(key, v);
        return v;
    }

    /** Top of the solid ground from the generator's density, scanning down in 4-block steps. */
    private int estimate(int x, int z) {
        if (density == null) {
            var source = level.getChunkSource();
            return source.getGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, source.randomState()) - 1;
        }
        int top = Math.min(level.getMaxBuildHeight() - 1, 256), min = level.getMinBuildHeight();
        for (int y = top; y > min; y -= 4)
            if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > SURFACE_DENSITY) {
                for (int f = y + 3; f > y; f--)
                    if (density.compute(new DensityFunction.SinglePointContext(x, f, z)) > SURFACE_DENSITY) return f;
                return y;
            }
        return sea - 1;
    }

    public int ground(int x, int z) { return (column(x, z) >>> 12) - 2048; }

    /** Top of the water in this column, or Integer.MIN_VALUE when it is dry. */
    public int water(int x, int z) {
        int w = (column(x, z) & 0xFFF) - 2048;
        return w == -1 ? Integer.MIN_VALUE : w;
    }

    public boolean wet(int x, int z) { return water(x, z) != Integer.MIN_VALUE; }

    /** Ground, or the water surface where there is water: the height a dry line has to sit on. */
    public int surface(int x, int z) { return wet(x, z) ? Math.max(ground(x, z), water(x, z)) : ground(x, z); }
}
