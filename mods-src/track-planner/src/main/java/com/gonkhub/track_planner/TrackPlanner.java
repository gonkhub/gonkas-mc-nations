package com.gonkhub.track_planner;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * Prototype. Plan a line with the Rail Planner (or /trackplan from to); it becomes a blueprint every
 * player sees as ghost track. /trackplan build id|last builds it, /trackplan remove id|all drops it.
 */
@Mod(TrackPlanner.MOD_ID)
public final class TrackPlanner {
    public static final String MOD_ID = "track_planner";

    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final DeferredItem<RailPlannerItem> RAIL_PLANNER =
            ITEMS.register("rail_planner", () -> new RailPlannerItem(new Item.Properties().stacksTo(1)));
    public static final net.neoforged.neoforge.registries.DeferredBlock<com.gonkhub.track_planner.layer.TrackLayerBlock> TRACK_LAYER =
            BLOCKS.register("track_layer", () -> new com.gonkhub.track_planner.layer.TrackLayerBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(2.5f)
                            .sound(net.minecraft.world.level.block.SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> TRACK_LAYER_ITEM = ITEMS.registerSimpleBlockItem(TRACK_LAYER);
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<com.gonkhub.track_planner.layer.TrackLayerBlockEntity>> TRACK_LAYER_BE =
            BLOCK_ENTITIES.register("track_layer", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of((pos, state) -> new com.gonkhub.track_planner.layer.TrackLayerBlockEntity(TrackPlanner.TRACK_LAYER_BE.get(), pos, state),
                            TRACK_LAYER.get()).build(null));
    /** Where the line being planned starts; on the Rail Planner between the two clicks. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> PLAN_START =
            COMPONENTS.registerComponentType("plan_start", b -> b.persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC));

    /** Whether holding this Rail Planner builds nearby blueprints; off (absent) by default, so planning doesn't build. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> BUILDING =
            COMPONENTS.registerComponentType("building", b -> b.persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL));

    public TrackPlanner(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(() ->
                com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.register(TRACK_LAYER.get(),
                        new com.gonkhub.track_planner.layer.TrackLayerMovement())));
        // the physics-train side only exists with Loconautics; its classes are never touched otherwise
        if (net.neoforged.fml.ModList.get().isLoaded("loconautics"))
            NeoForge.EVENT_BUS.addListener(com.gonkhub.track_planner.layer.LocoTrackLayers::tick);
        modBus.addListener(TrackPlanner::payloads);
        modBus.addListener(TrackPlanner::creativeTab);
        NeoForge.EVENT_BUS.addListener(TrackPlanner::commands);
        NeoForge.EVENT_BUS.addListener(TrackPlanner::login);
    }

    static void payloads(RegisterPayloadHandlersEvent e) {
        var reg = e.registrar("1");
        reg.playToClient(BlueprintsPayload.TYPE, BlueprintsPayload.STREAM_CODEC, (p, ctx) -> BlueprintsPayload.receive(p));
        reg.playToServer(MapPlanRequest.TYPE, MapPlanRequest.STREAM_CODEC, MapPlanRequest::handle);
        reg.playToServer(ToggleBuildRequest.TYPE, ToggleBuildRequest.STREAM_CODEC, ToggleBuildRequest::handle);
    }

    static void creativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            e.accept(RAIL_PLANNER.get());
            e.accept(TRACK_LAYER_ITEM.get());
        }
    }

    static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Blueprints.get(p.server).sync(p.server, p);
    }

    /** Plans start to goal and saves it as a blueprint. Reports to the given sink. Returns null on failure. */
    public static Blueprint planBlueprint(ServerLevel level, BlockPos start, BlockPos goal, String owner, Consumer<Component> report) {
        return planBlueprint(level, start, -1, goal, owner, report);
    }

    /** As above, leaving start only on the given headings (a bit mask over Moves.DIR; -1 for any). Runs here, on the calling thread. */
    public static Blueprint planBlueprint(ServerLevel level, BlockPos start, int startDirs, BlockPos goal, String owner, Consumer<Component> report) {
        long t0 = System.nanoTime();
        Planner.Plan plan = new Planner(level, Terrain.Mode.PREFETCH, new Planner.Weights()).plan(start, startDirs, goal);
        return saveBlueprint(level, plan, start, goal, owner, null, (System.nanoTime() - t0) / 1e9, report);
    }

    /** Saves a finished plan as a blueprint and reports it. Server thread only. Null when the plan failed. */
    static Blueprint saveBlueprint(ServerLevel level, Planner.Plan plan, BlockPos start, BlockPos goal, String owner, java.util.UUID ownerId,
                                   double seconds, Consumer<Component> report) {
        var st = plan.stats();
        if (st.failure != null) {
            report.accept(Component.literal("Track Planner: " + st.failure).withStyle(ChatFormatting.RED));
            return null;
        }
        Blueprints all = Blueprints.get(level.getServer());
        Blueprint b = Blueprint.of(all.nextId(), owner, ownerId, plan, start);
        all.add(level.getServer(), b);
        Planner.Anchor end = plan.anchors().get(plan.anchors().size() - 1);
        report.accept(Component.literal(String.format(
                "Blueprint #%d: %.0f blocks of track, about %d track items, ends %d blocks from the target. Planned in %.1f s. /trackplan build %d to build it.",
                b.id(), b.length(), b.trackItems(), Math.max(Math.abs(end.x() - goal.getX()), Math.abs(end.z() - goal.getZ())),
                seconds, b.id())).withStyle(ChatFormatting.AQUA));
        return b;
    }

    /** Builds a blueprint (creative-style for now) and removes it. */
    /** Builds a blueprint over many ticks (loading its chunks first) and removes it when done. Server thread. */
    public static java.util.concurrent.CompletableFuture<Builder.Result> buildBlueprintGradually(ServerLevel level, Blueprint b, java.util.UUID requester,
                                                                                               Consumer<Component> report) {
        return BuildJobs.start(level, b, requester, report).thenApply(r -> {
            Blueprints.get(level.getServer()).remove(level.getServer(), b.id());
            return r;
        });
    }

    /** Builds a blueprint all at once, now (tests). */
    public static Builder.Result buildBlueprint(ServerLevel level, Blueprint b) {
        Builder.Result r = Builder.build(level, b.plan(), new Planner.Weights());
        Blueprints.get(level.getServer()).remove(level.getServer(), b.id());
        return r;
    }

    static void commands(RegisterCommandsEvent e) {
        // anyone: list, remove their own. Ops: plan from coordinates, build instantly, remove anyone's or all.
        e.getDispatcher().register(Commands.literal("trackplan")
                .then(Commands.argument("from", BlockPosArgument.blockPos()).requires(s -> s.hasPermission(2))
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(c -> {
                                    BlockPos from = BlockPosArgument.getLoadedBlockPos(c, "from"), to = BlockPosArgument.getBlockPos(c, "to");
                                    var src = c.getSource();
                                    src.sendSuccess(() -> Component.literal("Track Planner: planning " + from.toShortString() + " to " + to.toShortString()
                                            + " in the background...").withStyle(ChatFormatting.GRAY), false);
                                    PlanJobs.submit(src.getLevel(), () -> new PlanJobs.Ends(from, -1, to), src.getTextName(),
                                            src.getPlayer() == null ? null : src.getPlayer().getUUID(), m -> src.sendSuccess(() -> m, true));
                                    return 1;
                                })))
                .then(Commands.literal("blueprints").executes(TrackPlanner::list))
                .then(Commands.literal("build").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("last").executes(c -> build(c, -1)))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> build(c, IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("remove")
                        .then(Commands.literal("all").requires(s -> s.hasPermission(2)).executes(c -> {
                            Blueprints.get(c.getSource().getServer()).clear(c.getSource().getServer());
                            c.getSource().sendSuccess(() -> Component.literal("All blueprints removed."), true);
                            return 1;
                        }))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            int id = IntegerArgumentType.getInteger(c, "id");
                            Blueprints all = Blueprints.get(c.getSource().getServer());
                            Blueprint b = all.byId(id);
                            if (b == null) { c.getSource().sendFailure(Component.literal("No blueprint #" + id + ".")); return 0; }
                            ServerPlayer p = c.getSource().getPlayer();
                            if (p != null && !b.mayManage(p)) {
                                c.getSource().sendFailure(Component.literal("Blueprint #" + id + " is " + b.owner() + "'s."));
                                return 0;
                            }
                            all.remove(c.getSource().getServer(), id);
                            c.getSource().sendSuccess(() -> Component.literal("Blueprint #" + id + " removed."), true);
                            return 1;
                        }))));
    }

    static int list(CommandContext<CommandSourceStack> c) {
        var all = Blueprints.get(c.getSource().getServer()).all();
        if (all.isEmpty()) c.getSource().sendSuccess(() -> Component.literal("No blueprints."), false);
        for (Blueprint b : all)
            c.getSource().sendSuccess(() -> Component.literal(String.format("#%d by %s: %s to %s, %.0f blocks, about %d track items, %d of %d pieces built",
                    b.id(), b.owner(), b.start().toShortString(), b.goal().toShortString(), b.length(), b.trackItems(),
                    b.built().cardinality(), b.moves().size())), false);
        return all.size();
    }

    static int build(CommandContext<CommandSourceStack> c, int id) throws CommandSyntaxException {
        Blueprints all = Blueprints.get(c.getSource().getServer());
        Blueprint b = id < 0 ? all.last() : all.byId(id);
        if (b == null) {
            c.getSource().sendFailure(Component.literal("No such blueprint."));
            return 0;
        }
        if (BuildJobs.building(b.id())) {
            c.getSource().sendFailure(Component.literal("Blueprint #" + b.id() + " is already being built."));
            return 0;
        }
        var src = c.getSource();
        src.sendSuccess(() -> Component.literal("Building blueprint #" + b.id() + " a little each tick...").withStyle(ChatFormatting.GRAY), true);
        buildBlueprintGradually(src.getLevel(), b, src.getPlayer() == null ? null : src.getPlayer().getUUID(), m -> src.sendSuccess(() -> m, true));
        return 1;
    }
}
