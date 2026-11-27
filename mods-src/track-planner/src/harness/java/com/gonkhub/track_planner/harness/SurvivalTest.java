package com.gonkhub.track_planner.harness;

import com.gonkhub.track_planner.Blueprint;
import com.gonkhub.track_planner.Blueprints;
import com.gonkhub.track_planner.Planner;
import com.gonkhub.track_planner.SurvivalBuilder;
import com.gonkhub.track_planner.Terrain;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Survival building with a test player: no track, then track but no tools, then a "claimed" stretch,
 * then fully equipped, walking the line from start to end. Checks the refusals, what it cost, and the result.
 */
final class SurvivalTest {
    static volatile BoundingBox protect;
    static boolean listening;

    static void run(ServerLevel level, BlockPos spawn, String name, int sdx, int sdz, int gdx, int gdz, PrintWriter log) {
        log.println("\n=== survival case " + name);
        if (!listening) {
            listening = true;
            // a stand-in for a land claim mod: refuse breaking and placing inside the box
            NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> { if (protect != null && protect.isInside(e.getPos())) e.setCanceled(true); });
            NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> { if (protect != null && protect.isInside(e.getPos())) e.setCanceled(true); });
        }
        Terrain real = new Terrain(level, Terrain.Mode.LOAD_CHUNKS);
        int sx = spawn.getX() + sdx, sz = spawn.getZ() + sdz, gx = spawn.getX() + gdx, gz = spawn.getZ() + gdz;
        BlockPos start = new BlockPos(sx, real.surface(sx, sz) + 1, sz), goal = new BlockPos(gx, real.surface(gx, gz) + 1, gz);

        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("survival-tester".getBytes()), "SurvivalTester"));
        p.setGameMode(GameType.SURVIVAL);
        Planner.Plan plan = new Planner(level, Terrain.Mode.PREFETCH, new Planner.Weights()).plan(start, -1, goal);
        if (plan.stats().failure != null) { log.println("FAILED to plan: " + plan.stats().failure); return; }
        Blueprints all = Blueprints.get(level.getServer());
        Blueprint b = Blueprint.of(all.nextId(), "SurvivalTester", p.getUUID(), plan, start);
        all.add(level.getServer(), b);
        int n = b.moves().size();
        log.printf("blueprint #%d: %d pieces, %.0f blocks, about %d track items%n", b.id(), n, b.length(), b.trackItems());
        // load the line (a player walking along it would)
        for (var an : b.anchors()) level.getChunk(an.x() >> 4, an.z() >> 4);

        // 1. no track at all
        p.getInventory().clearContent();
        stand(p, b, 0);
        var o = SurvivalBuilder.buildNear(p);
        log.println("no track: built=" + o.built() + ", says \"" + o.message() + "\"");

        // 2. track but no tools: walk the whole line once
        p.getInventory().add(new ItemStack(AllBlocks.TRACK.asItem(), 64));
        p.getInventory().add(new ItemStack(AllBlocks.TRACK.asItem(), 64));
        Set<String> said = new LinkedHashSet<>();
        int builtNoTools = walk(p, b, said);
        log.println("track, no tools: built " + builtNoTools + " of " + n + " pieces; said " + said);

        // 3. a claim over one unbuilt piece
        int target = -1;
        for (int i = 0; i < n; i++) if (!b.isBuilt(i)) { target = i; break; }
        if (target >= 0) {
            var a = b.anchors().get(target);
            protect = new BoundingBox(a.x() - 2, a.y() - 3, a.z() - 2, a.x() + 2, a.y() + 6, a.z() + 2);
            giveKit(p);
            stand(p, b, target);
            o = SurvivalBuilder.buildNear(p);
            log.println("claimed piece " + target + ": built=" + o.built() + ", says \"" + o.message() + "\"");
            protect = null;
        }

        // 4. fully equipped: walk start to end
        p.getInventory().clearContent();
        giveKit(p);
        int trackBefore = count(p, AllBlocks.TRACK.asItem());
        said.clear();
        int built = walk(p, b, said);
        int trackAfter = count(p, AllBlocks.TRACK.asItem());
        int wear = 0, gained = 0;
        StringBuilder loot = new StringBuilder();
        for (ItemStack st : p.getInventory().items) {
            if (st.isEmpty()) continue;
            if (st.isDamageableItem()) wear += st.getDamageValue();
            else if (!st.is(AllBlocks.TRACK.asItem())) { gained += st.getCount(); if (loot.length() < 200) loot.append(st.getCount()).append(' ').append(st.getHoverName().getString()).append(", "); }
        }
        boolean gone = all.byId(b.id()) == null;
        log.printf("equipped: built %d more pieces (%d of %d in all), blueprint finished and removed: %s%n", built, builtNoTools + built, n, gone);
        log.printf("cost: %d track from the inventory (blueprint estimate %d), %d tool durability; got %d items from digging (%s)%n",
                trackBefore - trackAfter, b.trackItems(), wear, gained, loot);
        if (!said.isEmpty()) log.println("said along the way: " + said);

        Harness.inspect(level, b.plan(), real);
        for (var an : b.anchors()) level.setChunkForced(an.x() >> 4, an.z() >> 4, true);
        Vec3 va = Vec3.atBottomCenterOf(b.anchors().get(0).pos()), vb = Vec3.atBottomCenterOf(b.anchors().get(n).pos());
        Harness.later.add(() -> Harness.graphCheck(name, va, vb, b.length()));
        Harness.later.add(() -> { log.println("--- after 20 s of game time: " + name); Harness.inspect(level, b.plan(), real); });
    }

    /** Walks the line, trying to build at every anchor; returns how many pieces got built. */
    static int walk(FakePlayer p, Blueprint b, Set<String> said) {
        int built = 0;
        for (int i = 0; i <= b.moves().size(); i++) {
            stand(p, b, i);
            for (int k = 0; k < 6; k++) {
                var o = SurvivalBuilder.buildNear(p);
                if (o.built()) built++;
                else { if (o.message() != null) said.add(o.message().replaceAll("at -?\\d+, -?\\d+, -?\\d+", "at ...")); break; }
            }
        }
        return built;
    }

    static void stand(FakePlayer p, Blueprint b, int anchor) {
        var a = b.anchors().get(Math.min(anchor, b.anchors().size() - 1));
        p.setPos(a.x() + 0.5, a.y() + 1, a.z() + 0.5);
    }

    static void giveKit(FakePlayer p) {
        for (int i = 0; i < 10; i++) p.getInventory().add(new ItemStack(AllBlocks.TRACK.asItem(), 64));
        p.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        p.getInventory().add(new ItemStack(Items.DIAMOND_SHOVEL));
        p.getInventory().add(new ItemStack(Items.DIAMOND_AXE));
    }

    static int count(FakePlayer p, net.minecraft.world.item.Item item) {
        int c = 0;
        for (ItemStack st : p.getInventory().items) if (st.is(item)) c += st.getCount();
        return c;
    }
}
