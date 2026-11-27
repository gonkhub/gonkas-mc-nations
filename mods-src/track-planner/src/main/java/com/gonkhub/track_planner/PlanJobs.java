package com.gonkhub.track_planner;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Plans run on one "Track Planner" thread, never on the server thread, so the server keeps ticking for
 * everyone while a line is planned. That thread does all of it: the corridor, asking the world generator
 * for the corridor's terrain (and waiting for it), the route search and the height profile. The server
 * thread only takes the finished plan, saves it as a blueprint and sends it to players. Jobs run one at a
 * time, in order; a player gets a progress line every two seconds.
 */
@EventBusSubscriber(modid = TrackPlanner.MOD_ID)
public final class PlanJobs {

    /** Where a line starts (and the headings it may leave on, bit mask over Moves.DIR; -1 any) and ends. */
    public record Ends(BlockPos start, int startDirs, BlockPos goal) {}

    static final class Job {
        final String owner;
        final UUID requester;
        final long queued = System.currentTimeMillis();
        volatile Planner planner;
        volatile String stage = "waiting for the planner";

        Job(String owner, UUID requester) { this.owner = owner; this.requester = requester; }

        String progress() {
            Planner p = planner;
            String s = p == null ? stage : p.stage();
            if (p != null && "loading terrain".equals(s)) {
                Terrain t = p.terrain();
                if (t.prefetchTotal > 0) s += String.format(" %d%% (%d of %d chunks)", 100 * t.prefetchDone / t.prefetchTotal, t.prefetchDone, t.prefetchTotal);
            }
            return s;
        }
    }

    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Track Planner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    static final Map<Job, Boolean> running = new ConcurrentHashMap<>();

    public static int queued() { return running.size(); }

    /** True when this player already has a plan waiting or running. */
    public static boolean busy(UUID player) {
        return running.keySet().stream().anyMatch(j -> player.equals(j.requester));
    }

    /**
     * Plans on the planner thread and saves the result as a blueprint on the server thread.
     * ends is evaluated on the planner thread too, so it may load chunks (finding a clicked point's height).
     * report receives messages on the server thread. The future completes on the server thread, with null on failure.
     */
    public static CompletableFuture<Blueprint> submit(ServerLevel level, Supplier<Ends> ends, String owner, UUID requester,
                                                      Consumer<Component> report) {
        MinecraftServer server = level.getServer();
        Job job = new Job(owner, requester);
        running.put(job, true);
        CompletableFuture<Blueprint> done = new CompletableFuture<>();
        WORKER.execute(() -> {
            long t0 = System.nanoTime();
            try {
                job.stage = "finding the ends";
                Ends e = ends.get();
                Planner planner = new Planner(level, Terrain.Mode.PREFETCH, new Planner.Weights());
                job.planner = planner;
                Planner.Plan plan = planner.plan(e.start(), e.startDirs(), e.goal());
                double seconds = (System.nanoTime() - t0) / 1e9;
                server.execute(() -> {
                    running.remove(job);
                    done.complete(TrackPlanner.saveBlueprint(level, plan, e.start(), e.goal(), owner, seconds, report));
                });
            } catch (Throwable ex) {
                LogUtils.getLogger().error("Track Planner: planning failed", ex);
                server.execute(() -> {
                    running.remove(job);
                    report.accept(Component.literal("Track Planner: planning failed (" + ex + ")").withStyle(ChatFormatting.RED));
                    done.complete(null);
                });
            }
        });
        return done;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (running.isEmpty() || e.getServer().getTickCount() % 40 != 0) return;
        for (Job job : running.keySet()) {
            if (job.requester == null) continue;
            ServerPlayer p = e.getServer().getPlayerList().getPlayer(job.requester);
            if (p != null)
                p.displayClientMessage(Component.literal("Track Planner: " + job.progress() + String.format(", %.0f s", (System.currentTimeMillis() - job.queued) / 1000.0))
                        .withStyle(ChatFormatting.GRAY), true);
        }
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        // the planner thread is a daemon; pending plans are simply dropped with the server
        running.clear();
    }

    private PlanJobs() {}
}
