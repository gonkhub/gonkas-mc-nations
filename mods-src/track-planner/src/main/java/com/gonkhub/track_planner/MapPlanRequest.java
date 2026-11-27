package com.gonkhub.track_planner;

import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client to server: "plan a line between these two points I clicked on the world map". The map only knows
 * x and z; the server finds each end's height, and starts from an existing Create track if one is there.
 */
public record MapPlanRequest(int sx, int sz, int gx, int gz) implements CustomPacketPayload {
    public static final Type<MapPlanRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TrackPlanner.MOD_ID, "map_plan"));
    public static final StreamCodec<ByteBuf, MapPlanRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MapPlanRequest::sx, ByteBufCodecs.VAR_INT, MapPlanRequest::sz,
            ByteBufCodecs.VAR_INT, MapPlanRequest::gx, ByteBufCodecs.VAR_INT, MapPlanRequest::gz, MapPlanRequest::new);

    /** Plans run in the background, but they still use world generation threads: one per player at a time, this far apart. */
    static final long COOLDOWN_MS = 10_000;
    static final int MAX_DISTANCE = 4000;
    static final Map<UUID, Long> lastPlan = new HashMap<>();

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Runs on the server thread. */
    public static void handle(MapPlanRequest r, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) {
            player.sendSystemMessage(Component.literal("Track Planner: plan lines in the Overworld.").withStyle(ChatFormatting.RED));
            return;
        }
        double distance = Math.hypot(r.gx - r.sx, r.gz - r.sz);
        if (distance > MAX_DISTANCE) {
            player.sendSystemMessage(Component.literal(String.format("Track Planner: that's %.0f blocks; the limit is %d.", distance, MAX_DISTANCE))
                    .withStyle(ChatFormatting.RED));
            return;
        }
        long now = System.currentTimeMillis(), last = lastPlan.getOrDefault(player.getUUID(), 0L);
        if (now - last < COOLDOWN_MS) {
            player.sendSystemMessage(Component.literal(String.format("Track Planner: wait %.0f s before planning again.", (COOLDOWN_MS - (now - last)) / 1000.0))
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (PlanJobs.busy(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Track Planner: your last line is still being planned.").withStyle(ChatFormatting.RED));
            return;
        }
        lastPlan.put(player.getUUID(), now);
        player.sendSystemMessage(Component.literal(String.format("Track Planner: planning a %.0f-block line in the background...", distance)).withStyle(ChatFormatting.GRAY));
        // finding the two ends loads their chunks, so it happens on the planner thread with the rest
        PlanJobs.submit(level, () -> {
            return new PlanJobs.Ends(LineEnd.column(level, r.sx, r.sz), LineEnd.column(level, r.gx, r.gz));
        }, player.getName().getString(), player.getUUID(), player::sendSystemMessage, RailPlannerItem.parallel(RailPlannerItem.held(player)), TrackPlanner.SPACING);
    }
}
