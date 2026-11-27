package com.gonkhub.track_planner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Builds a blueprint a little each tick instead of all at once. It first loads the line's chunks with its
 * own chunk ticket, a few dozen in flight at a time, so the world generator does that work in the
 * background; then builds for at most BUDGET_MS of each tick; then keeps the chunks loaded a little longer
 * so Create registers the new track in its rail network, and lets them go.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID)
public final class BuildJobs {
    public static final TicketType<ChunkPos> TICKET = TicketType.create("track_planner_build", Comparator.comparingLong(ChunkPos::toLong));
    static final int IN_FLIGHT = 64, BUDGET_MS = 15, SETTLE_TICKS = 200;

    static final class Job {
        final ServerLevel level;
        final Blueprint blueprint;
        final Builder.Task task;
        final UUID requester;
        final Consumer<Component> report;
        final CompletableFuture<Builder.Result> done = new CompletableFuture<>();
        final long[] chunks;
        final long started = System.currentTimeMillis();
        int requested, phase, settle;

        Job(ServerLevel level, Blueprint b, UUID requester, Consumer<Component> report) {
            this.level = level;
            this.blueprint = b;
            this.task = new Builder.Task(level, b.plan(), new Planner.Weights());
            this.requester = requester;
            this.report = report;
            this.chunks = task.chunks().toLongArray();
        }

        int loaded() {
            int n = 0;
            for (int i = 0; i < requested; i++) if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunks[i]), ChunkPos.getZ(chunks[i])) != null) n++;
            return n;
        }
    }

    static final List<Job> jobs = new ArrayList<>();

    /** What the running builds are doing, for diagnostics. */
    public static String state() {
        StringBuilder b = new StringBuilder();
        for (Job j : jobs) b.append(String.format("#%d phase %d, %d/%d chunks requested, settle %d, slow units %s; ",
                j.blueprint.id(), j.phase, j.requested, j.chunks.length, j.settle, j.task.slowUnits()));
        return b.toString();
    }

    public static boolean building(int blueprintId) {
        return jobs.stream().anyMatch(j -> j.blueprint.id() == blueprintId);
    }

    /** Starts building; the future completes on the server thread when the line is built and settled. */
    public static CompletableFuture<Builder.Result> start(ServerLevel level, Blueprint b, UUID requester, Consumer<Component> report) {
        Job job = new Job(level, b, requester, report);
        jobs.add(job);
        return job.done;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (jobs.isEmpty()) return;
        for (Job job : new ArrayList<>(jobs)) {
            var source = job.level.getChunkSource();
            if (job.phase == 0) {
                // load: keep at most IN_FLIGHT chunks waiting on the world generator
                int loaded = job.loaded();
                while (job.requested < job.chunks.length && job.requested - loaded < IN_FLIGHT) {
                    ChunkPos cp = new ChunkPos(job.chunks[job.requested++]);
                    source.addRegionTicket(TICKET, cp, 1, cp);
                }
                if (job.requested == job.chunks.length && loaded == job.chunks.length) job.phase = 1;
                else if (e.getServer().getTickCount() % 40 == 0) progress(e, job, String.format("loading chunks %d of %d", loaded, job.chunks.length));
            } else if (job.phase == 1) {
                if (job.task.step(System.nanoTime() + BUDGET_MS * 1_000_000L)) job.phase = 2;
                else if (e.getServer().getTickCount() % 40 == 0) progress(e, job, String.format("building %.0f%%", 100 * job.task.progress()));
            } else if (++job.settle >= SETTLE_TICKS) {
                // let Create register the new track before the chunks may unload
                for (long c : job.chunks) { ChunkPos cp = new ChunkPos(c); source.removeRegionTicket(TICKET, cp, 1, cp); }
                jobs.remove(job);
                Builder.Result r = job.task.result();
                job.report.accept(Component.literal(String.format("Built blueprint #%d in %.0f s: %d placements, %d refused by Create, %d track items.",
                        job.blueprint.id(), (System.currentTimeMillis() - job.started) / 1000.0, r.placements, r.refused, r.trackItems)).withStyle(ChatFormatting.AQUA));
                r.refusals.stream().limit(5).forEach(s -> job.report.accept(Component.literal("  refused: " + s)));
                job.done.complete(r);
            }
        }
    }

    static void progress(ServerTickEvent.Post e, Job job, String what) {
        if (job.requester == null) return;
        ServerPlayer p = e.getServer().getPlayerList().getPlayer(job.requester);
        if (p != null) p.displayClientMessage(Component.literal("Track Planner: blueprint #" + job.blueprint.id() + ", " + what).withStyle(ChatFormatting.GRAY), true);
    }

    private BuildJobs() {}
}
