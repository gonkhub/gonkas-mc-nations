package com.gonkhub.track_planner;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: every blueprint in the world, as pieces to draw. The handler only stores the tag
 * (this class is loaded on dedicated servers too); the client renderer reads it from {@link #latest}.
 */
public record BlueprintsPayload(CompoundTag data) implements CustomPacketPayload {
    public static final Type<BlueprintsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TrackPlanner.MOD_ID, "blueprints"));
    public static final StreamCodec<ByteBuf, BlueprintsPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, BlueprintsPayload::data, BlueprintsPayload::new);

    /** Last set received, and a counter the renderer uses to notice a new one. Client side only. */
    public static volatile CompoundTag latest = new CompoundTag();
    public static volatile int version;

    public static void receive(BlueprintsPayload p) {
        latest = p.data();
        version++;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
