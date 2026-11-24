// Desire Paths for NeoForge - port of Desire Paths by Matjojo (https://github.com/matjojo/desire-paths)
// Copyright (C) 2019 Matjojo; NeoForge port (C) 2026 gonkhub
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY.
// See the LICENSE file or <https://www.gnu.org/licenses/>.

package com.gonkhub.desire_paths;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Wears the block under anything that walks. Replaces the original's player-stats mixin.
 *
 * Distance is measured from where we last saw the entity, not from Entity#xo: a server player
 * (and a horse a player is riding) is moved by network packets between ticks, so its xo equals
 * its position by the time an entity tick event fires.
 */
public final class Wear {
    /** Who wears paths. data/desire_paths/tags/entity_type/wears_paths.json; extendable by datapack. */
    public static final TagKey<EntityType<?>> WEARS_PATHS =
            TagKey.create(Registries.ENTITY_TYPE, DesirePaths.id("wears_paths"));

    /** Server thread only. Weak keys, so unloaded entities fall out on their own. */
    private static final Map<Entity, Vec3> LAST_SEEN = new WeakHashMap<>();

    /** Anything that moves further than this in a tick teleported; don't wear a line to it. */
    private static final double TELEPORT = 2.0;

    private Wear() {}

    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity.isPassenger() || !wearsPaths(entity)) {
            return;
        }

        Vec3 now = entity.position();
        Vec3 before = LAST_SEEN.put(entity, now);
        if (before == null || !entity.onGround() || entity.isInWater()) {
            return;
        }
        double dx = now.x - before.x, dz = now.z - before.z;
        double step = Math.sqrt(dx * dx + dz * dz);
        if (step < DPConfig.MIN_STEP.get() || step > TELEPORT) {
            return;
        }

        wearAt(level, entity.getOnPos(), DPConfig.WEAR_PER_BLOCK.get() * Math.min(step, 1.0));
    }

    /**
     * Rolls one step of wear on the block at pos.
     *
     * @param chance chance of a step before the path-stage multiplier
     * @return true if the block changed
     */
    public static boolean wearAt(ServerLevel level, BlockPos pos, double chance) {
        BlockState state = level.getBlockState(pos);
        WornBlock worn = wornBlockFor(state.getBlock());
        if (worn == null) {
            return false;
        }
        if (worn.isPathStage()) {
            chance *= DPConfig.PATH_MULTIPLIER.get();
        }
        if (level.random.nextDouble() >= chance) {
            return false;
        }
        BlockState next = state.getBlock() instanceof WornBlock
                ? worn.worn(state, level, pos)
                : WornBlock.carrySnow(state, worn.defaultBlockState());
        if (next == null) {
            return false;
        }
        level.setBlockAndUpdate(pos, next);
        return true;
    }

    static boolean wearsPaths(Entity entity) {
        if (entity instanceof Player player) {
            return canWear(player) && entity.getType().is(WEARS_PATHS);
        }
        if (entity.getType().is(WEARS_PATHS)) {
            return true;
        }
        if (entity instanceof LivingEntity && entity.isVehicle() && DPConfig.MOUNTS.get()) {
            Entity rider = entity.getFirstPassenger();
            return rider != null && rider.getType().is(WEARS_PATHS)
                    && !(rider instanceof Player player && !canWear(player));
        }
        return false;
    }

    /** As the original: no sneaking, no spectators, and nobody who may not change blocks (adventure). */
    private static boolean canWear(Player player) {
        return !player.isSpectator() && !player.isCrouching() && player.mayBuild();
    }

    /** The worn block a ground block steps into, or the block itself if it is already worn. */
    @Nullable
    private static WornBlock wornBlockFor(Block block) {
        if (block instanceof WornBlock worn) return worn;
        if (block == Blocks.GRASS_BLOCK) return DPBlocks.GRASS_DIRT_INTER.get();
        if (block == Blocks.PODZOL) return DPBlocks.PODZOL_DIRT_INTER.get();
        if (block == Blocks.MYCELIUM) return DPBlocks.MYCELIUM_DIRT_INTER.get();
        if (block == Blocks.DIRT) return DPBlocks.DIRT_COARSE_INTER.get();
        if (block == Blocks.COARSE_DIRT) return DPBlocks.COARSE_PATH_INTER.get();
        return null;
    }
}
