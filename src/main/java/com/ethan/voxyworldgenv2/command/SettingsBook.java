package com.ethan.voxyworldgenv2.command;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.network.LodSendQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;

/**
 * On-screen settings UI without any client-side code: a <i>virtual</i> written book.
 *
 * <p>The server briefly tells the client its held slot contains a written book, sends the
 * open-book packet so the book screen appears, then resyncs the real inventory. Nothing changes
 * server-side; the client's copy of the slot is corrected before the player can interact with it.
 * This is the same trick chat libraries like Adventure use for server-driven book UIs.
 *
 * <p>Settings are rendered as click-to-apply options ({@code RUN_COMMAND}). Each setter re-opens
 * the book, so tapping a value applies it and immediately shows the refreshed page.
 */
public final class SettingsBook {

    private SettingsBook() {}

    public static void open(ServerPlayer player) {
        List<Filterable<Component>> pages = List.of(
            Filterable.passThrough(settingsPage(player)),
            Filterable.passThrough(statsPage()),
            Filterable.passThrough(tuningPage()));

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
            Filterable.passThrough("Voxy WorldGen"), "voxyworldgenv2", 0, pages, true));

        // Hotbar slot n lives at index 36 + n in the player's inventory menu.
        int slot = 36 + player.getInventory().selected;
        player.connection.send(new ClientboundContainerSetSlotPacket(
            player.inventoryMenu.containerId, player.inventoryMenu.getStateId(), slot, book));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        // Book screen is open now; put the client's inventory back the way it really is.
        player.inventoryMenu.sendAllDataToRemote();
    }

    // ---- pages ----------------------------------------------------------------------------

    private static Component settingsPage(ServerPlayer player) {
        var c = Config.DATA;
        boolean sp = ChunkGenerationManager.getInstance().isSingleplayer();
        boolean spOn = sp && c.singleplayer != null && c.singleplayer.enableSingleplayerDefaults;
        MutableComponent p = Component.empty();

        p.append(Component.literal("Voxy WorldGen\n").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));

        p.append(name("Generation: "));
        p.append(c.enabled
            ? Component.literal("ON").withStyle(ChatFormatting.DARK_GREEN)
            : Component.literal("OFF").withStyle(ChatFormatting.DARK_RED));
        p.append(space());
        p.append(opt(c.enabled ? "off" : "on",
            "/voxygen enabled " + !c.enabled, "toggle background generation"));
        p.append(nl());

        if (sp) {
            // The unthrottled singleplayer profile: a setting, not a force. When it's on, the
            // radius/tasks/rate buttons below edit the profile, and the shown values are its.
            p.append(name("SP profile: "));
            p.append(spOn
                ? Component.literal("ON").withStyle(ChatFormatting.DARK_GREEN)
                : Component.literal("OFF").withStyle(ChatFormatting.DARK_RED));
            p.append(space());
            p.append(opt(spOn ? "off" : "on",
                "/voxygen singleplayer " + !spOn,
                spOn ? "use the base settings and limits in this world"
                     : "unthrottle this world (no rate cap, wide radius)"));
            p.append(nl());
        }

        p.append(name("Radius: "));
        p.append(value(Config.getGenerationRadius(sp) + " chunks"));
        p.append(nl());
        p.append(optRow("radius", "generation radius", "32", "64", "128", "256"));

        p.append(name("Tasks: "));
        p.append(value(String.valueOf(Config.getMaxActiveTasks(sp))));
        p.append(nl());
        p.append(optRow("tasks", "parallel generation tasks", "10", "20", "40"));

        double rate = Config.getMaxMbpsPerPlayer(sp);
        p.append(name("Rate cap: "));
        p.append(value(rate <= 0 ? "unlimited" : String.format("%.0f Mbps", rate)));
        p.append(nl());
        p.append(opt("2", "/voxygen ratelimit 2", "2 Mbps per player"));
        p.append(opt("10", "/voxygen ratelimit 10", "10 Mbps per player"));
        p.append(opt("50", "/voxygen ratelimit 50", "50 Mbps per player"));
        p.append(opt("off", "/voxygen ratelimit off", "no bandwidth cap (LAN)"));
        p.append(nl());

        int cps = Config.getMaxChunksPerSecond(sp);
        p.append(name("Gen rate: "));
        p.append(value(cps <= 0 ? "unlimited" : cps + " c/s"));
        p.append(nl());
        p.append(opt("50", "/voxygen genrate 50", "gentle: 50 chunks/s, easy on FPS"));
        p.append(opt("200", "/voxygen genrate 200", "200 chunks/s"));
        p.append(opt("off", "/voxygen genrate off", "generate as fast as possible"));
        p.append(nl());

        boolean headless = VoxyGenCommand.isHeadless(player);
        p.append(name("Auto-popup: "));
        p.append(headless
            ? Component.literal("OFF").withStyle(ChatFormatting.DARK_RED)
            : Component.literal("ON").withStyle(ChatFormatting.DARK_GREEN));
        p.append(space());
        p.append(opt(headless ? "on" : "off",
            "/voxygen headless " + !headless,
            headless ? "auto-open this book after changes" : "headless: stop auto-opening this book"));
        p.append(nl());

        p.append(Component.literal("\nstats p2 - tuning p3").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        return p;
    }

    /** Overflow page for the knobs that no longer fit on page 1 (a book page holds 14 lines). */
    private static Component tuningPage() {
        var c = Config.DATA;
        MutableComponent p = Component.empty();

        p.append(Component.literal("Tuning\n").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));

        p.append(name("Log every: "));
        p.append(value(c.logProgressIntervalSeconds <= 0 ? "off" : c.logProgressIntervalSeconds + "s"));
        p.append(nl());
        p.append(opt("5", "/voxygen loginterval 5", "progress log every 5s"));
        p.append(opt("10", "/voxygen loginterval 10", "progress log every 10s"));
        p.append(opt("30", "/voxygen loginterval 30", "progress log every 30s"));
        p.append(opt("off", "/voxygen loginterval off", "no progress logging"));
        p.append(nl());

        boolean sp = ChunkGenerationManager.getInstance().isSingleplayer();
        int dist = Config.getSendDistanceChunks(sp);
        p.append(name("Send dist: "));
        p.append(value(dist <= 0 ? "unlimited" : dist + " chunks"));
        p.append(nl());
        p.append(opt("128", "/voxygen senddistance 128", "stream LODs up to 128 chunks out"));
        p.append(opt("256", "/voxygen senddistance 256", "stream LODs up to 256 chunks out"));
        p.append(opt("512", "/voxygen senddistance 512", "stream LODs up to 512 chunks out"));
        p.append(opt("off", "/voxygen senddistance off", "no distance cap"));
        p.append(nl());

        // HUD stat toggles: what the tab HUD and /voxygen traffic render per player
        p.append(name("HUD stats:"));
        p.append(nl());
        p.append(hudToggle("wire", c.hudShowCompressed, "compressed bytes actually sent"));
        p.append(hudToggle("zip", c.hudShowSavings, "compression ratio and bytes saved"));
        p.append(hudToggle("disk", c.hudShowClientDisk, "each client's Voxy store size"));
        p.append(nl());

        p.append(Component.literal("\nGen rate caps chunks/s to spare FPS; radius and tasks are on page 1.")
            .withStyle(ChatFormatting.DARK_GRAY));
        return p;
    }

    /** One [label on/off] chip; clicking flips the toggle and re-opens the book. */
    private static Component hudToggle(String label, boolean state, String hover) {
        String stat = switch (label) {
            case "wire" -> "compressed";
            case "zip" -> "savings";
            case "disk" -> "clientdisk";
            default -> label;
        };
        return opt(label + " " + (state ? "on" : "off"),
            "/voxygen hud " + stat + " " + !state,
            (state ? "hide " : "show ") + hover);
    }

    private static Component statsPage() {
        var mgr = ChunkGenerationManager.getInstance();
        var st = mgr.getStats();
        var q = LodSendQueue.getInstance();
        MutableComponent p = Component.empty();

        p.append(Component.literal("Live Stats\n").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));

        p.append(line("Players", String.valueOf(PlayerTracker.getInstance().getPlayerCount())));
        p.append(line("Done", String.valueOf(st.getCompleted())));
        p.append(line("Rate", String.format("%.1f c/s", st.getChunksPerSecond())));
        p.append(line("Left", String.valueOf(mgr.getRemainingInRadius())));
        boolean sp = mgr.isSingleplayer();
        p.append(line("Active", mgr.getActiveTaskCount() + " / " + Config.getMaxActiveTasks(sp) + (sp ? " (SP)" : "")));
        p.append(line("Failed", String.valueOf(st.getFailed())));
        p.append(line("Send q", q.getQueuedJobs() + " / " + q.getMaxQueuedJobs()));
        p.append(line("Deferred", String.valueOf(q.getJobsDropped())));
        p.append(line("Sent", human(q.getWireBytesSent()) + " wire"));
        long raw = com.ethan.voxyworldgenv2.network.NetworkHandler.RAW_SECTION_BYTES.get();
        long wire = com.ethan.voxyworldgenv2.network.NetworkHandler.WIRE_SECTION_BYTES.get();
        if (Config.DATA.hudShowSavings && raw > 0 && wire > 0) {
            p.append(line("Zip", String.format("%.1fx (%s saved)", (double) raw / wire, human(raw - wire))));
        }
        p.append(line("Now", String.format("%.2f MB/s wire", q.getCurrentWireBytesPerSecond() / 1_000_000.0)));
        if (mgr.isThrottled()) {
            p.append(Component.literal("TPS throttled!\n").withStyle(ChatFormatting.DARK_RED));
        }

        p.append(nl());
        p.append(opt("refresh", "/voxygen settings", "re-open with fresh numbers"));
        p.append(opt("tab hud", "/voxygen log", "toggle the hold-Tab live HUD"));
        return p;
    }

    // ---- small builders -------------------------------------------------------------------

    private static Component name(String s) {
        return Component.literal(s).withStyle(ChatFormatting.BLACK);
    }

    private static Component value(String s) {
        return Component.literal(s).withStyle(ChatFormatting.DARK_BLUE);
    }

    private static Component line(String k, String v) {
        return Component.empty().append(name(k + ": ")).append(value(v)).append(nl());
    }

    /** One clickable [label] that runs a command; the setter re-opens the book afterwards. */
    private static Component opt(String label, String command, String hover) {
        return Component.literal("[" + label + "] ").withStyle(style -> style
            .withColor(ChatFormatting.DARK_AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static Component optRow(String sub, String hover, String... values) {
        MutableComponent row = Component.empty();
        for (String v : values) {
            row.append(opt(v, "/voxygen " + sub + " " + v, hover + " = " + v));
        }
        row.append(nl());
        return row;
    }

    private static Component nl() { return Component.literal("\n"); }
    private static Component space() { return Component.literal(" "); }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
