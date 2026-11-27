package com.gonkhub.track_planner;

import com.gonkhub.track_planner.Moves.Move;
import com.gonkhub.track_planner.Planner.Anchor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.FakeTrackBlock;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Survival building, Factorio style: hold the Rail Planner near one of your blueprints and the nearest
 * unbuilt piece within REACH builds itself, one piece every EVERY ticks. It is paid for like doing it by hand:
 * track from your inventory (whatever Create track you carry; Create's own placement takes it), and every
 * block in the way dug with a suitable tool from your inventory, which wears down and whose drops you get.
 * Every block broken or placed goes through NeoForge's break and place events first, so land claims apply.
 * A piece is checked in full before anything changes; if something is missing it waits and says what.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID)
public final class SurvivalBuilder {
    public static final double REACH = 20;
    public static final int EVERY = 10;

    /** What happened on one attempt, for the player and for tests. */
    public record Outcome(boolean built, String message) {}

    static final Map<UUID, String> lastMessage = new HashMap<>();

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        Blueprints all = Blueprints.get(server);
        all.flush(server);
        if (server.getTickCount() % EVERY != 0 || all.all().isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.level().dimension() != Level.OVERWORLD || !holdsPlanner(p)) continue;
            Outcome o = buildNear(p);
            if (o.message() == null) continue;
            // repeat a complaint every 2 s at most; progress always shows
            boolean same = o.message().equals(lastMessage.get(p.getUUID()));
            if (o.built() || !same || server.getTickCount() % 40 == 0)
                p.displayClientMessage(Component.literal(o.message()).withStyle(o.built() ? ChatFormatting.AQUA : ChatFormatting.GOLD), true);
            lastMessage.put(p.getUUID(), o.message());
        }
    }

    static boolean holdsPlanner(ServerPlayer p) {
        return p.getMainHandItem().is(TrackPlanner.RAIL_PLANNER.get()) || p.getOffhandItem().is(TrackPlanner.RAIL_PLANNER.get());
    }

    /** Builds the nearest unbuilt piece within reach of this player, if they may and can. */
    public static Outcome buildNear(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        Blueprints all = Blueprints.get(level.getServer());
        Blueprint best = null;
        int bestMove = -1;
        double bestDist = REACH;
        for (Blueprint b : all.all()) {
            if (!b.mayManage(p)) continue;
            for (int i = 0; i < b.moves().size(); i++) {
                if (b.isBuilt(i)) continue;
                double d = distance(p.position(), b, i);
                if (d <= bestDist) { bestDist = d; best = b; bestMove = i; }
            }
        }
        if (best == null) return new Outcome(false, null);
        String why = tryBuild(level, best, bestMove, p);
        if (why != null) return new Outcome(false, "Blueprint #" + best.id() + ": " + why);
        all.markBuilt(best, bestMove);
        Anchor end = best.anchors().get(bestMove + 1);
        level.playSound(null, end.pos(), SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.8f, 1.1f);
        int done = best.built().cardinality(), total = best.moves().size();
        if (best.complete()) {
            all.remove(level.getServer(), best.id());
            p.sendSystemMessage(Component.literal("Blueprint #" + best.id() + " is built: " + Math.round(best.length()) + " blocks of track.")
                    .withStyle(ChatFormatting.AQUA));
        }
        return new Outcome(true, String.format("Blueprint #%d: built piece %d of %d", best.id(), done, total));
    }

    /** Closest distance from a point to the samples of one move, at track height. */
    static double distance(Vec3 from, Blueprint b, int i) {
        Anchor a = b.anchors().get(i), c = b.anchors().get(i + 1);
        int[][] s = b.moves().get(i).samples();
        double best = from.distanceTo(Vec3.atBottomCenterOf(a.pos()));
        for (int j = 0; j < s.length; j++) {
            double ty = a.y() + (c.y() - a.y()) * (j + 1) / (double) s.length;
            best = Math.min(best, from.distanceTo(new Vec3(a.x() + s[j][0] + 0.5, ty, a.z() + s[j][1] + 0.5)));
        }
        return best;
    }

    /**
     * Builds move i of the blueprint for this player, paying for it. Null when built, else why not
     * (and then nothing in the world has changed).
     */
    public static String tryBuild(ServerLevel level, Blueprint b, int i, ServerPlayer p) {
        Anchor a = b.anchors().get(i), c = b.anchors().get(i + 1);
        Move m = b.moves().get(i);
        Planner.Weights w = new Planner.Weights();
        boolean creative = p.isCreative();

        Item track = pickTrack(p);
        if (track == null && creative) track = AllBlocks.TRACK.asItem();
        if (track == null) return "carry some Create track";

        // ---- what has to go, and where track goes ----
        Map<BlockPos, Integer> columns = new LinkedHashMap<>();   // column (y ignored) -> track height
        columns.put(new BlockPos(a.x(), 0, a.z()), a.y());
        int[][] s = m.samples();
        for (int j = 0; j < s.length; j++)
            columns.put(new BlockPos(a.x() + s[j][0], 0, a.z() + s[j][1]), (int) Math.floor(a.y() + (c.y() - a.y()) * (j + 1) / (double) s.length));
        Set<BlockPos> place = new LinkedHashSet<>();
        Set<BlockPos> dig = new LinkedHashSet<>();
        columns.forEach((col, ty) -> {
            place.add(new BlockPos(col.getX(), ty, col.getZ()));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                for (int y = ty; y < ty + w.clearance; y++) dig.add(new BlockPos(col.getX() + dx, y, col.getZ() + dz));
        });
        if (!Blueprint.isLevelStraight(m, a, c)) {
            // the curve Create will lay, a block either side
            BezierConnection bc = Blueprint.curve(a, c);
            int n = Math.max(8, (int) Math.ceil(bc.getLength() * 4));
            for (int k = 0; k <= n; k++) {
                double t = k / (double) n;
                Vec3 at = bc.getPosition(t), ahead = bc.getPosition(Math.min(1, t + 0.01)).subtract(bc.getPosition(Math.max(0, t - 0.01)));
                Vec3 side = new Vec3(-ahead.z, 0, ahead.x).normalize();
                for (int off = -1; off <= 1; off++)
                    for (int dy = 0; dy < w.clearance; dy++)
                        dig.add(BlockPos.containing(at.x + side.x * off, at.y + 0.05 + dy, at.z + side.z * off));
            }
        }
        List<BlockPos> toDig = new ArrayList<>();
        for (BlockPos pos : dig) if (needsDigging(level.getBlockState(pos))) toDig.add(pos);
        // sand and gravel resting on what goes would fall onto the rails
        for (BlockPos pos : new ArrayList<>(toDig)) {
            BlockPos up = pos.above();
            while (level.getBlockState(up).getBlock() instanceof FallingBlock && !dig.contains(up)) { toDig.add(up); dig.add(up); up = up.above(); }
        }

        // ---- may they? ----
        for (BlockPos pos : place) {
            if (!level.mayInteract(p, pos)) return "the track would go into protected land at " + pos.toShortString();
            BlockEvent.EntityPlaceEvent ev = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, pos), level.getBlockState(pos.below()), p);
            if (NeoForge.EVENT_BUS.post(ev).isCanceled()) return "the track would go into protected land at " + pos.toShortString();
        }
        for (BlockPos pos : toDig) {
            BlockState st = level.getBlockState(pos);
            if (!st.getFluidState().isEmpty() && st.canBeReplaced()) continue;
            if (!level.mayInteract(p, pos) || NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, st, p)).isCanceled())
                return "can't dig " + name(st) + " at " + pos.toShortString() + " (protected)";
            if (st.getDestroySpeed(level, pos) < 0) return "can't dig through " + name(st) + " at " + pos.toShortString();
        }

        // ---- can they pay? ----
        int needTrack = Blueprint.isLevelStraight(m, a, c) ? Math.max(Math.abs(c.x() - a.x()), Math.abs(c.z() - a.z())) + 1
                : Blueprint.curve(a, c).getTrackItemCost() + 2;
        if (!creative) {
            int have = count(p, track);
            if (have < needTrack) return String.format("need %d %s, carrying %d", needTrack, name(track), have);
        }
        Map<BlockPos, ItemStack> toolFor = new HashMap<>();
        if (!creative) {
            Map<ItemStack, Integer> uses = new IdentityHashMap<>();
            for (BlockPos pos : toDig) {
                BlockState st = level.getBlockState(pos);
                if (free(level, pos, st)) continue;
                ItemStack tool = bestTool(p, st, uses);
                if (tool == null) return "need " + toolKind(st) + " for " + name(st);
                uses.merge(tool, 1, Integer::sum);
                toolFor.put(pos, tool);
            }
        }

        // ---- build ----
        Builder.Result r = new Builder.Result();
        for (BlockPos pos : toDig) {
            BlockState st = level.getBlockState(pos);
            if (st.isAir() || st.getBlock() instanceof ITrackBlock || st.getBlock() instanceof FakeTrackBlock) continue;
            ItemStack tool = toolFor.getOrDefault(pos, ItemStack.EMPTY);
            if (!creative && st.getFluidState().isEmpty())
                for (ItemStack drop : Block.getDrops(st, level, pos, level.getBlockEntity(pos), p, tool)) give(p, drop);
            if (!tool.isEmpty()) tool.hurtAndBreak(1, level, p, item -> {});
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            seal(level, pos, st, dig);
        }
        BlockState base = track instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : AllBlocks.TRACK.getDefaultState();
        for (Anchor an : new Anchor[]{a, c})
            if (Builder.setTrack(level, an, base) && !creative) take(p, track, 1);
        String refusal = Builder.connect(level, p, a, c, r, track);
        if (refusal != null) return "Create refused the piece (" + refusal + ")";
        // the curve as Create laid it may pass a little off the prediction, and where it meets a slope built
        // earlier Create tilts the joint and reshapes that earlier curve too: clear along every curve touching
        // either end, whichever end Create stores it on
        for (Anchor an : new Anchor[]{a, c})
            if (level.getBlockEntity(an.pos()) instanceof TrackBlockEntity tbe)
                for (BezierConnection bc : tbe.getConnections().values())
                    Builder.clearAlong(level, r, bc, w.clearance);
        return null;
    }

    static boolean needsDigging(BlockState st) {
        return !st.isAir() && !(st.getBlock() instanceof ITrackBlock) && !(st.getBlock() instanceof FakeTrackBlock);
    }

    /** Blocks anyone clears by hand: plants, snow, leaves, fluids, anything that breaks instantly. */
    static boolean free(ServerLevel level, BlockPos pos, BlockState st) {
        return st.canBeReplaced() || st.is(BlockTags.LEAVES) || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, pos) == 0;
    }

    /** The fastest tool in the inventory that mines this block properly and has durability left. */
    static ItemStack bestTool(ServerPlayer p, BlockState st, Map<ItemStack, Integer> uses) {
        ItemStack best = null;
        float bestSpeed = 1.0f;
        List<ItemStack> stacks = new ArrayList<>(p.getInventory().items);
        stacks.addAll(p.getInventory().offhand);
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !stack.isDamageableItem()) continue;
            float speed = stack.getDestroySpeed(st);
            if (speed <= bestSpeed) continue;
            if (st.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(st)) continue;
            int left = stack.getMaxDamage() - stack.getDamageValue() - uses.getOrDefault(stack, 0);
            if (left <= 1) continue;   // never break the tool outright
            best = stack;
            bestSpeed = speed;
        }
        return best;
    }

    static String toolKind(BlockState st) {
        if (st.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "a pickaxe";
        if (st.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "a shovel";
        if (st.is(BlockTags.MINEABLE_WITH_AXE)) return "an axe";
        if (st.is(BlockTags.MINEABLE_WITH_HOE)) return "a hoe";
        return "a tool";
    }

    /** Water or lava beside or above a dug gap would flow onto the rails: wall it off with stone (as the creative builder does). */
    static void seal(ServerLevel level, BlockPos pos, BlockState removed, Set<BlockPos> dig) {
        BlockState seal = removed.getBlock() instanceof FallingBlock || !removed.getFluidState().isEmpty() || !removed.isCollisionShapeFullBlock(level, pos)
                ? Blocks.STONE.defaultBlockState() : removed;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos n = pos.relative(d);
            if (dig.contains(n)) continue;
            if (!level.getFluidState(n).isEmpty() && !(level.getBlockState(n).getBlock() instanceof ITrackBlock))
                level.setBlock(n, seal, Block.UPDATE_CLIENTS);
        }
    }

    /** The Create track kind the player carries most of. */
    static Item pickTrack(ServerPlayer p) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack st : p.getInventory().items)
            if (!st.isEmpty() && AllTags.AllBlockTags.TRACKS.matches(st)) counts.merge(st.getItem(), st.getCount(), Integer::sum);
        for (ItemStack st : p.getInventory().offhand)
            if (!st.isEmpty() && AllTags.AllBlockTags.TRACKS.matches(st)) counts.merge(st.getItem(), st.getCount(), Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack st : p.getInventory().items) if (st.is(item)) n += st.getCount();
        for (ItemStack st : p.getInventory().offhand) if (st.is(item)) n += st.getCount();
        return n;
    }

    static void take(ServerPlayer p, Item item, int n) {
        for (List<ItemStack> list : List.of(p.getInventory().items, p.getInventory().offhand))
            for (ItemStack st : list) {
                if (n <= 0) return;
                if (!st.is(item)) continue;
                int k = Math.min(n, st.getCount());
                st.shrink(k);
                n -= k;
            }
    }

    static void give(ServerPlayer p, ItemStack drop) {
        ItemStack copy = drop.copy();
        if (!p.getInventory().add(copy) && !copy.isEmpty()) p.drop(copy, false);
    }

    static String name(BlockState st) { return st.getBlock().getName().getString(); }

    static String name(Item item) { return item.getDescription().getString(); }

    private SurvivalBuilder() {}
}
