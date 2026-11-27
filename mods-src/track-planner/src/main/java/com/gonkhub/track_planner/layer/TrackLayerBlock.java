package com.gonkhub.track_planner.layer;

import com.gonkhub.track_planner.TrackPlanner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The track-laying car's block. Put it on a train, with chests or vaults of track and tools on the same
 * car; as the train runs along one of its owner's blueprints it builds the stretch ahead. It does nothing
 * standing in the world. On Loconautics physics trains it also brakes the train before the line runs out.
 */
public class TrackLayerBlock extends Block implements EntityBlock {
    public TrackLayerBlock(Properties p) { super(p); }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrackLayerBlockEntity(TrackPlanner.TRACK_LAYER_BE.get(), pos, state);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Put it on a train with track and tools in the train's chests").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Builds your blueprint ahead as the train runs along it").withStyle(ChatFormatting.DARK_GRAY));
    }
}
