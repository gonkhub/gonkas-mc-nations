package com.gonkhub.track_planner;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the "toggle building" key was pressed. Flips building on the Rail Planner in hand. */
public record ToggleBuildRequest() implements CustomPacketPayload {
    public static final ToggleBuildRequest INSTANCE = new ToggleBuildRequest();
    public static final Type<ToggleBuildRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TrackPlanner.MOD_ID, "toggle_build"));
    public static final StreamCodec<ByteBuf, ToggleBuildRequest> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ToggleBuildRequest r, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer p)) return;
        ItemStack stack = p.getMainHandItem().is(TrackPlanner.RAIL_PLANNER.get()) ? p.getMainHandItem()
                : p.getOffhandItem().is(TrackPlanner.RAIL_PLANNER.get()) ? p.getOffhandItem() : null;
        if (stack != null) RailPlannerItem.toggleBuilding(p, stack);
    }
}
