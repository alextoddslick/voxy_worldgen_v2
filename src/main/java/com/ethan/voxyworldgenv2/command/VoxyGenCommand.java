package com.ethan.voxyworldgenv2.command;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.network.LodSendQueue;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side operator commands.
 *
 * <p>Before this existed the only way to change settings was the ModMenu screen, which is
 * client-only — on a dedicated server that meant editing {@code config/voxyworldgenv2.json} and
 * restarting. That is a poor position to be in when {@code generationRadius} is the knob that
 * controls sustained network egress.
 *
 * <p>Each subtree is built by its own method rather than one deep inline {@code .then()} chain:
 * long chains misnest silently on a stray parenthesis and still compile.
 */
public final class VoxyGenCommand {
    private static final int PERMISSION_OP = 2;

    private VoxyGenCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The root is visible to anyone who could use ANY subtree, so refresh can carry a
        // configurable level of its own. Every pre-existing subtree keeps op level 2 explicitly.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voxygen")
            .requires(src -> src.hasPermission(
                Math.min(PERMISSION_OP, Math.max(0, Config.DATA.refreshPermissionLevel))));

        root.then(op(buildStatus()));
        root.then(op(buildRadius()));
        root.then(op(buildTasks()));
        root.then(op(buildQueue()));
        root.then(op(buildEnabled()));
        root.then(op(buildRateLimit()));
        root.then(op(buildLogInterval()));
        root.then(op(buildSettings()));
        root.then(op(buildHeadless()));
        root.then(op(buildLog()));
        root.then(op(buildTraffic()));
        root.then(op(buildReload()));
        root.then(buildRefresh());
        root.executes(VoxyGenCommand::status);

        dispatcher.register(root);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> op(ArgumentBuilder<CommandSourceStack, ?> node) {
        return node.requires(src -> src.hasPermission(PERMISSION_OP));
    }

    // ---- subtrees -------------------------------------------------------------------------

    private static ArgumentBuilder<CommandSourceStack, ?> buildStatus() {
        return Commands.literal("status").executes(VoxyGenCommand::status);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildRadius() {
        return Commands.literal("radius")
            .executes(ctx -> report(ctx, "generationRadius", Config.DATA.generationRadius))
            .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 512))
                .executes(VoxyGenCommand::setRadius));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildTasks() {
        return Commands.literal("tasks")
            .executes(ctx -> report(ctx, "maxActiveTasks", Config.DATA.maxActiveTasks))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 128))
                .executes(VoxyGenCommand::setTasks));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildQueue() {
        return Commands.literal("queue")
            .executes(ctx -> report(ctx, "maxQueueSize", Config.DATA.maxQueueSize))
            .then(Commands.argument("size", IntegerArgumentType.integer(100, 1_000_000))
                .executes(VoxyGenCommand::setQueue));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildEnabled() {
        return Commands.literal("enabled")
            .executes(ctx -> report(ctx, "enabled", Config.DATA.enabled ? 1 : 0))
            .then(Commands.argument("value", BoolArgumentType.bool())
                .executes(VoxyGenCommand::setEnabled));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildRateLimit() {
        return Commands.literal("ratelimit")
            .executes(VoxyGenCommand::showRateLimit)
            .then(Commands.literal("off").executes(ctx -> applyRateLimit(ctx, 0.0)))
            .then(Commands.argument("mbps", DoubleArgumentType.doubleArg(0.0, 1000.0))
                .executes(ctx -> applyRateLimit(ctx, DoubleArgumentType.getDouble(ctx, "mbps"))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildLogInterval() {
        return Commands.literal("loginterval")
            .executes(ctx -> report(ctx, "logProgressIntervalSeconds", Config.DATA.logProgressIntervalSeconds))
            .then(Commands.literal("off").executes(ctx -> applyLogInterval(ctx, 0)))
            .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
                .executes(ctx -> applyLogInterval(ctx, IntegerArgumentType.getInteger(ctx, "seconds"))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildSettings() {
        return Commands.literal("settings").executes(VoxyGenCommand::settings);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildHeadless() {
        return Commands.literal("headless")
            .executes(VoxyGenCommand::showHeadless)
            .then(Commands.argument("value", BoolArgumentType.bool())
                .executes(VoxyGenCommand::setHeadless));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildLog() {
        return Commands.literal("log").executes(VoxyGenCommand::toggleLog);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildTraffic() {
        return Commands.literal("traffic").executes(VoxyGenCommand::traffic);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildReload() {
        return Commands.literal("reload").executes(VoxyGenCommand::reload);
    }

    /** The re-send sweep is capped even for "all": collectCompletedInRange walks the distance
     *  graph, and an unbounded radius would make that walk arbitrarily expensive on the worker. */
    private static final int REFRESH_ALL_RADIUS = 512;

    private static ArgumentBuilder<CommandSourceStack, ?> buildRefresh() {
        return Commands.literal("refresh")
            .requires(src -> src.hasPermission(Math.max(0, Config.DATA.refreshPermissionLevel)))
            .executes(ctx -> refresh(ctx, Config.DATA.refreshDefaultRadius, false, null, null))
            .then(refreshTarget(Commands.literal("near"), c -> Config.DATA.refreshDefaultRadius, false))
            .then(refreshTarget(Commands.literal("all"), c -> REFRESH_ALL_RADIUS, true))
            .then(refreshTarget(Commands.argument("chunks", IntegerArgumentType.integer(1, 512)),
                c -> IntegerArgumentType.getInteger(c, "chunks"), false));
    }

    /**
     * Attaches the shared [player [dimension]] tail to one radius node.
     *
     * <p>{@code all} is passed explicitly rather than inferred from the radius. Inferring it
     * would make the literal `/voxygen refresh 512` silently mean "forget the whole dimension",
     * which is a different operation that happens to share a number.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> refreshTarget(
            ArgumentBuilder<CommandSourceStack, ?> node,
            java.util.function.Function<CommandContext<CommandSourceStack>, Integer> radius,
            boolean all) {
        return node
            .executes(ctx -> refresh(ctx, radius.apply(ctx), all, null, null))
            .then(Commands.argument("player", EntityArgument.player())
                .requires(src -> src.hasPermission(PERMISSION_OP))
                .executes(ctx -> refresh(ctx, radius.apply(ctx), all,
                    EntityArgument.getPlayer(ctx, "player"), null))
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .executes(ctx -> refresh(ctx, radius.apply(ctx), all,
                        EntityArgument.getPlayer(ctx, "player"),
                        DimensionArgument.getDimension(ctx, "dimension")))));
    }

    // ---- handlers -------------------------------------------------------------------------

    private static int status(CommandContext<CommandSourceStack> ctx) {
        if (!ctx.getSource().hasPermission(PERMISSION_OP)) {
            reply(ctx, "usage: /voxygen refresh <near|chunks|all>");
            return 0;
        }
        var mgr = ChunkGenerationManager.getInstance();
        var stats = mgr.getStats();
        var q = LodSendQueue.getInstance();

        reply(ctx, "§6Voxy World Gen V2§r  (MC 1.21.1)");
        reply(ctx, String.format("  generation: %s   players tracked: %d",
            Config.DATA.enabled ? "§aon§r" : "§coff§r", PlayerTracker.getInstance().getPlayerCount()));
        reply(ctx, String.format("  chunks: §a%d§r done, §e%d§r queued, §c%d§r failed, %d skipped",
            stats.getCompleted(), stats.getQueued(), stats.getFailed(), stats.getSkipped()));
        reply(ctx, String.format("  active tasks: %d / %d   radius: %d chunks",
            mgr.getActiveTaskCount(), Config.DATA.maxActiveTasks, Config.DATA.generationRadius));
        reply(ctx, String.format("  LOD sender: %s   queued %d/%d   sent %d pkt / %s",
            q.isRunning() ? "§aalive§r" : "§cstopped§r",
            q.getQueuedJobs(), q.getMaxQueuedJobs(), q.getPacketsSent(), humanBytes(q.getBytesSent())));

        int dropped = q.getJobsDropped();
        if (dropped > 0) {
            reply(ctx, String.format(
                "  §c%d chunk(s) dropped§r - sender is behind; lower radius or tasks", dropped));
        }
        return 1;
    }

    private static int setRadius(CommandContext<CommandSourceStack> ctx) {
        int v = IntegerArgumentType.getInteger(ctx, "chunks");
        int old = Config.DATA.generationRadius;
        Config.DATA.generationRadius = v;
        persist();
        reply(ctx, String.format("generationRadius %d -> §a%d§r chunks", old, v));
        if (v > 128) {
            reply(ctx, "§e warning:§r a large radius streams a lot of data; watch egress on hosted servers");
        }
        maybeShowBook(ctx);
        return 1;
    }

    private static int setTasks(CommandContext<CommandSourceStack> ctx) {
        int v = IntegerArgumentType.getInteger(ctx, "count");
        int old = Config.DATA.maxActiveTasks;
        Config.DATA.maxActiveTasks = v;
        persist();
        reply(ctx, String.format("maxActiveTasks %d -> §a%d§r", old, v));
        maybeShowBook(ctx);
        return 1;
    }

    private static int setQueue(CommandContext<CommandSourceStack> ctx) {
        int v = IntegerArgumentType.getInteger(ctx, "size");
        int old = Config.DATA.maxQueueSize;
        Config.DATA.maxQueueSize = v;
        persist();
        reply(ctx, String.format("maxQueueSize %d -> §a%d§r", old, v));
        maybeShowBook(ctx);
        return 1;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean v = BoolArgumentType.getBool(ctx, "value");
        Config.DATA.enabled = v;
        persist();
        reply(ctx, v ? "generation §aenabled§r" : "generation §cdisabled§r");
        maybeShowBook(ctx);
        return 1;
    }

    private static int showRateLimit(CommandContext<CommandSourceStack> ctx) {
        double v = Config.DATA.maxMbpsPerPlayer;
        reply(ctx, v <= 0
            ? "per-player LOD limit: §eunlimited§r  (set with /voxygen ratelimit <mbps>)"
            : String.format("per-player LOD limit: §b%.2f Mbps§r (%s/s)", v, humanBytes((long) (v * 125_000))));
        return 1;
    }

    private static int applyRateLimit(CommandContext<CommandSourceStack> ctx, double mbps) {
        double old = Config.DATA.maxMbpsPerPlayer;
        Config.DATA.maxMbpsPerPlayer = mbps;
        persist();
        if (mbps <= 0) {
            reply(ctx, String.format("per-player LOD limit %s -> §eunlimited§r",
                old <= 0 ? "unlimited" : String.format("%.2f Mbps", old)));
        } else {
            reply(ctx, String.format("per-player LOD limit %s -> §a%.2f Mbps§r (%s/s per player)",
                old <= 0 ? "unlimited" : String.format("%.2f Mbps", old), mbps,
                humanBytes((long) (mbps * 125_000))));
        }
        maybeShowBook(ctx);
        return 1;
    }

    private static int traffic(CommandContext<CommandSourceStack> ctx) {
        var q = LodSendQueue.getInstance();
        long bps = q.getCurrentBytesPerSecond();

        reply(ctx, "§6LOD traffic§r");
        long raw = NetworkHandler.RAW_SECTION_BYTES.get();
        long wire = NetworkHandler.WIRE_SECTION_BYTES.get();
        if (wire > 0) {
            reply(ctx, String.format("  on the wire: §b%s§r over %d packets  (was %s raw, §a%.1fx§r smaller)",
                humanBytes(wire), q.getPacketsSent(), humanBytes(raw), raw > 0 ? (double) raw / wire : 0.0));
        } else {
            reply(ctx, String.format("  total sent : §b%s§r over %d packets",
                humanBytes(q.getBytesSent()), q.getPacketsSent()));
        }
        reply(ctx, String.format("  now        : §b%.2f MB/s§r raw before compression  (%.2f Mbps)",
            bps / 1_000_000.0, (bps * 8.0) / 1_000_000.0));
        reply(ctx, String.format("  limit      : %s",
            Config.DATA.maxMbpsPerPlayer <= 0 ? "§eunlimited§r"
                : String.format("§b%.2f Mbps§r per player", Config.DATA.maxMbpsPerPlayer)));
        reply(ctx, String.format("  queue      : %d/%d   dropped: %d   throttled: %.1fs",
            q.getQueuedJobs(), q.getMaxQueuedJobs(), q.getJobsDropped(),
            q.getThrottleWaitMillis() / 1000.0));

        var per = q.getPerPlayerBytes();
        if (per.isEmpty()) {
            reply(ctx, "  no per-player traffic yet");
            return 1;
        }
        reply(ctx, "  per player:");
        var server = ctx.getSource().getServer();
        per.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(10)
            .forEach(e -> {
                var p = server.getPlayerList().getPlayer(e.getKey());
                String name = p != null ? p.getName().getString() : e.getKey().toString().substring(0, 8) + " (gone)";
                reply(ctx, String.format("    %-18s §b%s§r", name, humanBytes(e.getValue())));
            });
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ChunkGenerationManager.getInstance().scheduleConfigReload();
        reply(ctx, "config reload scheduled (re-reads config/voxyworldgenv2.json next tick)");
        return 1;
    }

    /**
     * Clears synced bits so the worker's catch-up loop re-sends them. Deliberately sends nothing
     * itself and never queues ungenerated chunks: "all" forgets the whole dimension but the
     * re-send sweep is still capped at REFRESH_ALL_RADIUS, and the rest arrives as the player
     * travels. The reply states the effective radius so that is never a surprise.
     */
    private static int refresh(CommandContext<CommandSourceStack> ctx, int radius, boolean all,
                               ServerPlayer explicitTarget, ServerLevel explicitDimension) {
        ServerPlayer target = explicitTarget;
        if (target == null) {
            if (!(ctx.getSource().getEntity() instanceof ServerPlayer self)) {
                reply(ctx, "console has no position; name a player: /voxygen refresh all <player>");
                return 0;
            }
            target = self;
        }

        ServerLevel level = explicitDimension != null ? explicitDimension : (ServerLevel) target.level();
        String dim = PlayerTracker.dimensionId(level.dimension());

        var store = PlayerTracker.getInstance().getStore(target.getUUID());
        if (store == null) {
            reply(ctx, "§c" + target.getName().getString() + " is not tracked yet; try again in a moment");
            return 0;
        }

        int forgotten = all
            ? store.forgetAll(dim)
            : store.forgetWithin(dim, target.chunkPosition().x, target.chunkPosition().z, radius);

        PlayerTracker.getInstance().setRefreshRadius(target.getUUID(), dim, radius);

        reply(ctx, String.format("forgot §b%d§r chunk(s) for §b%s§r in §b%s§r; re-sending within §b%d§r chunks",
            forgotten, target.getName().getString(), dim, radius));
        if (all) {
            reply(ctx, "§7 \"all\" forgets the whole dimension; anything beyond "
                + REFRESH_ALL_RADIUS + " chunks arrives as they travel");
        }
        if (forgotten == 0) {
            reply(ctx, "§7 nothing was remembered there, so nothing will be re-sent");
        }
        return 1;
    }

    private static int applyLogInterval(CommandContext<CommandSourceStack> ctx, int seconds) {
        int old = Config.DATA.logProgressIntervalSeconds;
        Config.DATA.logProgressIntervalSeconds = seconds;
        persist();
        reply(ctx, String.format("progress log interval %s -> %s",
            old <= 0 ? "off" : old + "s", seconds <= 0 ? "§eoff§r" : "§a" + seconds + "s§r"));
        maybeShowBook(ctx);
        return 1;
    }

    private static int settings(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            SettingsBook.open(player);
        } else {
            // console/RCON has no screen; print the same information as text
            status(ctx);
        }
        return 1;
    }

    /**
     * In-game sources get the settings book re-opened so the change is visible immediately —
     * unless the player opted out with {@code /voxygen headless on}.
     */
    private static void maybeShowBook(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player && !isHeadless(player)) {
            SettingsBook.open(player);
        }
    }

    static boolean isHeadless(ServerPlayer player) {
        return Config.DATA.headlessPlayers.contains(player.getUUID().toString());
    }

    private static int toggleLog(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            reply(ctx, "the tab HUD is per-player; run this in game");
            return 0;
        }
        boolean on = TabHud.toggle(player);
        reply(ctx, on
            ? "tab HUD \u00a7aon\u00a7r - hold Tab to see live worldgen info (/voxygen log to hide)"
            : "tab HUD \u00a7coff\u00a7r");
        return 1;
    }

    private static int showHeadless(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            reply(ctx, isHeadless(player)
                ? "headless: §aon§r - settings book only opens via /voxygen settings"
                : "headless: §coff§r - settings book auto-opens after changes");
        } else {
            reply(ctx, "headless is a per-player preference; consoles are always headless");
        }
        return 1;
    }

    private static int setHeadless(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            reply(ctx, "headless is a per-player preference; run this in game");
            return 0;
        }
        boolean v = BoolArgumentType.getBool(ctx, "value");
        String id = player.getUUID().toString();
        if (v && !Config.DATA.headlessPlayers.contains(id)) {
            Config.DATA.headlessPlayers.add(id);
        } else if (!v) {
            Config.DATA.headlessPlayers.remove(id);
        }
        Config.save();
        reply(ctx, v
            ? "headless §aon§r - the book won't auto-open; /voxygen settings still shows it"
            : "headless §coff§r - the book auto-opens after setting changes");
        // Deliberately no book popup here: turning headless ON and getting a book in the face
        // would be exactly the annoyance being opted out of. Turning it off shows it again.
        if (!v) {
            SettingsBook.open(player);
        }
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx, String name, int value) {
        reply(ctx, String.format("%s = §b%d§r", name, value));
        return 1;
    }

    // ---- helpers --------------------------------------------------------------------------

    /**
     * Writes the change to disk. The running worker reads {@code Config.DATA} directly, so the new
     * value is already live; the scheduled reload is what makes the throttle and the active scan
     * pick up structural changes such as maxActiveTasks.
     */
    private static void persist() {
        Config.save();
        ChunkGenerationManager.getInstance().scheduleConfigReload();
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
