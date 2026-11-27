package com.gonkhub.track_planner.layer;

import com.gonkhub.track_planner.SurvivalBuilder;
import com.gonkhub.track_planner.Supplies;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a Track Layer on a train does, whichever kind of train it rides: lay the track of the nearest unbuilt
 * piece of the train owner's blueprint within REACH, from the train's storage, with claims checked as the
 * owner. It only lays track: nothing in the way is dug, so the train clears its own way with drills (Create's
 * drills mine as the train moves). It also says when the train should stop because the next piece can't be laid.
 */
public final class TrackLayer {
    /** How far from the layer a piece may be: the next piece is laid when the layer nears the end of the track. */
    public static final double REACH = 12;
    /** Stop the train when a piece that can't be laid is this close. */
    public static final double BRAKE_DISTANCE = REACH;

    public record Result(boolean built, boolean brake, String message) {}

    static final Map<UUID, String> lastSaid = new HashMap<>();

    /** One step for a layer at `at` (world position) on a train owned by `owner`, with that train's storage. */
    public static Result step(ServerLevel level, Vec3 at, UUID owner, IItemHandlerModifiable storage, UUID trainId) {
        if (owner == null) return new Result(false, false, null);
        ServerPlayer actor = ownerStandIn(level, owner);
        Supplies sup = new Supplies.Storage(storage, actor, level, BlockPos.containing(at));
        SurvivalBuilder.Outcome o = SurvivalBuilder.buildNear(level, at, sup, REACH, false);
        boolean brake = !o.built() && o.message() != null && o.distance() <= BRAKE_DISTANCE;
        Result r = new Result(o.built(), brake, o.message());
        tell(level, at, trainId, r);
        return r;
    }

    /**
     * Acts as the train's owner: claim mods see the owner's id. Creative only so that Create's placement
     * doesn't look for track in its (empty) inventory; the train's storage is charged instead.
     */
    static ServerPlayer ownerStandIn(ServerLevel level, UUID owner) {
        GameProfile profile = level.getServer().getProfileCache() == null ? null
                : level.getServer().getProfileCache().get(owner).orElse(null);
        FakePlayer p = FakePlayerFactory.get(level, profile != null ? profile : new GameProfile(owner, "[Track Layer]"));
        if (!p.isCreative()) p.setGameMode(GameType.CREATIVE);
        return p;
    }

    /** Players near the train hear what it's doing: every new message, and a complaint again every few seconds. */
    static void tell(ServerLevel level, Vec3 at, UUID trainId, Result r) {
        if (r.message() == null) return;
        boolean same = r.message().equals(lastSaid.get(trainId));
        lastSaid.put(trainId, r.message());
        if (!r.built() && same && level.getServer().getTickCount() % 60 != 0) return;
        Component c = Component.literal((r.brake() ? "Track Layer stopping: " : "Track Layer: ") + r.message())
                .withStyle(r.built() ? ChatFormatting.AQUA : ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) if (p.position().distanceTo(at) < 64) p.displayClientMessage(c, true);
    }

    private TrackLayer() {}
}
