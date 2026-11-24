package com.gonkhub.desire_paths.demo;

import com.gonkhub.desire_paths.DesirePaths;
import com.gonkhub.desire_paths.WornBlock;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only (src/demo, never shipped): drives a real client for a screen recording. Builds a lane
 * of grass, podzol and mycelium, then walks it back and forth by pressing the actual movement
 * keys, so wear comes from the mod's normal walking path.
 */
@EventBusSubscriber(modid = DesirePaths.MOD_ID, value = Dist.CLIENT)
public final class DemoDriver {
    private static final int LANE = 16, PASSES = 12, WARMUP = 100, HOLD_END = 80;
    private static int tick = -1, pass = 0, pause = 0;
    private static double startX;

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.screen != null) {
            return;
        }
        if (tick < 0) {
            setup(mc);
        }
        tick++;
        var p = mc.player;
        p.setYRot(-90f);
        p.setYHeadRot(-90f);
        p.setXRot(38f);

        if (tick < WARMUP) {
            p.displayClientMessage(Component.literal("Desire Paths demo - fresh lane: grass, podzol, mycelium"), true);
            return;
        }
        if (pass >= PASSES) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            p.displayClientMessage(Component.literal("Done: " + PASSES + " passes. Grass -> dirt -> coarse dirt -> dirt path"), true);
            if (++pause > HOLD_END) {
                mc.stop();
            }
            return;
        }
        if (pause > 0) {
            pause--;
            return;
        }
        boolean forward = pass % 2 == 0; // W toward +X, S back toward the start
        double x = p.getX();
        boolean arrived = forward ? x >= startX + LANE - 0.5 : x <= startX + 0.5;
        mc.options.keyUp.setDown(forward && !arrived);
        mc.options.keyDown.setDown(!forward && !arrived);
        if (arrived) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            pass++;
            pause = 6;
        }
        BlockState under = mc.level.getBlockState(p.getOnPos());
        String stage = under.hasProperty(WornBlock.STAGE) ? " (stage " + under.getValue(WornBlock.STAGE) + "/5)" : "";
        p.displayClientMessage(Component.literal("Pass " + (pass + 1) + "/" + PASSES + "  -  walking on: "
                + under.getBlock().getName().getString() + stage), true);
    }

    private static void setup(Minecraft mc) {
        tick = 0;
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        mc.options.hideGui = false;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        BlockPos at = mc.player.blockPosition();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            level.setDayTime(6000);
            level.setWeatherParameters(6000, 0, false, false);
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            // Keep vanilla grass spread from repainting the dirt mid-recording.
            server.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()) - 1;
            for (int i = 0; i < LANE; i++) {
                Block b = i < 8 ? Blocks.GRASS_BLOCK : i < 12 ? Blocks.PODZOL : Blocks.MYCELIUM;
                level.setBlockAndUpdate(new BlockPos(at.getX() + i, y, at.getZ()), b.defaultBlockState());
                // stone kerbs so the lane reads on camera
                level.setBlockAndUpdate(new BlockPos(at.getX() + i, y, at.getZ() - 2), Blocks.STONE_BRICKS.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(at.getX() + i, y, at.getZ() + 2), Blocks.STONE_BRICKS.defaultBlockState());
            }
            sp.teleportTo(level, at.getX() + 0.5, y + 1, at.getZ() + 0.5, -90f, 38f);
            sp.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        });
        startX = at.getX() + 0.5 - 0.5;
    }
}
