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
    static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final DeferredItem<RailPlannerItem> RAIL_PLANNER =
            ITEMS.register("rail_planner", () -> new RailPlannerItem(new Item.Properties().stacksTo(1)));
    /** Where the line being planned starts; on the Rail Planner between the two clicks. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> PLAN_START =
            COMPONENTS.registerComponentType("plan_start", b -> b.persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC));

    public TrackPlanner(IEventBus modBus) {
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(TrackPlanner::payloads);
        modBus.addListener(TrackPlanner::creativeTab);
        NeoForge.EVENT_BUS.addListener(TrackPlanner::commands);
        NeoForge.EVENT_BUS.addListener(TrackPlanner::login);
    }

    static void payloads(RegisterPayloadHandlersEvent e) {
        e.registrar("1").playToClient(BlueprintsPayload.TYPE, BlueprintsPayload.STREAM_CODEC, (p, ctx) -> BlueprintsPayload.receive(p));
    }

    static void creativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(RAIL_PLANNER.get());
    }

    static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Blueprints.get(p.server).sync(p.server, p);
    }

    /** Plans start to goal and saves it as a blueprint. Reports to the given sink. Returns null on failure. */
    public static Blueprint planBlueprint(ServerLevel level, BlockPos start, BlockPos goal, String owner, Consumer<Component> report) {
        Planner.Weights w = new Planner.Weights();
        Planner.Plan plan = new Planner(level, Terrain.Mode.PREFETCH, w).plan(start, -1, goal);
        var st = plan.stats();
        if (st.failure != null) {
            report.accept(Component.literal("Track Planner: " + st.failure).withStyle(ChatFormatting.RED));
            return null;
        }
        Blueprints all = Blueprints.get(level.getServer());
        Blueprint b = Blueprint.of(all.nextId(), owner, plan, start);
        all.add(level.getServer(), b);
        Planner.Anchor end = plan.anchors().get(plan.anchors().size() - 1);
        report.accept(Component.literal(String.format(
                "Blueprint #%d: %.0f blocks of track, about %d track items, ends %d blocks from the target. Planned in %.1f s. /trackplan build %d to build it.",
                b.id(), b.length(), b.trackItems(), Math.max(Math.abs(end.x() - goal.getX()), Math.abs(end.z() - goal.getZ())),
                (st.corridorMs + st.prefetchMs + st.routeMs + st.profileMs) / 1000.0, b.id())).withStyle(ChatFormatting.AQUA));
        return b;
    }

    /** Builds a blueprint (creative-style for now) and removes it. */
    public static Builder.Result buildBlueprint(ServerLevel level, Blueprint b) {
        Builder.Result r = Builder.build(level, b.plan(), new Planner.Weights());
        Blueprints.get(level.getServer()).remove(level.getServer(), b.id());
        return r;
    }

    static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("trackplan").requires(s -> s.hasPermission(2))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(c -> {
                                    planBlueprint(c.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(c, "from"),
                                            BlockPosArgument.getBlockPos(c, "to"), c.getSource().getTextName(), m -> c.getSource().sendSuccess(() -> m, true));
                                    return 1;
                                })))
                .then(Commands.literal("blueprints").executes(TrackPlanner::list))
                .then(Commands.literal("build")
                        .then(Commands.literal("last").executes(c -> build(c, -1)))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> build(c, IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("remove")
                        .then(Commands.literal("all").executes(c -> {
                            Blueprints.get(c.getSource().getServer()).clear(c.getSource().getServer());
                            c.getSource().sendSuccess(() -> Component.literal("All blueprints removed."), true);
                            return 1;
                        }))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            int id = IntegerArgumentType.getInteger(c, "id");
                            boolean ok = Blueprints.get(c.getSource().getServer()).remove(c.getSource().getServer(), id);
                            c.getSource().sendSuccess(() -> Component.literal(ok ? "Blueprint #" + id + " removed." : "No blueprint #" + id + "."), true);
                            return ok ? 1 : 0;
                        }))));
    }

    static int list(CommandContext<CommandSourceStack> c) {
        var all = Blueprints.get(c.getSource().getServer()).all();
        if (all.isEmpty()) c.getSource().sendSuccess(() -> Component.literal("No blueprints."), false);
        for (Blueprint b : all)
            c.getSource().sendSuccess(() -> Component.literal(String.format("#%d by %s: %s to %s, %.0f blocks, about %d track items",
                    b.id(), b.owner(), b.start().toShortString(), b.goal().toShortString(), b.length(), b.trackItems())), false);
        return all.size();
    }

    static int build(CommandContext<CommandSourceStack> c, int id) throws CommandSyntaxException {
        Blueprints all = Blueprints.get(c.getSource().getServer());
        Blueprint b = id < 0 ? all.last() : all.byId(id);
        if (b == null) {
            c.getSource().sendFailure(Component.literal("No such blueprint."));
            return 0;
        }
        Builder.Result r = buildBlueprint(c.getSource().getLevel(), b);
        c.getSource().sendSuccess(() -> Component.literal(String.format("Built blueprint #%d: %d placements, %d refused by Create, %d track items.",
                b.id(), r.placements, r.refused, r.trackItems)), true);
        r.refusals.stream().limit(5).forEach(s -> c.getSource().sendSystemMessage(Component.literal("  refused: " + s)));
        return 1;
    }
}
