package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.Blueprint;
import com.gonkhub.track_planner.Blueprints;
import com.gonkhub.track_planner.Planner;
import com.gonkhub.track_planner.Terrain;
import com.gonkhub.track_planner.TrackPlanner;
import com.gonkhub.track_planner.layer.TrackLayer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.io.PrintWriter;
import java.util.UUID;

/**
 * The track-laying car's core, driven the way a train would drive it: a layer moving along a blueprint
 * with a chest's worth of storage. No train is assembled; the adapters only feed position, owner and storage.
 */
final class LayerTest {
    static void run(ServerLevel level, BlockPos spawn, String name, int sdx, int sdz, int gdx, int gdz, PrintWriter log) {
        log.println("\n=== track layer case " + name);
        log.println("Create movement behaviour registered for the Track Layer: " + (MovementBehaviour.REGISTRY.get(TrackPlanner.TRACK_LAYER.get()) != null));
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        int sx = spawn.getX() + sdx, sz = spawn.getZ() + sdz, gx = spawn.getX() + gdx, gz = spawn.getZ() + gdz;
        BlockPos start = new BlockPos(sx, real.surface(sx, sz) + 1, sz), goal = new BlockPos(gx, real.surface(gx, gz) + 1, gz);
        UUID owner = UUID.nameUUIDFromBytes("train-owner".getBytes()), trainId = UUID.randomUUID();

        Planner.Plan plan = new Planner(level, Terrain.Mode.PREFETCH, new Planner.Weights()).plan(start, -1, goal);
        if (plan.stats().failure != null) { log.println("FAILED to plan: " + plan.stats().failure); return; }
        Blueprints all = Blueprints.get(level.getServer());
        Blueprint b = Blueprint.of(all.nextId(), "TrainOwner", owner, plan, start);
        all.add(level.getServer(), b);
        int n = b.moves().size();
        log.printf("blueprint #%d: %d pieces, %.0f blocks, about %d track items%n", b.id(), n, b.length(), b.trackItems());
        for (var an : b.anchors()) level.getChunk(an.x() >> 4, an.z() >> 4);

        // a train somebody else owns does nothing to this blueprint
        ItemStackHandler other = kit(640);
        var r0 = TrackLayer.step(level, at(b, 0), UUID.randomUUID(), other, UUID.randomUUID());
        log.println("someone else's train: built=" + r0.built() + ", message=" + r0.message());

        // short of track: must ask for the brakes near the first unbuilt piece
        ItemStackHandler poor = kit(4);
        var r1 = TrackLayer.step(level, at(b, 0), owner, poor, trainId);
        log.println("4 track on board: built=" + r1.built() + ", brake=" + r1.brake() + ", says \"" + r1.message() + "\"");

        // fully stocked: run the length of the line
        ItemStackHandler stock = kit(640);
        int trackBefore = count(stock, AllBlocks.TRACK.asItem()), built = 0, brakes = 0;
        for (int i = 0; i <= n; i++) {
            Vec3 pos = at(b, i);
            for (int k = 0; k < 6; k++) {
                var r = TrackLayer.step(level, pos, owner, stock, trainId);
                if (r.brake()) brakes++;
                if (r.built()) built++; else break;
            }
        }
        int wear = 0, loot = 0;
        for (int i = 0; i < stock.getSlots(); i++) {
            ItemStack st = stock.getStackInSlot(i);
            if (st.isEmpty()) continue;
            if (st.isDamageableItem()) wear += st.getDamageValue();
            else if (!st.is(AllBlocks.TRACK.asItem())) loot += st.getCount();
        }
        log.printf("stocked run: built %d of %d pieces, brakes asked %d times, blueprint finished and removed: %s%n", built, n, brakes, all.byId(b.id()) == null);
        log.printf("from storage: %d track (estimate %d), %d tool durability (expect 0: it only lays track); %d items from blocks the anchors replaced%n",
                trackBefore - count(stock, AllBlocks.TRACK.asItem()), b.trackItems(), wear, loot);

        log.println("(blocks in the way are left for the train's drills, so buried rails are expected here)");
        Harness.inspect(level, b.plan(), real);
        for (var an : b.anchors()) level.setChunkForced(an.x() >> 4, an.z() >> 4, true);
        Vec3 va = Vec3.atBottomCenterOf(b.anchors().get(0).pos()), vb = Vec3.atBottomCenterOf(b.anchors().get(n).pos());
        Harness.later.add(() -> Harness.graphCheck(name, va, vb, b.length()));
        Harness.later.add(() -> { log.println("--- after 20 s of game time: " + name); Harness.inspect(level, b.plan(), real); });
    }

    /** Where the layer would be with the train standing on anchor i: on the track, a block up. */
    static Vec3 at(Blueprint b, int i) {
        var a = b.anchors().get(Math.min(i, b.anchors().size() - 1));
        return new Vec3(a.x() + 0.5, a.y() + 1.5, a.z() + 0.5);
    }

    static ItemStackHandler kit(int track) {
        ItemStackHandler h = new ItemStackHandler(54);
        int slot = 0;
        for (int left = track; left > 0; left -= 64) h.setStackInSlot(slot++, new ItemStack(AllBlocks.TRACK.asItem(), Math.min(64, left)));
        h.setStackInSlot(slot++, new ItemStack(Items.DIAMOND_PICKAXE));
        h.setStackInSlot(slot++, new ItemStack(Items.DIAMOND_SHOVEL));
        h.setStackInSlot(slot, new ItemStack(Items.DIAMOND_AXE));
        return h;
    }

    static int count(ItemStackHandler h, net.minecraft.world.item.Item item) {
        int c = 0;
        for (int i = 0; i < h.getSlots(); i++) if (h.getStackInSlot(i).is(item)) c += h.getStackInSlot(i).getCount();
        return c;
    }
}
