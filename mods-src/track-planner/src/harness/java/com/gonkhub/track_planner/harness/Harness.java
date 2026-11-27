package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.Builder;
import com.gonkhub.track_planner.Moves.Move;
import com.gonkhub.track_planner.Planner;
import com.gonkhub.track_planner.Planner.Anchor;
import com.gonkhub.track_planner.Terrain;
import com.gonkhub.track_planner.TrackPlanner;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import net.minecraft.core.BlockPos;
import com.gonkhub.track_planner.Moves;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ./gradlew runHarness: on a fresh world, plans and builds a set of lines, then asks Create's railway
 * manager whether each one is a single network a train can route over. Writes run/harness-out/.
 * Uses the same seed and endpoints as the Create: Auto Track test where a comparison makes sense.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID)
public final class Harness {
    static final Path OUT = Path.of("harness-out");
    static PrintWriter log;
    public static final List<Runnable> later = new ArrayList<>();
    static int ticks;

    /** Offsets from world spawn. map = also write a before-heightmap of the area for plotting. */
    record Case(String name, int sx, int sz, int gx, int gz, boolean map) {
        /** b* cases go through the blueprint path: plan, save, load back, build from the loaded copy */
        boolean blueprint() { return name.startsWith("b"); }
        /** a* cases plan in the background while the server ticks, then build the blueprint */
        boolean async() { return name.startsWith("a"); }
        /** s* cases build the blueprint the survival way, with a test player walking along it */
        boolean survival() { return name.startsWith("s"); }
    }

    static final List<Case> CASES = List.of(
            new Case("c1_autotrack_case1", 0, 0, 110, 80, true),
            new Case("c2_600", 0, 0, 450, -400, true),
            new Case("c3_autotrack_case6", -300, -300, -1300, -1100, false),
            new Case("c4_3km", 300, 300, 2800, 1900, false),
            new Case("b1_blueprint", -20, -20, -260, 140, true),
            new Case("a4_async_3km", 300, 300, 2800, 1900, false),
            new Case("s1_survival", -60, 220, -330, 420, false));

    @SubscribeEvent
    static void started(ServerStartedEvent e) throws Exception {
        if (!Boolean.getBoolean("track_planner.harness")) return;
        Files.createDirectories(OUT);
        log = new PrintWriter(Files.newBufferedWriter(OUT.resolve("report.txt")), true);
        ServerLevel level = e.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        log.println("seed " + level.getSeed() + ", spawn " + spawn.toShortString());
        var recipe = e.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("track_planner", "rail_planner"));
        log.println("Rail Planner recipe loaded: " + recipe.isPresent() + recipe.map(r -> " (" + r.value().getType() + ", makes "
                + r.value().getResultItem(level.registryAccess()).getHoverName().getString() + ")").orElse(""));
        if (Boolean.getBoolean("track_planner.inspectOnly")) { inspectWorld(level); return; }
        String only = System.getProperty("track_planner.cases", "");
        for (Case c : CASES) {
            if (!only.isEmpty() && !Arrays.asList(only.split(",")).contains(c.name())) continue;
            if (c.async()) { startAsync(level, spawn, c); continue; }
            if (c.survival()) {
                try { SurvivalTest.run(level, spawn, c.name(), c.sx(), c.sz(), c.gx(), c.gz(), log); }
                catch (Throwable t) { log.println("CRASH in " + c.name()); t.printStackTrace(log); }
                continue;
            }
            try { run(level, spawn, c); }
            catch (Throwable t) { log.println("CRASH in " + c.name()); t.printStackTrace(log); }
        }
    }

    static void run(ServerLevel level, BlockPos spawn, Case c) throws Exception {
        log.println("\n=== " + c);
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        int sx = spawn.getX() + c.sx(), sz = spawn.getZ() + c.sz(), gx = spawn.getX() + c.gx(), gz = spawn.getZ() + c.gz();
        BlockPos start = new BlockPos(sx, real.surface(sx, sz) + 1, sz);
        BlockPos goal = new BlockPos(gx, real.surface(gx, gz) + 1, gz);
        log.printf("start %s, goal %s, straight-line %.0f blocks, %+d height%n", start.toShortString(), goal.toShortString(),
                Math.hypot(gx - sx, gz - sz), goal.getY() - start.getY());

        // ---- plan: corridor on quick estimates, then exact worldgen terrain for the corridor's chunks ----
        Planner.Weights w = new Planner.Weights();
        Planner planner = new Planner(level, Terrain.Mode.PREFETCH, w);
        Planner.Plan plan = planner.plan(start, -1, goal);
        var st = plan.stats();
        log.printf("plan: corridor %d ms (%d cells), terrain prefetch %d ms (%d chunks), route %d ms (%d nodes), heights %d ms; columns: %d real, %d worldgen-exact, %d estimated; max placement %d%n",
                st.corridorMs, st.corridorCells, st.prefetchMs, st.prefetchChunks, st.routeMs, st.nodesExpanded, st.profileMs,
                st.realColumns, st.noiseColumns, st.estimatedColumns, st.maxPlacement);
        if (st.failure != null) { log.println("FAILED: " + st.failure); return; }
        log.printf("valleys: %d route passes, kept pass %d; points floating more than %d blocks: %d on the first pass, %d on the kept plan%n",
                st.passes, st.chosenPass, w.comfortLift, st.highFloatFirst, st.highFloat);
        List<Anchor> an = plan.anchors();
        List<Move> mv = plan.moves();
        double len = mv.stream().mapToDouble(Move::length).sum();
        Map<String, Integer> kinds = new TreeMap<>();
        mv.forEach(m -> kinds.merge(m.kind().toString(), 1, Integer::sum));
        Anchor end = an.get(an.size() - 1);
        log.printf("moves %d %s, planned length %.0f (%.0f%% over straight line), ends %d blocks from goal, height %d -> %d (goal %d)%n",
                mv.size(), kinds, len, 100 * (len / Math.hypot(gx - sx, gz - sz) - 1),
                Math.max(Math.abs(end.x() - gx), Math.abs(end.z() - gz)), start.getY(), end.y(), goal.getY());

        // ---- what the ground really is along the line (loads those chunks), vs. what the planner assumed ----
        long tl = System.nanoTime();
        double errSum = 0; int errMax = 0, errN = 0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(OUT.resolve(c.name() + "_line.csv")))) {
            pw.println("i,x,z,track_y,ground_planned,ground_real,water_real,water_planned");
            int idx = 0;
            for (int i = 0; i < mv.size(); i++) {
                Anchor a = an.get(i), b = an.get(i + 1);
                int[][] s = mv.get(i).samples();
                for (int j = 0; j < s.length; j++) {
                    int x = a.x() + s[j][0], z = a.z() + s[j][1];
                    double ty = a.y() + (b.y() - a.y()) * (j + 1) / (double) s.length;
                    int gp = planner.terrain().ground(x, z), gr = real.ground(x, z), wr = real.water(x, z);
                    int wp = planner.terrain().water(x, z);
                    pw.printf("%d,%d,%d,%.2f,%d,%d,%d,%d%n", idx++, x - sx, z - sz, ty, gp, gr, wr == Integer.MIN_VALUE ? -999 : wr, wp == Integer.MIN_VALUE ? -999 : wp);
                    int err = Math.abs(gp - gr); errSum += err; errN++; errMax = Math.max(errMax, err);
                }
            }
        }
        log.printf("ground estimate error along the line: mean %.1f, max %d blocks (loading the line's chunks took %d ms)%n",
                errSum / errN, errMax, (System.nanoTime() - tl) / 1_000_000);
        if (c.map()) {
            int x0 = Math.min(sx, gx) - 40, x1 = Math.max(sx, gx) + 40, z0 = Math.min(sz, gz) - 40, z1 = Math.max(sz, gz) + 40;
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(OUT.resolve(c.name() + "_map.csv")))) {
                pw.println("x,z,ground,water");
                for (int x = x0; x <= x1; x += 2) for (int z = z0; z <= z1; z += 2) {
                    int wr = real.water(x, z);
                    pw.printf("%d,%d,%d,%d%n", x - sx, z - sz, real.ground(x, z), wr == Integer.MIN_VALUE ? -999 : wr);
                }
            }
        }

        // ---- build (b* cases: through a blueprint saved and loaded back, as a player's would be) ----
        Builder.Result r;
        if (c.blueprint()) {
            com.gonkhub.track_planner.Blueprint bp = com.gonkhub.track_planner.Blueprint.of(com.gonkhub.track_planner.Blueprints.get(level.getServer()).nextId(), "harness", null, plan, start);
            com.gonkhub.track_planner.Blueprints.get(level.getServer()).add(level.getServer(), bp);
            var loaded = com.gonkhub.track_planner.Blueprint.load(bp.save());
            boolean same = loaded.anchors().equals(plan.anchors()) && loaded.moves().equals(plan.moves())
                    && loaded.forClient().equals(bp.forClient());
            log.printf("blueprint #%d: %d pieces for the client (%d bytes as NBT), about %d track items; save/load round trip identical: %s%n",
                    bp.id(), bp.forClient().getList("pieces", 10).size(), bp.forClient().sizeInBytes(), bp.trackItems(), same);
            r = com.gonkhub.track_planner.TrackPlanner.buildBlueprint(level, loaded);
            log.println("blueprints left after building it: " + com.gonkhub.track_planner.Blueprints.get(level.getServer()).all().size());
        } else {
            r = Builder.build(level, plan, w);
        }
        log.printf("build: earthworks %d ms, track %d ms; %d placements, %d refused by Create; %d track items%n",
                r.earthworksMs, r.trackMs, r.placements, r.refused, r.trackItems);
        r.blocks.forEach((k, v) -> log.println("   " + k + ": " + v));
        r.refusals.forEach(s -> log.println("   REFUSED " + s));
        inspect(level, plan, real);

        // keep the line ticking, as it would with players along it, so Create registers the new track
        for (long ch : r.chunks) level.setChunkForced(ChunkPos.getX(ch), ChunkPos.getZ(ch), true);
        Vec3 a = Vec3.atBottomCenterOf(an.get(0).pos()), b = Vec3.atBottomCenterOf(end.pos());
        later.add(() -> graphCheck(c.name(), a, b, len));
        // and look again once Create has had its scheduled ticks on the new track
        later.add(() -> { log.println("--- after 20 s of game time: " + c.name()); inspect(level, plan, real); });
    }

    /**
     * Walks what was actually built. Straight runs: every block between two anchors must be track with
     * nothing solid above it. Curves: Create's own curve, sampled every quarter block, must have nothing
     * solid or wet at rail height. Also reports how high the line floats above the ground.
     */
    public static void inspect(ServerLevel level, Planner.Plan plan, Terrain real) {
        List<Anchor> an = plan.anchors();
        int straightBlocks = 0, gaps = 0, buriedStraight = 0, curveSamples = 0, buriedCurve = 0, wet = 0;
        List<String> examples = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < plan.moves().size(); i++) {
            Anchor a = an.get(i), b = an.get(i + 1);
            Move m = plan.moves().get(i);
            if (m.kind() == com.gonkhub.track_planner.Moves.Kind.STRAIGHT && a.y() == b.y()) {
                int[] d = com.gonkhub.track_planner.Moves.DIR[a.dir()];
                int steps = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
                for (int k = 0; k <= steps; k++) {
                    p.set(a.x() + d[0] * k, a.y(), a.z() + d[1] * k);
                    straightBlocks++;
                    if (!(level.getBlockState(p).getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock)) {
                        gaps++;
                        if (examples.size() < 6) examples.add("gap at " + p.toShortString() + ": " + level.getBlockState(p).getBlock());
                    }
                    if (level.getBlockState(p.move(0, 1, 0)).blocksMotion()) buriedStraight++;
                }
            }
        }
        double maxLift = 0, liftSum = 0; int liftN = 0;
        for (Anchor a : an) {
            if (!(level.getBlockEntity(a.pos()) instanceof com.simibubi.create.content.trains.track.TrackBlockEntity tbe)) continue;
            for (com.simibubi.create.content.trains.track.BezierConnection placed : tbe.getConnections().values()) {
                if (!placed.isPrimary()) continue;
                var bc = Builder.settled(placed);
                int n = Math.max(8, (int) Math.ceil(bc.getLength() * 4));
                for (int k = 0; k <= n; k++) {
                    Vec3 at = bc.getPosition(k / (double) n);
                    curveSamples++;
                    for (int dy = 0; dy <= 1; dy++) {
                        var st = level.getBlockState(p.set(Math.floor(at.x), Math.floor(at.y + 0.05 + dy), Math.floor(at.z)));
                        boolean track = st.getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock
                                || st.getBlock() instanceof com.simibubi.create.content.trains.track.FakeTrackBlock;
                        if (!track && st.blocksMotion()) {
                            buriedCurve++;
                            if (examples.size() < 6) examples.add("rail inside " + st.getBlock() + " at " + p.toShortString());
                            break;
                        }
                        if (!st.getFluidState().isEmpty() && dy == 0) wet++;
                    }
                }
            }
        }
        for (int i = 0; i < plan.moves().size(); i++) {
            Anchor a = an.get(i), b = an.get(i + 1);
            int[][] s = plan.moves().get(i).samples();
            for (int j = 0; j < s.length; j++) {
                int x = a.x() + s[j][0], z = a.z() + s[j][1];
                double ty = a.y() + (b.y() - a.y()) * (j + 1) / (double) s.length;
                double lift = ty - 1 - real.surface(x, z);
                if (lift > 0) { maxLift = Math.max(maxLift, lift); liftSum += lift; liftN++; }
            }
        }
        log.printf("inspect: straight runs %d blocks, %d gaps, %d with a solid block on the rails; curves %d samples, %d with rails inside a solid block, %d in water%n",
                straightBlocks, gaps, buriedStraight, curveSamples, buriedCurve, wet);
        log.printf("inspect: track floats above ground/water at %d of the sampled points, %.1f blocks on average there, %.0f at most%n", liftN, liftN == 0 ? 0 : liftSum / liftN, maxLift);
        examples.forEach(e -> log.println("   " + e));
    }

    /** Inspect-only: every Create track block in the loaded chunks of an existing world, nothing is changed. */
    static void inspectWorld(ServerLevel level) {
        int tracks = 0, coveredStraight = 0, curves = 0, samples = 0, buried = 0, wet = 0;
        BlockPos firstWet = null;
        java.util.Map<String, Integer> what = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // the harness force-loads every chunk a line passes through, so those are exactly the chunks to check
        for (long ck : level.getForcedChunks()) {
            var chunk = level.getChunk(ChunkPos.getX(ck), ChunkPos.getZ(ck));
            for (var be : chunk.getBlockEntities().values()) {
                if (!(be instanceof com.simibubi.create.content.trains.track.TrackBlockEntity tbe)) continue;
                for (var bc : tbe.getConnections().values()) {
                    if (!bc.isPrimary()) continue;
                    curves++;
                    int n = Math.max(8, (int) Math.ceil(bc.getLength() * 4));
                    for (int k = 0; k <= n; k++) {
                        Vec3 at = bc.getPosition(k / (double) n);
                        samples++;
                        var st = level.getBlockState(p.set(Math.floor(at.x), Math.floor(at.y + 0.05), Math.floor(at.z)));
                        var above = level.getBlockState(p.set(Math.floor(at.x), Math.floor(at.y + 1.05), Math.floor(at.z)));
                        boolean trackish = st.getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock
                                || st.getBlock() instanceof com.simibubi.create.content.trains.track.FakeTrackBlock;
                        if (!trackish && st.blocksMotion()) { buried++; what.merge("rail inside " + st.getBlock().getName().getString(), 1, Integer::sum); }
                        else if (above.blocksMotion() && !(above.getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock)) { buried++; what.merge("covered by " + above.getBlock().getName().getString(), 1, Integer::sum); }
                        if (!st.getFluidState().isEmpty()) {
                            wet++;
                            if (firstWet == null) firstWet = p.immutable();
                            if (wet <= 8) log.printf("   wet rail at %s: %s, source=%s, track block here=%s%n", p.set(Math.floor(at.x), Math.floor(at.y + 0.05), Math.floor(at.z)).toShortString(),
                                    st.getBlock().getName().getString(), st.getFluidState().isSource(), st.getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock || st.getBlock() instanceof com.simibubi.create.content.trains.track.FakeTrackBlock);
                        }
                    }
                }
            }
            // straight track blocks with something solid right on top of them
            int minY = level.getMinBuildHeight();
            for (int sec = 0; sec < chunk.getSections().length; sec++) {
                var section = chunk.getSections()[sec];
                if (section.hasOnlyAir()) continue;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    if (!(section.getBlockState(x, y, z).getBlock() instanceof com.simibubi.create.content.trains.track.TrackBlock)) continue;
                    tracks++;
                    p.set(chunk.getPos().getMinBlockX() + x, minY + sec * 16 + y + 1, chunk.getPos().getMinBlockZ() + z);
                    var up = level.getBlockState(p);
                    if (up.blocksMotion() && !(up.getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock)) {
                        coveredStraight++; what.merge("track block covered by " + up.getBlock().getName().getString(), 1, Integer::sum);
                    }
                }
            }
        }
        // around the first wet rail: every fluid within 6 blocks, and whether the sky is open above it
        if (firstWet != null) {
            log.println("fluids within 6 blocks of " + firstWet.toShortString() + " (dx,dy,dz kind source level sky):");
            for (int dx = -6; dx <= 6; dx++) for (int dy = -3; dy <= 6; dy++) for (int dz = -6; dz <= 6; dz++) {
                BlockPos q = firstWet.offset(dx, dy, dz);
                var fs = level.getFluidState(q);
                if (!fs.isEmpty()) log.printf("   %d,%d,%d %s src=%s lvl=%d sky=%s%n", dx, dy, dz, fs.getType().builtInRegistryHolder().key().location().getPath(),
                        fs.isSource(), fs.getAmount(), level.canSeeSky(q));
            }
            StringBuilder col = new StringBuilder();
            for (int dy = -3; dy <= 6; dy++) col.append(dy).append('=').append(level.getBlockState(firstWet.offset(0, dy, 0)).getBlock().getName().getString()).append("  ");
            log.println("column: " + col);
        }
        log.printf("inspect-only: %d track blocks, %d of them with a solid block on top; %d curves, %d samples, %d buried or covered, %d in water%n",
                tracks, coveredStraight, curves, samples, buried, wet);
        what.forEach((k, v) -> log.println("   " + k + ": " + v));
        log.println("done");
        log.close();
        level.getServer().halt(false);
        log = null;
    }

    public static void graphCheck(String name, Vec3 a, Vec3 b, double planned) {
        log.println("\n--- track network check: " + name);
        TrackNode na = null, nb = null;
        TrackGraph ga = null, gb = null;
        double da = 3, db = 3;
        for (TrackGraph g : Create.RAILWAYS.trackNetworks.values())
            for (TrackNodeLocation loc : g.getNodes()) {
                Vec3 v = loc.getLocation();
                if (v.distanceTo(a) < da) { da = v.distanceTo(a); na = g.locateNode(loc); ga = g; }
                if (v.distanceTo(b) < db) { db = v.distanceTo(b); nb = g.locateNode(loc); gb = g; }
            }
        log.println("networks in world: " + Create.RAILWAYS.trackNetworks.size() + "; start found: " + (na != null) + ", end found: " + (nb != null));
        if (ga == null || gb == null) return;
        log.println("start and end on the same network: " + (ga == gb) + " (start network has " + ga.getNodes().size() + " nodes)");
        if (ga != gb) return;
        Map<TrackNode, Double> d = new HashMap<>();
        PriorityQueue<Object[]> q = new PriorityQueue<>(Comparator.comparingDouble(o -> (Double) o[0]));
        d.put(na, 0.0);
        q.add(new Object[]{0.0, na});
        while (!q.isEmpty()) {
            Object[] o = q.poll();
            TrackNode n = (TrackNode) o[1];
            double dist = (Double) o[0];
            if (dist > d.get(n)) continue;
            for (Map.Entry<TrackNode, TrackEdge> en : ga.getConnectionsFrom(n).entrySet()) {
                double nd = dist + en.getValue().getLength();
                if (nd < d.getOrDefault(en.getKey(), Double.MAX_VALUE)) { d.put(en.getKey(), nd); q.add(new Object[]{nd, en.getKey()}); }
            }
        }
        log.printf("train route start -> end: %s (planned %.0f)%n", d.containsKey(nb) ? String.format("%.0f blocks of rail", d.get(nb)) : "NOT CONNECTED", planned);
    }

    // ---- background planning while the server ticks ----
    static volatile boolean asyncPending;
    static long tickStart, asyncStart, slowTicks, tickCount, tickNanos, maxTick, lastPre, maxGap, longGaps;

    static void startAsync(ServerLevel level, BlockPos spawn, Case c) {
        log.println("\n=== " + c);
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        int sx = spawn.getX() + c.sx(), sz = spawn.getZ() + c.sz(), gx = spawn.getX() + c.gx(), gz = spawn.getZ() + c.gz();
        BlockPos start = new BlockPos(sx, real.surface(sx, sz) + 1, sz), goal = new BlockPos(gx, real.surface(gx, gz) + 1, gz);
        log.printf("start %s, goal %s, straight-line %.0f blocks; planning on the planner thread while the server ticks%n",
                start.toShortString(), goal.toShortString(), Math.hypot(gx - sx, gz - sz));
        asyncPending = true;
        asyncStart = System.nanoTime();
        com.gonkhub.track_planner.PlanJobs.submit(level, () -> new com.gonkhub.track_planner.PlanJobs.Ends(start, -1, goal), "harness", null,
                m -> log.println("planner said: " + m.getString())).thenAccept(b -> {
            asyncPending = false;
            double secs = (System.nanoTime() - asyncStart) / 1e9;
            log.printf("server ticks while planning: %d ticks in %.1f s (%.1f per second, 20 is full speed), slowest tick %.1f ms, average %.2f ms, %d over 50 ms%n",
                    tickCount, secs, tickCount / secs, maxTick / 1e6, tickCount == 0 ? 0 : tickNanos / 1e6 / tickCount, slowTicks);
            log.printf("longest gap between ticks: %.0f ms; gaps over 100 ms: %d%n", maxGap / 1e6, longGaps);
            if (b == null) { log.println("FAILED: no blueprint"); return; }
            // build it the way /trackplan build does, a little each tick, and time the ticks again
            resetTicks();
            asyncPending = true;
            asyncStart = System.nanoTime();
            com.gonkhub.track_planner.TrackPlanner.buildBlueprintGradually(level, b, null, m -> log.println("builder said: " + m.getString())).thenAccept(r -> {
                asyncPending = false;
                double bsecs = (System.nanoTime() - asyncStart) / 1e9;
                log.printf("server ticks while building: %d ticks in %.1f s (%.1f per second), slowest tick %.1f ms, average %.2f ms, %d over 50 ms; longest gap %.0f ms, %d over 100 ms%n",
                        tickCount, bsecs, tickCount / bsecs, maxTick / 1e6, tickCount == 0 ? 0 : tickNanos / 1e6 / tickCount, slowTicks, maxGap / 1e6, longGaps);
                log.printf("build: earthworks %d ms, track %d ms (spread over ticks); %d placements, %d refused by Create; %d track items%n",
                        r.earthworksMs, r.trackMs, r.placements, r.refused, r.trackItems);
                r.refusals.forEach(x -> log.println("   REFUSED " + x));
                inspect(level, b.plan(), real);
                for (long ch : r.chunks) level.setChunkForced(ChunkPos.getX(ch), ChunkPos.getZ(ch), true);
                Vec3 a = Vec3.atBottomCenterOf(b.anchors().get(0).pos()), z = Vec3.atBottomCenterOf(b.anchors().get(b.anchors().size() - 1).pos());
                later.add(() -> graphCheck(c.name(), a, z, b.length()));
                later.add(() -> { log.println("--- after 20 s of game time: " + c.name()); inspect(level, b.plan(), real); });
                ticks = 0;
            });
        });
    }

    static void resetTicks() { slowTicks = tickCount = tickNanos = maxTick = lastPre = maxGap = longGaps = 0; }

    @SubscribeEvent
    static void tickStart(ServerTickEvent.Pre e) {
        tickStart = System.nanoTime();
        // time from one tick's start to the next: 50 ms when the server keeps up
        if (asyncPending && lastPre != 0) {
            long gap = tickStart - lastPre;
            if (gap > 150_000_000L) log.printf("   long gap %.0f ms before a tick during: %s%n", gap / 1e6, com.gonkhub.track_planner.BuildJobs.state());
            maxGap = Math.max(maxGap, gap);
            if (gap > 100_000_000L) longGaps++;
        }
        lastPre = tickStart;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (log == null) return;
        if (asyncPending) {
            long d = System.nanoTime() - tickStart;
            tickCount++; tickNanos += d; maxTick = Math.max(maxTick, d);
            if (d > 50_000_000L) slowTicks++;
            if (d > 100_000_000L) log.printf("   slow tick %.0f ms during: %s%n", d / 1e6, com.gonkhub.track_planner.BuildJobs.state());
            return;
        }
        if (++ticks == 400) {
            later.forEach(Runnable::run);
            log.println("\ndone");
            log.close();
            e.getServer().halt(false);
        }
    }

    private Harness() {}
}
