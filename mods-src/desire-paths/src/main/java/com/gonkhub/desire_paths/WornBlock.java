// Desire Paths for NeoForge - port of Desire Paths by Matjojo (https://github.com/matjojo/desire-paths)
// Copyright (C) 2019 Matjojo; NeoForge port (C) 2026 gonkhub
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY.
// See the LICENSE file or <https://www.gnu.org/licenses/>.

package com.gonkhub.desire_paths;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import org.jetbrains.annotations.Nullable;

/** A partly worn block. Replaces the original's Trampleable. */
public class WornBlock extends SnowyDirtBlock {
    public static final int MAX_STAGE = 5;
    /** Same property name as the original mod, so its blockstate files carry over. */
    public static final IntegerProperty STAGE = IntegerProperty.create("desiretramples", 0, MAX_STAGE);

    private final Supplier<Block> base;
    private final Supplier<Block> result;
    private final Supplier<Block> tilled;
    private final boolean pathStage;
    private final MapCodec<WornBlock> codec = MapCodec.unit(this);

    /**
     * @param base      the block this wore away from, and grows back into
     * @param result    the block this becomes once fully worn
     * @param tilled    what a hoe turns it into
     * @param pathStage true for the slow coarse dirt -> dirt path step
     */
    public WornBlock(Properties props, Supplier<Block> base, Supplier<Block> result,
                     Supplier<Block> tilled, boolean pathStage) {
        super(props);
        this.base = base;
        this.result = result;
        this.tilled = tilled;
        this.pathStage = pathStage;
        registerDefaultState(stateDefinition.any().setValue(SNOWY, false).setValue(STAGE, 0));
    }

    @Override
    public MapCodec<WornBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(STAGE);
    }

    public boolean isPathStage() {
        return pathStage;
    }

    /** The state after one more step of wear, or null if it cannot wear here right now. */
    @Nullable
    public BlockState worn(BlockState state, LevelReader level, BlockPos pos) {
        int stage = state.getValue(STAGE);
        if (stage < MAX_STAGE) {
            return state.setValue(STAGE, stage + 1);
        }
        BlockState next = carrySnow(state, result.get().defaultBlockState());
        // A dirt path cannot sit under a solid block; wait rather than place one that pops.
        return next.canSurvive(level, pos) ? next : null;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextDouble() >= DPConfig.REGROW_CHANCE.get()) {
            return;
        }
        int stage = state.getValue(STAGE);
        level.setBlockAndUpdate(pos, stage > 0
                ? state.setValue(STAGE, stage - 1)
                : carrySnow(state, base.get().defaultBlockState()));
    }

    @Override
    public @Nullable BlockState getToolModifiedState(BlockState state, UseOnContext context,
                                                     ItemAbility ability, boolean simulate) {
        if (ability == ItemAbilities.SHOVEL_FLATTEN) {
            return Blocks.DIRT_PATH.defaultBlockState(); // ShovelItem checks for air above
        }
        if (ability == ItemAbilities.HOE_TILL) {
            // HoeItem leaves the air-above check to the block for modded blocks.
            return context.getLevel().getBlockState(context.getClickedPos().above()).isAir()
                    ? tilled.get().defaultBlockState() : null;
        }
        return super.getToolModifiedState(state, context, ability, simulate);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(base.get());
    }

    static BlockState carrySnow(BlockState from, BlockState to) {
        return from.hasProperty(SNOWY) && to.hasProperty(SNOWY) ? to.setValue(SNOWY, from.getValue(SNOWY)) : to;
    }
}
