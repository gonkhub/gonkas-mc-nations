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
 * destination to plan it. The plan is saved as a blueprint and shown to everyone as ghost track. Holding
 * the planner near your blueprint, with building switched on, builds it piece by piece in survival (see
 * SurvivalBuilder). Building is off by default; the toggle key (B) or sneak-right-click in the air switches it.
 * Sneak-right-click in the air with a start set forgets the start instead.
 */
public final class RailPlannerItem extends Item {
    /** The toggle key's current binding, on the client; "the toggle key" elsewhere. */
    static String keyName() { return keyName(false); }

    static String keyName(boolean parallel) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) return "the key";
        var k = parallel ? com.gonkhub.track_planner.client.PlannerKeys.CYCLE_PARALLEL : com.gonkhub.track_planner.client.PlannerKeys.TOGGLE_BUILD;
        return k.getTranslatedKeyMessage().getString();
    }

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
        if (PlanJobs.busy(player.getUUID())) {
            player.displayClientMessage(Component.literal("Your last line is still being planned.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(Component.literal("Planning " + start.toShortString() + " to " + at.toShortString() + " in the background...")
                .withStyle(ChatFormatting.GRAY), true);
        BlockPos from = start;
        PlanJobs.submit((ServerLevel) level, () -> new PlanJobs.Ends(LineEnd.at((ServerLevel) level, from), LineEnd.at((ServerLevel) level, at)),
                player.getName().getString(), player.getUUID(), player::sendSystemMessage, parallel(stack), TrackPlanner.SPACING);
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
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) toggleBuilding(player, stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    /** The Rail Planner in a player's main hand, else off hand, else null. */
    public static ItemStack held(Player p) {
        if (p.getMainHandItem().is(TrackPlanner.RAIL_PLANNER.get())) return p.getMainHandItem();
        if (p.getOffhandItem().is(TrackPlanner.RAIL_PLANNER.get())) return p.getOffhandItem();
        return null;
    }

    /** 1: lines get a second line on their right, -1 on their left, 0 single track. */
    public static int parallel(ItemStack stack) {
        Integer v = stack == null ? null : stack.get(TrackPlanner.PARALLEL.get());
        return v == null ? 0 : Integer.signum(v);
    }

    /** Single, double on the right, double on the left, single... */
    public static void cycleParallel(Player player, ItemStack stack) {
        int next = switch (parallel(stack)) { case 0 -> 1; case 1 -> -1; default -> 0; };
        if (next == 0) stack.remove(TrackPlanner.PARALLEL.get()); else stack.set(TrackPlanner.PARALLEL.get(), next);
        player.displayClientMessage(Component.literal(next == 0 ? "Single track: lines are planned on their own."
                : "Double track: every line gets a second one on its " + (next > 0 ? "right" : "left") + ", " + TrackPlanner.SPACING + " blocks apart.")
                .withStyle(next == 0 ? ChatFormatting.GRAY : ChatFormatting.AQUA), true);
    }

    static String parallelName(ItemStack stack) {
        return switch (parallel(stack)) { case 1 -> "double track, second line on the right"; case -1 -> "double track, second line on the left"; default -> "single track"; };
    }

    public static boolean building(ItemStack stack) {
        return stack.is(TrackPlanner.RAIL_PLANNER.get()) && Boolean.TRUE.equals(stack.get(TrackPlanner.BUILDING.get()));
    }

    public static void toggleBuilding(Player player, ItemStack stack) {
        boolean on = !building(stack);
        if (on) stack.set(TrackPlanner.BUILDING.get(), true); else stack.remove(TrackPlanner.BUILDING.get());
        player.displayClientMessage(Component.literal(on ? "Building on: hold the planner near your blueprint to build it."
                : "Building off: the planner only plans.").withStyle(on ? ChatFormatting.AQUA : ChatFormatting.GRAY), true);
    }

    /** Glints while building is on. */
    @Override
    public boolean isFoil(ItemStack stack) { return building(stack) || super.isFoil(stack); }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        BlockPos start = stack.get(TrackPlanner.PLAN_START.get());
        lines.add(Component.literal(start == null ? "Right-click where the line starts" : "Starts at " + start.toShortString() + "; right-click the destination")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(start != null ? "Sneak-right-click the air to clear the start" : "Sneak-right-click the air or press "
                + keyName() + " to switch building").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(building(stack) ? Component.literal("Building ON: builds your blueprint within 8 blocks,").withStyle(ChatFormatting.AQUA)
                : Component.literal("Building OFF: only plans").withStyle(ChatFormatting.GRAY));
        if (building(stack))
            lines.add(Component.literal("with track and tools from your inventory").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Planning " + parallelName(stack) + " (" + keyName(true) + " to change)")
                .withStyle(parallel(stack) != 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));
    }
}
