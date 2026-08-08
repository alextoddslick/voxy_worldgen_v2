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
            Filterable.passThrough(statsPage()));

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
            Filterable.passThrough("Voxy WorldGen"), "voxyworldgenv2", 0, pages, true));

        // Hotbar slot n lives at index 36 + n in the player's inventory menu.
        int slot = 36 + player.getInventory().getSelectedSlot();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            player.inventoryMenu.containerId, player.inventoryMenu.getStateId(), slot, book));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        // Book screen is open now; put the client's inventory back the way it really is.
        player.inventoryMenu.sendAllDataToRemote();
    }

    // ---- pages ----------------------------------------------------------------------------

    private static Component settingsPage(ServerPlayer player) {
        var c = Config.DATA;
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

        p.append(name("Radius: "));
        p.append(value(c.generationRadius + " chunks"));
        p.append(nl());
        p.append(optRow("radius", "generation radius", "32", "64", "128", "256"));

        p.append(name("Tasks: "));
        p.append(value(String.valueOf(c.maxActiveTasks)));
        p.append(nl());
        p.append(optRow("tasks", "parallel generation tasks", "10", "20", "40"));

        p.append(name("Rate cap: "));
        p.append(value(c.maxMbpsPerPlayer <= 0 ? "unlimited"
            : String.format("%.0f Mbps", c.maxMbpsPerPlayer)));
        p.append(nl());
        p.append(opt("2", "/voxygen ratelimit 2", "2 Mbps per player"));
        p.append(opt("10", "/voxygen ratelimit 10", "10 Mbps per player"));
        p.append(opt("50", "/voxygen ratelimit 50", "50 Mbps per player"));
        p.append(opt("off", "/voxygen ratelimit off", "no bandwidth cap (LAN)"));
        p.append(nl());

        p.append(name("Log every: "));
        p.append(value(c.logProgressIntervalSeconds <= 0 ? "off" : c.logProgressIntervalSeconds + "s"));
        p.append(nl());
        p.append(opt("5", "/voxygen loginterval 5", "progress log every 5s"));
        p.append(opt("10", "/voxygen loginterval 10", "progress log every 10s"));
        p.append(opt("30", "/voxygen loginterval 30", "progress log every 30s"));
        p.append(opt("off", "/voxygen loginterval off", "no progress logging"));
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

        p.append(Component.literal("\nlive stats on page 2").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        return p;
    }

    private static Component statsPage() {
        var mgr = ChunkGenerationManager.getInstance();
        var st = mgr.getStats();
        var q = LodSendQueue.getInstance();
        long bps = q.getCurrentBytesPerSecond();
        MutableComponent p = Component.empty();

        p.append(Component.literal("Live Stats\n").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));

        p.append(line("Players", String.valueOf(PlayerTracker.getInstance().getPlayerCount())));
        p.append(line("Done", String.valueOf(st.getCompleted())));
        p.append(line("Rate", String.format("%.1f c/s", st.getChunksPerSecond())));
        p.append(line("Left", String.valueOf(mgr.getRemainingInRadius())));
        p.append(line("Active", mgr.getActiveTaskCount() + " / " + Config.DATA.maxActiveTasks));
        p.append(line("Failed", String.valueOf(st.getFailed())));
        p.append(line("Send q", q.getQueuedJobs() + " / " + q.getMaxQueuedJobs()));
        p.append(line("Deferred", String.valueOf(q.getJobsDropped())));
        p.append(line("Sent", human(q.getBytesSent())));
        long raw = com.ethan.voxyworldgenv2.network.NetworkHandler.RAW_SECTION_BYTES.get();
        long wire = com.ethan.voxyworldgenv2.network.NetworkHandler.WIRE_SECTION_BYTES.get();
        if (raw > 0 && wire > 0) {
            p.append(line("Zip", String.format("%.1fx", (double) raw / wire)));
        }
        p.append(line("Now", String.format("%.2f MB/s", bps / 1_000_000.0)));
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
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
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
