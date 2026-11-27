package com.gonkhub.track_planner.layer;

import com.lycoris.loconautics.common.sable.SubLevelBlocks;
import com.lycoris.loconautics.compat.create.psi.SablePsiHandlers;
import com.lycoris.loconautics.trainphysics.SableTrain;
import com.lycoris.loconautics.trainphysics.state.SableTrainRegistry;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Track Layer on a Loconautics physics train. There the train is a Sable sub-level: a real structure of
 * blocks that moves, so the layer stays a block with its block entity. Every 10 ticks this finds the layers
 * on each train car (as Loconautics' own roller patch finds rollers), takes their world position from the
 * car's pose, and builds from that car's storage. Loconautics derails a train that runs off the end of its
 * track, so when the next piece can't be built and is close, the train is held on its brakes until it can.
 * Only registered when Loconautics is installed.
 */
public final class LocoTrackLayers {
    /** Trains this class put the brakes on, so it only ever releases its own. */
    static final Set<UUID> holding = new HashSet<>();

    public static void tick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 10 != 0) return;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (SubLevel sl : List.copyOf(container.getAllSubLevels())) {
                if (!(sl instanceof ServerSubLevel car) || car.isRemoved()) continue;
                SableTrain train = SableTrainRegistry.bySubLevel(car.getUniqueId());
                if (train == null || train.isDerailed()) continue;
                List<TrackLayerBlockEntity> layers = SubLevelBlocks.findAll(car, TrackLayerBlockEntity.class);
                if (layers.isEmpty()) continue;
                IItemHandlerModifiable storage = SablePsiHandlers.carriageItems(car, () -> {});
                if (storage == null) continue;
                boolean brake = false, built = false;
                for (TrackLayerBlockEntity layer : layers) {
                    Vec3 at = car.logicalPose().transformPosition(Vec3.atCenterOf(layer.getBlockPos()));
                    TrackLayer.Result r = TrackLayer.step(level, at, train.owner(), storage, train.id());
                    brake |= r.brake();
                    built |= r.built();
                }
                if (brake && holding.add(train.id())) {
                    train.setTargetSpeed(0);
                    train.setBrakeHold(true);
                } else if (!brake && holding.remove(train.id())) {
                    train.setBrakeHold(false);
                }
            }
        }
    }

    private LocoTrackLayers() {}
}
