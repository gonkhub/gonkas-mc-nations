package com.gonkhub.track_planner.layer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marks a Track Layer inside a Loconautics physics train, where the block keeps being a real block on a
 * moving structure; LocoTrackLayers finds these. (On a Create train the block becomes part of a contraption
 * and TrackLayerMovement does the work instead.) Holds no state of its own.
 */
public class TrackLayerBlockEntity extends BlockEntity {
    public TrackLayerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
}
