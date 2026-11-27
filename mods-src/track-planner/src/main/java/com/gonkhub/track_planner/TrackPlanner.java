package com.gonkhub.track_planner;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Prototype. Op-only command: /trackplan <from> <to> [prefetch|estimate|load]
 * plans a line from the track block at (or placed at) <from> to <to> and builds it.
 */
@Mod(TrackPlanner.MOD_ID)
public final class TrackPlanner {
    public static final String MOD_ID = "track_planner";

    public TrackPlanner(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(TrackPlanner::commands);
    }

    static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("trackplan").requires(s -> s.hasPermission(2))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(c -> run(c, "prefetch"))
                                .then(Commands.argument("terrain", StringArgumentType.word())
                                        .suggests((c, b) -> b.suggest("prefetch").suggest("estimate").suggest("load").buildFuture())
                                        .executes(c -> run(c, StringArgumentType.getString(c, "terrain")))))));
    }

    static int run(CommandContext<CommandSourceStack> c, String terrain) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = c.getSource().getLevel();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(c, "from"), to = BlockPosArgument.getBlockPos(c, "to");
        Planner.Weights w = new Planner.Weights();
        Planner planner = new Planner(level, terrain.equals("load") ? Terrain.Mode.LOAD_CHUNKS : terrain.equals("estimate") ? Terrain.Mode.ESTIMATE : Terrain.Mode.PREFETCH, w);
        Planner.Plan plan = planner.plan(from, -1, to);
        var st = plan.stats();
        if (st.failure != null) {
            c.getSource().sendFailure(Component.literal("Track Planner: " + st.failure));
            return 0;
        }
        Builder.Result r = Builder.build(level, plan, w);
        c.getSource().sendSuccess(() -> Component.literal(String.format(
                "Track Planner: %d moves, planned in %d ms (corridor %d, route %d, heights %d); built in %d ms; %d track items; %d of %d placements refused by Create",
                plan.moves().size(), st.corridorMs + st.routeMs + st.profileMs, st.corridorMs, st.routeMs, st.profileMs,
                r.earthworksMs + r.trackMs, r.trackItems, r.refused, r.placements)), true);
        r.refusals.stream().limit(5).forEach(s -> c.getSource().sendSystemMessage(Component.literal("  refused: " + s)));
        return 1;
    }
}
