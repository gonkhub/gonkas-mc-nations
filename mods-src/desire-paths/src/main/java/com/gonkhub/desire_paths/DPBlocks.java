// Desire Paths for NeoForge - port of Desire Paths by Matjojo (https://github.com/matjojo/desire-paths)
// Copyright (C) 2019 Matjojo; NeoForge port (C) 2026 gonkhub
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY.
// See the LICENSE file or <https://www.gnu.org/licenses/>.

package com.gonkhub.desire_paths;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The worn stages. Each one has six looks (desiretramples=0..5) between the block it wore
 * away from and the block it turns into:
 *
 *   grass / podzol / mycelium -> *_dirt_inter -> dirt -> dirt_coarse_inter -> coarse dirt
 *   -> coarse_path_inter -> dirt path
 *
 * No block here, and no block the chain ends on, changes movement speed. That is a hard
 * requirement for this pack, not an accident: do not add one.
 */
public final class DPBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DesirePaths.MOD_ID);

    public static final DeferredBlock<WornBlock> GRASS_DIRT_INTER = BLOCKS.register("grass_dirt_inter",
            () -> new WornBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GRASS_BLOCK).randomTicks(),
                    () -> Blocks.GRASS_BLOCK, () -> Blocks.DIRT, () -> Blocks.FARMLAND, false));

    public static final DeferredBlock<WornBlock> PODZOL_DIRT_INTER = BLOCKS.register("podzol_dirt_inter",
            () -> new WornBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.PODZOL).randomTicks(),
                    () -> Blocks.PODZOL, () -> Blocks.DIRT, () -> Blocks.FARMLAND, false));

    public static final DeferredBlock<WornBlock> MYCELIUM_DIRT_INTER = BLOCKS.register("mycelium_dirt_inter",
            () -> new WornBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.MYCELIUM).randomTicks(),
                    () -> Blocks.MYCELIUM, () -> Blocks.DIRT, () -> Blocks.FARMLAND, false));

    public static final DeferredBlock<WornBlock> DIRT_COARSE_INTER = BLOCKS.register("dirt_coarse_inter",
            () -> new WornBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT).randomTicks(),
                    () -> Blocks.DIRT, () -> Blocks.COARSE_DIRT, () -> Blocks.FARMLAND, false));

    /** New in the port: the slow last step, coarse dirt wearing into a vanilla dirt path. */
    public static final DeferredBlock<WornBlock> COARSE_PATH_INTER = BLOCKS.register("coarse_path_inter",
            () -> new WornBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COARSE_DIRT).randomTicks(),
                    () -> Blocks.COARSE_DIRT, () -> Blocks.DIRT_PATH, () -> Blocks.DIRT, true));

    private DPBlocks() {}
}
