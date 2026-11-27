package com.gonkhub.track_planner.layer;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.server.level.ServerLevel;

/**
 * The Track Layer on a regular Create train: the block is part of the carriage contraption, and Create calls
 * this every tick while the train moves. Every 10 ticks it builds from the carriage's own storage. A Create
 * train stops at the end of its track by itself, so it needs no brakes from here.
 */
public class TrackLayerMovement implements MovementBehaviour {
    @Override
    public void tick(MovementContext ctx) {
        if (ctx.world.isClientSide || ctx.position == null || !(ctx.world instanceof ServerLevel level)) return;
        int t = ctx.data.getInt("track_planner_t") + 1;
        ctx.data.putInt("track_planner_t", t);
        if (t % 10 != 0) return;
        if (!(ctx.contraption.entity instanceof CarriageContraptionEntity cce)) return;   // trains only
        Carriage carriage = cce.getCarriage();
        if (carriage == null || carriage.train == null) return;
        TrackLayer.step(level, ctx.position, carriage.train.owner, ctx.contraption.getStorage().getAllItems(), carriage.train.id);
    }
}
