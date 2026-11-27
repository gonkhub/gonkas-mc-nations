package com.gonkhub.track_planner;

import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Right-click a block to start a line there (on an existing track, from that track), right-click the
 * destination to plan it. The plan is saved as a blueprint and shown to everyone as ghost track; nothing
 * is built until someone runs /trackplan build. Sneak-right-click in the air forgets the start.
 */
public final class RailPlannerItem extends Item {
    public RailPlannerItem(Properties p) { super(p); }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ItemStack stack = ctx.getItemInHand();
        BlockPos clicked = ctx.getClickedPos();
        boolean onTrack = level.getBlockState(clicked).getBlock() instanceof ITrackBlock;
        BlockPos at = onTrack ? clicked : clicked.above();

        BlockPos start = stack.get(TrackPlanner.PLAN_START.get());
        if (start == null) {
            stack.set(TrackPlanner.PLAN_START.get(), at);
            player.displayClientMessage(Component.literal("Line starts at " + at.toShortString() + ". Now right-click the destination.")
                    .withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.CONSUME;
        }
        stack.remove(TrackPlanner.PLAN_START.get());
        player.displayClientMessage(Component.literal("Planning " + start.toShortString() + " to " + at.toShortString() + "...")
                .withStyle(ChatFormatting.GRAY), true);
        TrackPlanner.planBlueprint((ServerLevel) level, start, at, player.getName().getString(), msg -> player.sendSystemMessage(msg));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.has(TrackPlanner.PLAN_START.get())) {
            if (!level.isClientSide) {
                stack.remove(TrackPlanner.PLAN_START.get());
                player.displayClientMessage(Component.literal("Line start cleared.").withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        BlockPos start = stack.get(TrackPlanner.PLAN_START.get());
        lines.add(Component.literal(start == null ? "Right-click where the line starts" : "Starts at " + start.toShortString() + "; right-click the destination")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Sneak-right-click the air to clear the start").withStyle(ChatFormatting.DARK_GRAY));
    }
}
