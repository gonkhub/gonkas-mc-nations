package com.gonkhub.track_planner;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: a Rail Planner key was pressed. Changes that setting on the planner in hand. */
public record PlannerKeyRequest(int key) implements CustomPacketPayload {
    public static final int TOGGLE_BUILD = 0, CYCLE_PARALLEL = 1;
    public static final Type<PlannerKeyRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TrackPlanner.MOD_ID, "planner_key"));
    public static final StreamCodec<ByteBuf, PlannerKeyRequest> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(PlannerKeyRequest::new, PlannerKeyRequest::key);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(PlannerKeyRequest r, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        ItemStack stack = RailPlannerItem.held(p);
        if (stack == null) return;
        if (r.key == TOGGLE_BUILD) RailPlannerItem.toggleBuilding(p, stack);
        else if (r.key == CYCLE_PARALLEL) RailPlannerItem.cycleParallel(p, stack);
    }
}
