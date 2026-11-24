package com.gonkhub.desire_paths.gametest;

import com.gonkhub.desire_paths.DPBlocks;
import com.gonkhub.desire_paths.DPConfig;
import com.gonkhub.desire_paths.DesirePaths;
import com.gonkhub.desire_paths.Wear;
import com.gonkhub.desire_paths.WornBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DesirePaths.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DesirePaths.MOD_ID)
public final class DPGameTests {
    private static final String EMPTY = "empty";
    private static final double ALWAYS = 1000.0;

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        event.register(DPGameTests.class);
    }

    private static BlockPos ground(GameTestHelper h, Block block) {
        BlockPos pos = new BlockPos(3, 1, 3);
        h.setBlock(pos, block);
        return pos;
    }

    private static void wear(GameTestHelper h, BlockPos pos, int times) {
        for (int i = 0; i < times; i++) {
            Wear.wearAt(h.getLevel(), h.absolutePos(pos), ALWAYS);
        }
    }

    /** grass -> 6 trampled stages -> dirt -> 6 -> coarse dirt -> 6 -> dirt path, 7 steps each. */
    @GameTest(template = EMPTY)
    public static void fullChain(GameTestHelper h) {
        BlockPos pos = ground(h, Blocks.GRASS_BLOCK);
        wear(h, pos, 1);
        h.assertBlockPresent(DPBlocks.GRASS_DIRT_INTER.get(), pos);
        h.assertBlockProperty(pos, WornBlock.STAGE, 0);
        wear(h, pos, 5);
        h.assertBlockProperty(pos, WornBlock.STAGE, 5);
        wear(h, pos, 1);
        h.assertBlockPresent(Blocks.DIRT, pos);
        wear(h, pos, 7);
        h.assertBlockPresent(Blocks.COARSE_DIRT, pos);
        wear(h, pos, 6);
        h.assertBlockPresent(DPBlocks.COARSE_PATH_INTER.get(), pos);
        wear(h, pos, 1);
        h.assertBlockPresent(Blocks.DIRT_PATH, pos);
        wear(h, pos, 3);
        h.assertBlockPresent(Blocks.DIRT_PATH, pos); // end of the line
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void podzolAndMyceliumWearToDirt(GameTestHelper h) {
        BlockPos a = new BlockPos(1, 1, 1), b = new BlockPos(3, 1, 1);
        h.setBlock(a, Blocks.PODZOL);
        h.setBlock(b, Blocks.MYCELIUM);
        wear(h, a, 1);
        wear(h, b, 1);
        h.assertBlockPresent(DPBlocks.PODZOL_DIRT_INTER.get(), a);
        h.assertBlockPresent(DPBlocks.MYCELIUM_DIRT_INTER.get(), b);
        wear(h, a, 6);
        wear(h, b, 6);
        h.assertBlockPresent(Blocks.DIRT, a);
        h.assertBlockPresent(Blocks.DIRT, b);
        h.succeed();
    }

    /** A dirt path can't exist under a solid block, so the last step waits instead. */
    @GameTest(template = EMPTY)
    public static void noPathUnderSolidBlock(GameTestHelper h) {
        BlockPos pos = ground(h, Blocks.COARSE_DIRT);
        h.setBlock(pos.above(), Blocks.STONE);
        wear(h, pos, 10);
        h.assertBlockPresent(DPBlocks.COARSE_PATH_INTER.get(), pos);
        h.assertBlockProperty(pos, WornBlock.STAGE, WornBlock.MAX_STAGE);
        h.succeed();
    }

    /** The chance really is a chance: at 0 nothing ever wears. */
    @GameTest(template = EMPTY)
    public static void zeroChanceNeverWears(GameTestHelper h) {
        BlockPos pos = ground(h, Blocks.GRASS_BLOCK);
        for (int i = 0; i < 500; i++) {
            Wear.wearAt(h.getLevel(), h.absolutePos(pos), 0.0);
        }
        h.assertBlockPresent(Blocks.GRASS_BLOCK, pos);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void regrowsOneStageAtATime(GameTestHelper h) {
        double saved = DPConfig.REGROW_CHANCE.get();
        DPConfig.REGROW_CHANCE.set(1.0);
        try {
            BlockPos pos = ground(h, Blocks.GRASS_BLOCK);
            wear(h, pos, 3); // stage 2
            BlockPos abs = h.absolutePos(pos);
            randomTick(h, abs);
            h.assertBlockProperty(pos, WornBlock.STAGE, 1);
            randomTick(h, abs);
            randomTick(h, abs);
            h.assertBlockPresent(Blocks.GRASS_BLOCK, pos);
        } finally {
            DPConfig.REGROW_CHANCE.set(saved);
        }
        h.succeed();
    }

    private static void randomTick(GameTestHelper h, BlockPos abs) {
        BlockState state = h.getLevel().getBlockState(abs);
        state.randomTick(h.getLevel(), abs, h.getLevel().random);
    }

    /** The pack's hard requirement: nothing in the chain changes walking speed. */
    @GameTest(template = EMPTY)
    public static void noSpeedChange(GameTestHelper h) {
        Block[] chain = {DPBlocks.GRASS_DIRT_INTER.get(), DPBlocks.PODZOL_DIRT_INTER.get(),
                DPBlocks.MYCELIUM_DIRT_INTER.get(), DPBlocks.DIRT_COARSE_INTER.get(),
                DPBlocks.COARSE_PATH_INTER.get(), Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.DIRT_PATH};
        for (Block b : chain) {
            h.assertTrue(b.getSpeedFactor() == 1.0f, b + " speed factor " + b.getSpeedFactor());
            h.assertTrue(b.getJumpFactor() == 1.0f, b + " jump factor " + b.getJumpFactor());
        }
        h.succeed();
    }

    /** One #minecraft:dirt tag entry replaces the original's plant, sugar cane and bamboo mixins. */
    @GameTest(template = EMPTY)
    public static void plantsGrowOnTrampledGround(GameTestHelper h) {
        BlockPos pos = ground(h, Blocks.GRASS_BLOCK);
        wear(h, pos, 3);
        BlockState here = h.getBlockState(pos);
        h.assertTrue(here.is(BlockTags.DIRT), "trampled grass not in #minecraft:dirt");
        h.assertTrue(Blocks.OAK_SAPLING.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(pos.above())),
                "sapling can't be planted on trampled grass");
        h.assertTrue(Blocks.SHORT_GRASS.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(pos.above())),
                "grass can't grow on trampled grass");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void shovelAndHoe(GameTestHelper h) {
        BlockPos pos = ground(h, Blocks.GRASS_BLOCK);
        wear(h, pos, 2);
        BlockPos abs = h.absolutePos(pos);
        BlockState state = h.getBlockState(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        UseOnContext shovel = new UseOnContext(h.getLevel(), null, InteractionHand.MAIN_HAND,
                new ItemStack(Items.IRON_SHOVEL), hit);
        UseOnContext hoe = new UseOnContext(h.getLevel(), null, InteractionHand.MAIN_HAND,
                new ItemStack(Items.IRON_HOE), hit);
        BlockState flat = state.getToolModifiedState(shovel, ItemAbilities.SHOVEL_FLATTEN, true);
        BlockState till = state.getToolModifiedState(hoe, ItemAbilities.HOE_TILL, true);
        h.assertTrue(flat != null && flat.is(Blocks.DIRT_PATH), "shovel gave " + flat);
        h.assertTrue(till != null && till.is(Blocks.FARMLAND), "hoe gave " + till);
        h.succeed();
    }

    /** A villager walking across a strip of grass wears it; one standing still does not. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void villagerWearsWhereItWalks(GameTestHelper h) {
        double saved = DPConfig.WEAR_PER_BLOCK.get();
        DPConfig.WEAR_PER_BLOCK.set(ALWAYS);
        for (int x = 0; x < 7; x++) {
            h.setBlock(new BlockPos(x, 0, 1), Blocks.GRASS_BLOCK); // walked strip
            h.setBlock(new BlockPos(x, 0, 5), Blocks.GRASS_BLOCK); // standing strip
        }
        Villager walker = h.spawn(EntityType.VILLAGER, new Vec3(0.5, 1, 1.5));
        Villager stander = h.spawn(EntityType.VILLAGER, new Vec3(3.5, 1, 5.5));
        walker.setNoAi(true);
        stander.setNoAi(true);
        h.onEachTick(() -> {
            Vec3 p = walker.position();
            if (p.x < h.absoluteVec(new Vec3(6.5, 0, 0)).x) {
                walker.setPos(p.x + 0.2, p.y, p.z);
            }
            walker.setOnGround(true);
            stander.setOnGround(true);
        });
        h.runAtTickTime(60, () -> {
            DPConfig.WEAR_PER_BLOCK.set(saved);
            int worn = 0;
            for (int x = 1; x < 6; x++) {
                if (!h.getBlockState(new BlockPos(x, 0, 1)).is(Blocks.GRASS_BLOCK)) worn++;
                h.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(x, 0, 5));
            }
            h.assertTrue(worn == 5, "only " + worn + " of 5 walked blocks wore");
            h.succeed();
        });
    }

    /** Zombies aren't in desire_paths:wears_paths. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void untaggedMobsDoNotWear(GameTestHelper h) {
        double saved = DPConfig.WEAR_PER_BLOCK.get();
        DPConfig.WEAR_PER_BLOCK.set(ALWAYS);
        for (int x = 0; x < 7; x++) h.setBlock(new BlockPos(x, 0, 1), Blocks.GRASS_BLOCK);
        var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(0.5, 1, 1.5));
        zombie.setNoAi(true);
        h.onEachTick(() -> {
            Vec3 p = zombie.position();
            if (p.x < h.absoluteVec(new Vec3(6.5, 0, 0)).x) zombie.setPos(p.x + 0.2, p.y, p.z);
            zombie.setOnGround(true);
        });
        h.runAtTickTime(60, () -> {
            DPConfig.WEAR_PER_BLOCK.set(saved);
            for (int x = 0; x < 7; x++) h.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(x, 0, 1));
            h.succeed();
        });
    }

    /** Caravan porters ride horses: the horse wears the path. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void horseWithTaggedRiderWears(GameTestHelper h) {
        double saved = DPConfig.WEAR_PER_BLOCK.get();
        DPConfig.WEAR_PER_BLOCK.set(ALWAYS);
        for (int x = 0; x < 7; x++) h.setBlock(new BlockPos(x, 0, 1), Blocks.GRASS_BLOCK);
        var horse = h.spawn(EntityType.HORSE, new Vec3(0.5, 1, 1.5));
        Villager rider = h.spawn(EntityType.VILLAGER, new Vec3(0.5, 1, 1.5));
        horse.setNoAi(true);
        rider.setNoAi(true);
        rider.startRiding(horse, true);
        h.onEachTick(() -> {
            Vec3 p = horse.position();
            if (p.x < h.absoluteVec(new Vec3(6.5, 0, 0)).x) horse.setPos(p.x + 0.2, p.y, p.z);
            horse.setOnGround(true);
        });
        h.runAtTickTime(60, () -> {
            DPConfig.WEAR_PER_BLOCK.set(saved);
            int worn = 0;
            for (int x = 1; x < 6; x++) if (!h.getBlockState(new BlockPos(x, 0, 1)).is(Blocks.GRASS_BLOCK)) worn++;
            h.assertTrue(rider.isPassenger(), "villager fell off the horse");
            h.assertTrue(worn == 5, "only " + worn + " of 5 ridden-over blocks wore");
            h.succeed();
        });
    }
}
