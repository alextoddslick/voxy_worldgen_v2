package com.ethan.voxyworldgenv2.command;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.network.LodSendQueue;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carpet-style live HUD in the tab list (hold Tab), toggled per player with {@code /voxygen log}.
 *
 * <p>Same mechanism as carpet's {@code /log tps}: the server rewrites the tab-list header/footer
 * once a second for subscribed players. Session-scoped on purpose — like carpet, a relog clears
 * the subscription. Note this owns the tab header/footer while enabled; a pack that also sets a
 * fancy tab header will be overridden until the player toggles off (an empty header/footer is
 * sent on unsubscribe to hand it back).
 */
public final class TabHud {

    private static final Set<UUID> SUBSCRIBED = ConcurrentHashMap.newKeySet();

    private TabHud() {}

    /** @return true if the player is now subscribed. */
    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (SUBSCRIBED.remove(id)) {
            player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
            return false;
        }
        SUBSCRIBED.add(id);
        send(player); // immediate feedback rather than waiting for the next tick
        return true;
    }

    public static void clear() {
        SUBSCRIBED.clear();
    }

    /** Called once a second from the manager's tick. */
    public static void tick(MinecraftServer server) {
        if (SUBSCRIBED.isEmpty()) return;
        SUBSCRIBED.removeIf(id -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return true; // disconnected; subscription is session-scoped
            send(p);
            return false;
        });
    }

    private static void send(ServerPlayer viewer) {
        viewer.connection.send(new ClientboundTabListPacket(header(), footer(viewer)));
    }

    private static Component header() {
        return Component.literal("⛏ Voxy WorldGen").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private static Component footer(ServerPlayer viewer) {
        var mgr = ChunkGenerationManager.getInstance();
        var st = mgr.getStats();
        var q = LodSendQueue.getInstance();
        MutableComponent f = Component.empty();

        // generation line
        int remaining = mgr.getRemainingInRadius();
        String state;
        ChatFormatting stateColor;
        if (!Config.DATA.enabled) {
            state = "generation off"; stateColor = ChatFormatting.RED;
        } else if (mgr.isTransitionPaused()) {
            state = "paused: player loading"; stateColor = ChatFormatting.YELLOW;
        } else if (mgr.isThrottled()) {
            state = "paused: low TPS"; stateColor = ChatFormatting.YELLOW;
        } else if (mgr.getActiveTaskCount() > 0 || remaining > 0) {
            state = String.format("generating @ %.1f/s", st.getChunksPerSecond());
            stateColor = ChatFormatting.GREEN;
        } else {
            state = "caught up"; stateColor = ChatFormatting.GRAY;
        }
        f.append(Component.literal(state).withStyle(stateColor));
        f.append(Component.literal(String.format("  %,d done · %,d left · r%d",
            st.getCompleted(), remaining, Config.DATA.generationRadius)).withStyle(ChatFormatting.GRAY));
        f.append(nl());

        // pipeline line
        long raw = NetworkHandler.RAW_SECTION_BYTES.get();
        long wire = NetworkHandler.WIRE_SECTION_BYTES.get();
        String zip = (raw > 0 && wire > 0) ? String.format(" · zip %.1fx", (double) raw / wire) : "";
        f.append(Component.literal(String.format("queue %d/%d · %s%s",
                q.getQueuedJobs(), q.getMaxQueuedJobs(), rate(q.getCurrentBytesPerSecond()), zip))
            .withStyle(ChatFormatting.DARK_AQUA));
        f.append(nl());

        // per-player lines: traffic + synced chunk counts, viewer first
        var perBytes = q.getPerPlayerBytes();
        f.append(playerLine(viewer, perBytes.getOrDefault(viewer.getUUID(), 0L), true));
        for (ServerPlayer p : PlayerTracker.getInstance().getPlayers()) {
            if (p.getUUID().equals(viewer.getUUID())) continue;
            f.append(nl());
            f.append(playerLine(p, perBytes.getOrDefault(p.getUUID(), 0L), false));
        }
        return f;
    }

    private static Component playerLine(ServerPlayer p, long bytes, boolean isViewer) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(p.getUUID());
        int syncedCount = synced != null ? synced.size() : 0;
        return Component.empty()
            .append(Component.literal(isViewer ? "you" : p.getName().getString())
                .withStyle(isViewer ? ChatFormatting.WHITE : ChatFormatting.GRAY))
            .append(Component.literal(String.format(": %s received · %,d chunks synced",
                human(bytes), syncedCount)).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component nl() { return Component.literal("\n"); }

    /** Network rates are always MB/s (decimal, matching Mbps x8). */
    private static String rate(long bytesPerSec) {
        return String.format("%.2f MB/s", bytesPerSec / 1_000_000.0);
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
