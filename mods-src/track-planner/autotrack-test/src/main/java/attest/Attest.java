package attest;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Drives Create: Auto Track the way a sneaking player would, on real terrain, and records what it does. */
@Mod("attest")
@EventBusSubscriber(modid = "attest")
public class Attest {
    static final Path OUT = Path.of("attest-out");
    static PrintWriter log;
    static final List<Runnable> afterTicks = new ArrayList<>();
    static int ticks = 0;

    static class Player extends FakePlayer {
        final List<String> chat = new ArrayList<>();
        Player(ServerLevel l) { super(l, new GameProfile(UUID.randomUUID(), "tester")); }
        @Override public void sendSystemMessage(Component c) { chat.add(c.getString()); }
    }

    record Scenario(String name, int ox, int oz, String axis, int tx, int tz, boolean obstacles) {}

    @SubscribeEvent
    static void started(ServerStartedEvent e) throws Exception {
        Files.createDirectories(OUT);
        log = new PrintWriter(Files.newBufferedWriter(OUT.resolve("report.txt")), true);
        MinecraftServer server = e.getServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        log.println("spawn " + spawn);
        List<Scenario> list = List.of(
            new Scenario("s1_turn", 0, 0, "x", 110, 80, true),
            new Scenario("s2_straight", 0, 400, "x", 180, 4, false),
            new Scenario("s3_too_close", 0, 700, "x", 12, 14, false),
            new Scenario("s4_wrong_axis", 0, 800, "x", 5, 120, false),
            new Scenario("s5_z_axis_turn_back", 400, 0, "z", -90, 130, false),
            new Scenario("s6_long", -300, -300, "x", -1000, -800, false));
        for (Scenario s : list) run(level, spawn.offset(s.ox(), 0, s.oz()), s);
    }

    static Block block(String id) { return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState withAxis(BlockState st, String axis) {
        Property p = st.getBlock().getStateDefinition().getProperty("axis");
        return st.setValue(p, (Comparable) p.getValue(axis).get());
    }

    static int surface(ServerLevel l, int x, int z) { l.getChunk(x >> 4, z >> 4); return l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1; }

    static String cat(BlockState st) {
        if (st.isAir()) return "air";
        if (st.getBlock() instanceof TrackBlock) return "track";
        if (st.is(Blocks.WHITE_WOOL)) return "roadbed";
        if (!st.getFluidState().isEmpty()) return "water";
        if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) return "tree";
        if (st.is(Blocks.OAK_PLANKS) || st.is(Blocks.CHEST) || st.is(Blocks.GLASS)) return "building";
        return "ground";
    }

    static void run(ServerLevel level, BlockPos origin, Scenario s) throws Exception {
        log.println("\n=== " + s);
        int bx = origin.getX(), bz = origin.getZ();
        int by = surface(level, bx, bz);           // builder replaces the top ground block
        int gx = bx + s.tx(), gz = bz + s.tz();
        int targetY = surface(level, gx, gz);
        BlockPos builder = new BlockPos(bx, by, bz);
        BlockPos target = new BlockPos(gx, targetY, gz);
        log.printf("builder %s (ground y=%d), target %s (ground y=%d, %+d vs builder)%n", builder, by, target, targetY, targetY - by);

        // Obstacles in s1: a little house with a chest of diamonds, and a glass wall, on the planned line
        if (s.obstacles()) {
            for (int dx = 30; dx <= 36; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 1; dy <= 5; dy++) {
                boolean shell = dx == 30 || dx == 36 || dz == -3 || dz == 3 || dy == 5;
                level.setBlock(new BlockPos(bx + dx, by + dy, bz + dz), shell ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
            BlockPos chest = new BlockPos(bx + 33, by + 1, bz);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            if (level.getBlockEntity(chest) instanceof net.minecraft.world.Container c) c.setItem(0, new ItemStack(Items.DIAMOND, 64));
        }

        // Snapshot the area before
        int x0 = Math.min(bx, gx) - 25, x1 = Math.max(bx, gx) + 25, z0 = Math.min(bz, gz) - 25, z1 = Math.max(bz, gz) + 25;
        boolean big = (long) (x1 - x0) * (z1 - z0) > 400_000;
        if (!big) for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) level.getChunk(cx, cz);
        int[][] before = new int[x1 - x0 + 1][z1 - z0 + 1];
        if (!big) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) before[x - x0][z - z0] = surface(level, x, z);
        Map<String, Integer> changed = new TreeMap<>();
        Map<BlockPos, String> pre = new HashMap<>();
        if (!big) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = by - 1; y <= by + 7; y++) {
            BlockPos p = new BlockPos(x, y, z); pre.put(p, cat(level.getBlockState(p)));
        }

        // Set up the builder like the tooltip says: roadbed on top, track on the roadbed
        level.setBlock(builder, withAxis(block("create_auto_track:builder_block").defaultBlockState(), s.axis()), 3);
        level.setBlock(builder.above(), Blocks.WHITE_WOOL.defaultBlockState(), 3);
        level.setBlock(builder.above(2), block("create:track").defaultBlockState()
            .setValue(TrackBlock.SHAPE, s.axis().equals("x") ? TrackShape.XO : TrackShape.ZO), 3);

        Player p = new Player(level);
        p.setPos(bx + 0.5, by + 3, bz + 0.5);
        p.setShiftKeyDown(true);
        ItemStack selector = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_auto_track:pos_selector")));
        p.setItemInHand(InteractionHand.MAIN_HAND, selector);
        p.getInventory().add(new ItemStack(Items.WHITE_WOOL, 3));
        int invBefore = countInv(p);

        click(p, target);                           // record destination
        long t0 = System.nanoTime();
        click(p, builder);                          // build
        double ms = (System.nanoTime() - t0) / 1e6;
        log.printf("build call took %.0f ms on the server thread%n", ms);
        log.println("chat: " + p.chat);
        log.println("player inventory items before/after: " + invBefore + " / " + countInv(p) + " (selector + 3 white wool)");

        // What was placed
        int tracks = 0, roadbed = 0, curves = 0;
        int tMinY = Integer.MAX_VALUE, tMaxY = Integer.MIN_VALUE;
        if (!big) {
            for (Map.Entry<BlockPos, String> en : pre.entrySet()) {
                String now = cat(level.getBlockState(en.getKey()));
                if (!now.equals(en.getValue())) changed.merge(en.getValue() + " -> " + now, 1, Integer::sum);
                if (now.equals("track")) { tracks++; tMinY = Math.min(tMinY, en.getKey().getY()); tMaxY = Math.max(tMaxY, en.getKey().getY());
                    if (level.getBlockEntity(en.getKey()) instanceof TrackBlockEntity tbe && !tbe.getConnections().isEmpty()) curves++; }
                if (now.equals("roadbed")) roadbed++;
            }
            log.println("track blocks: " + tracks + " (at y " + tMinY + ".." + tMaxY + "), curve ends with a Bezier connection: " + curves + ", roadbed blocks: " + roadbed);
            log.println("block changes in the corridor (y " + (by - 1) + ".." + (by + 7) + "):");
            changed.forEach((k, v) -> log.println("   " + k + ": " + v));
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(x0, by - 5, z0, x1, by + 20, z1));
            log.println("item drops left in the world: " + drops.size());
            log.println("builder block still there: " + cat(level.getBlockState(builder)) + " / " + level.getBlockState(builder).getBlock());

            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(OUT.resolve(s.name() + ".csv")))) {
                w.println("x,z,before,after_top,after_cat_y,after_cat_y1");
                for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
                    w.println((x - bx) + "," + (z - bz) + "," + (before[x - x0][z - z0] - by) + "," + (surface(level, x, z) - by) + ","
                        + cat(level.getBlockState(new BlockPos(x, by, z))) + "," + cat(level.getBlockState(new BlockPos(x, by + 1, z))));
            }
        } else {
            // Long run: walk the expected L shape only
            int straight = 0; for (int x = Math.min(bx, gx); x <= Math.max(bx, gx); x++) if (level.getBlockState(new BlockPos(x, by + 1, bz)).getBlock() instanceof TrackBlock) straight++;
            int straightZ = 0; for (int z = Math.min(bz, gz); z <= Math.max(bz, gz); z++) if (level.getBlockState(new BlockPos(gx, by + 1, z)).getBlock() instanceof TrackBlock) straightZ++;
            log.println("track blocks along x leg: " + straight + ", along z leg: " + straightZ);
            int lo = 999, hi = -999; for (int x = Math.min(bx, gx); x <= Math.max(bx, gx); x += 4) { int g = surface(level, x, bz - 6) - by; lo = Math.min(lo, g); hi = Math.max(hi, g); }
            log.println("ground beside the x leg ranges " + lo + " to " + hi + " blocks relative to the track bed");
        }

        // Keep the line's chunks ticking, as they would be with a player riding along it
        for (int cx = Math.min(bx, gx) - 32 >> 4; cx <= Math.max(bx, gx) + 32 >> 4; cx++) { level.setChunkForced(cx, bz >> 4, true); level.setChunkForced(cx, (bz >> 4) + (gz > bz ? 2 : -2), true); }
        for (int cz = Math.min(bz, gz) - 32 >> 4; cz <= Math.max(bz, gz) + 32 >> 4; cz++) { level.setChunkForced(gx >> 4, cz, true); level.setChunkForced((gx >> 4) + (gx > bx ? -2 : 2), cz, true); }
        // Ask Create's railway manager, a few seconds later, whether the line is one connected graph
        final int fbx = bx, fbz = bz, fgx = gx, fgz = gz, fy = by + 1;
        afterTicks.add(() -> graphCheck(s.name(), new Vec3(fbx + .5, fy, fbz + .5), new Vec3(fgx + .5, fy, fgz + .5)));
    }

    static int countInv(Player p) { int n = 0; for (ItemStack st : p.getInventory().items) n += st.getCount(); return n; }

    static void click(Player p, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, pos, hit));
    }

    static void graphCheck(String name, Vec3 a, Vec3 b) {
        log.println("\n--- track graph check: " + name);
        TrackNode na = null, nb = null; TrackGraph ga = null, gb = null;
        for (TrackGraph g : Create.RAILWAYS.trackNetworks.values()) for (TrackNodeLocation loc : g.getNodes()) {
            Vec3 v = loc.getLocation();
            if (v.distanceTo(a) < 4 && (na == null || v.distanceTo(a) < na.getLocation().getLocation().distanceTo(a))) { na = g.locateNode(loc); ga = g; }
            if (v.distanceTo(b) < 4 && (nb == null || v.distanceTo(b) < nb.getLocation().getLocation().distanceTo(b))) { nb = g.locateNode(loc); gb = g; }
        }
        log.println("graphs in world: " + Create.RAILWAYS.trackNetworks.size() + "; node near start: " + (na != null) + ", node near end: " + (nb != null));
        if (ga == null || gb == null) return;
        log.println("same graph: " + (ga == gb) + "; start graph nodes=" + ga.getNodes().size() + (ga != gb ? ", end graph nodes=" + gb.getNodes().size() : ""));
        if (ga != gb) return;
        // Dijkstra over the graph to see if a train could route start -> end
        Map<TrackNode, Double> d = new HashMap<>(); PriorityQueue<Object[]> q = new PriorityQueue<>(Comparator.comparingDouble(o -> (Double) o[0]));
        d.put(na, 0.0); q.add(new Object[]{0.0, na});
        while (!q.isEmpty()) { Object[] o = q.poll(); TrackNode n = (TrackNode) o[1]; double dist = (Double) o[0]; if (dist > d.get(n)) continue;
            for (Map.Entry<TrackNode, TrackEdge> en : ga.getConnectionsFrom(n).entrySet()) { double nd = dist + en.getValue().getLength();
                if (nd < d.getOrDefault(en.getKey(), Double.MAX_VALUE)) { d.put(en.getKey(), nd); q.add(new Object[]{nd, en.getKey()}); } } }
        log.printf("route start->end along the rails: %s%n", d.containsKey(nb) ? String.format("%.1f blocks", d.get(nb)) : "NOT CONNECTED");
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (log == null) return;
        if (++ticks == 400) {
            afterTicks.forEach(Runnable::run);
            log.println("\ndone");
            log.close();
            e.getServer().halt(false);
        }
    }
}
