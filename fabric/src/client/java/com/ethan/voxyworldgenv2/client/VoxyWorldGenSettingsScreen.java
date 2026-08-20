package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "VoxyWorldGen Server Settings" screen — the professional replacement for the written book,
 * built from vanilla widgets (Option A of the design canvas). The server drives everything: it
 * sends a {@link NetworkHandler.SettingsSnapshotPayload} to open or refresh the screen, and edits
 * go back as string-keyed ops that get the same clamps as the /voxygen commands. Non-ops get a
 * read-only view and no Players tab; the op check that matters is re-done server-side per packet.
 */
@Environment(EnvType.CLIENT)
public class VoxyWorldGenSettingsScreen extends Screen {

    private enum Tab { GENERATION, NETWORK, PLAYERS, HUD }

    private NetworkHandler.SettingsSnapshotPayload snapshot;
    private Tab tab = Tab.GENERATION;
    /** Edits not yet applied; key/value exactly as SettingsUpdatePayload carries them. */
    private final Map<String, String> pending = new LinkedHashMap<>();
    private int playerPage = 0;

    private static final int PANEL_WIDTH = 340;
    private static final int PLAYERS_PER_PAGE = 5;

    public VoxyWorldGenSettingsScreen(NetworkHandler.SettingsSnapshotPayload snapshot) {
        super(Component.literal("VoxyWorldGen Server Settings"));
        this.snapshot = snapshot;
    }

    /** A fresh snapshot after an apply (or a non-op rejection): re-render truth, drop edits. */
    public void refresh(NetworkHandler.SettingsSnapshotPayload snapshot) {
        this.snapshot = snapshot;
        this.pending.clear();
        rebuildWidgets();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int tabY = 42;

        List<Tab> tabs = new ArrayList<>(List.of(Tab.GENERATION, Tab.NETWORK));
        if (snapshot.isOp()) tabs.add(Tab.PLAYERS);
        tabs.add(Tab.HUD);

        int tabWidth = 82;
        int totalWidth = tabs.size() * tabWidth + (tabs.size() - 1) * 4;
        int tx = cx - totalWidth / 2;
        for (Tab t : tabs) {
            Button b = Button.builder(tabLabel(t), btn -> {
                this.tab = t;
                this.playerPage = 0;
                rebuildWidgets();
            }).bounds(tx, tabY, tabWidth, 20).build();
            b.active = t != tab;
            addRenderableWidget(b);
            tx += tabWidth + 4;
        }

        switch (tab) {
            case GENERATION -> initGeneration(cx);
            case NETWORK -> initNetwork(cx);
            case PLAYERS -> initPlayers(cx);
            case HUD -> initHud(cx);
        }

        boolean dirty = !pending.isEmpty();
        Button apply = Button.builder(Component.literal(dirty ? "Apply (" + pending.size() + ")" : "Apply"),
            b -> sendPending(false)).bounds(cx - 156, this.height - 32, 150, 20).build();
        apply.active = snapshot.isOp() && dirty;
        addRenderableWidget(apply);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            sendPending(true);
        }).bounds(cx + 6, this.height - 32, 150, 20).build());
    }

    private Component tabLabel(Tab t) {
        String name = switch (t) {
            case GENERATION -> "Generation";
            case NETWORK -> "Network";
            case PLAYERS -> "Players";
            case HUD -> "HUD";
        };
        return Component.literal(name);
    }

    // ---- tabs -------------------------------------------------------------------------------

    private void initGeneration(int cx) {
        int y = 78;
        addToggle(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Generation", "enabled",
            currentBool("enabled", snapshot.enabled()));
        y += 26;
        addSlider(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Radius", "radius",
            currentInt("radius", snapshot.generationRadius()), 1, 512, " chunks");
        y += 26;
        addSlider(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Parallel tasks", "tasks",
            currentInt("tasks", snapshot.maxActiveTasks()), 1, 128, "");
        y += 26;
        addSlider(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Gen rate", "genrate",
            currentInt("genrate", snapshot.maxChunksPerSecond()), 0, 1000, " chunks/s");
    }

    private void initNetwork(int cx) {
        int y = 78;
        // Rate cap slider works in tenths of Mbps so 0.5 steps are reachable.
        int capTenths = (int) Math.round(currentDouble("ratelimit", snapshot.maxMbpsPerPlayer()) * 10);
        addRenderableWidget(new LabeledSlider(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, capTenths, 0, 500) {
            @Override
            protected String label(int v) {
                return v <= 0 ? "Rate cap: unlimited" : String.format("Rate cap: %.1f Mbps / player", v / 10.0);
            }

            @Override
            protected void commit(int v) {
                stage("ratelimit", String.valueOf(v / 10.0));
            }
        });
        y += 26;
        addSlider(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Send distance", "senddistance",
            currentInt("senddistance", snapshot.lodSendDistanceChunks()), 0, 1024, " chunks");
    }

    private void initHud(int cx) {
        int y = 78;
        addToggle(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Show compressed (wire) bytes", "hud.compressed",
            currentBool("hud.compressed", snapshot.hudCompressed()));
        y += 26;
        addToggle(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Show compression savings", "hud.savings",
            currentBool("hud.savings", snapshot.hudSavings()));
        y += 26;
        addToggle(cx - PANEL_WIDTH / 2, y, PANEL_WIDTH, "Show client disk usage", "hud.clientdisk",
            currentBool("hud.clientdisk", snapshot.hudClientDisk()));
    }

    private void initPlayers(int cx) {
        var players = snapshot.players();
        int pages = Math.max(1, (players.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        playerPage = Math.min(playerPage, pages - 1);

        int y = 96;
        int left = cx - 230;
        for (int i = playerPage * PLAYERS_PER_PAGE;
             i < Math.min(players.size(), (playerPage + 1) * PLAYERS_PER_PAGE); i++) {
            var row = players.get(i);
            String uuid = row.uuid().toString();

            double capNow = pending.containsKey("player." + uuid + ".ratelimit")
                ? Double.parseDouble(pending.get("player." + uuid + ".ratelimit"))
                : (row.hasCap() ? row.capMbps() : -1);
            Button cap = Button.builder(Component.literal(capLabel(capNow)), b -> {
                double next = nextCap(pendingOrRow(uuid, row));
                stage("player." + uuid + ".ratelimit", String.valueOf(next));
                rebuildWidgets();
            }).bounds(left + 250, y, 74, 20).build();
            cap.active = snapshot.isOp();
            addRenderableWidget(cap);

            int distNow = pending.containsKey("player." + uuid + ".senddistance")
                ? Integer.parseInt(pending.get("player." + uuid + ".senddistance"))
                : (row.hasDist() ? row.distChunks() : -1);
            Button dist = Button.builder(Component.literal(distLabel(distNow)), b -> {
                int next = nextDist(distNow);
                stage("player." + uuid + ".senddistance", String.valueOf(next));
                rebuildWidgets();
            }).bounds(left + 328, y, 74, 20).build();
            dist.active = snapshot.isOp();
            addRenderableWidget(dist);

            Button reset = Button.builder(Component.literal("Reset"), b -> {
                pending.keySet().removeIf(k -> k.startsWith("player." + uuid + "."));
                stage("player." + uuid + ".reset", "");
                rebuildWidgets();
            }).bounds(left + 406, y, 54, 20).build();
            reset.active = snapshot.isOp() && (row.hasCap() || row.hasDist()
                || pending.keySet().stream().anyMatch(k -> k.startsWith("player." + uuid + ".")));
            addRenderableWidget(reset);

            y += 24;
        }

        if (pages > 1) {
            Button prev = Button.builder(Component.literal("<"), b -> {
                playerPage = Math.max(0, playerPage - 1);
                rebuildWidgets();
            }).bounds(cx - 60, y + 6, 20, 20).build();
            prev.active = playerPage > 0;
            addRenderableWidget(prev);
            Button next = Button.builder(Component.literal(">"), b -> {
                playerPage = Math.min(pages - 1, playerPage + 1);
                rebuildWidgets();
            }).bounds(cx + 40, y + 6, 20, 20).build();
            next.active = playerPage < pages - 1;
            addRenderableWidget(next);
        }
    }

    // ---- widget helpers ----------------------------------------------------------------------

    /** Cycle: global -> 1 -> 2 -> 5 -> 10 -> 25 -> 50 -> unlimited(0) -> 1 ... (-1 = global). */
    private static double nextCap(double current) {
        double[] cycle = {1, 2, 5, 10, 25, 50, 0};
        if (current < 0) return cycle[0];
        for (int i = 0; i < cycle.length - 1; i++) {
            if (Math.abs(cycle[i] - current) < 0.001) return cycle[i + 1];
        }
        return cycle[0];
    }

    private double pendingOrRow(String uuid, NetworkHandler.SettingsSnapshotPayload.PlayerRow row) {
        String staged = pending.get("player." + uuid + ".ratelimit");
        if (staged != null) return Double.parseDouble(staged);
        return row.hasCap() ? row.capMbps() : -1;
    }

    private static int nextDist(int current) {
        int[] cycle = {64, 128, 256, 512, 1024, 0};
        if (current < 0) return cycle[0];
        for (int i = 0; i < cycle.length - 1; i++) {
            if (cycle[i] == current) return cycle[i + 1];
        }
        return cycle[0];
    }

    private static String capLabel(double cap) {
        if (cap < 0) return "cap: global";
        if (cap == 0) return "cap: ∞";
        return String.format("cap: %.0f Mbps", cap);
    }

    private static String distLabel(int dist) {
        if (dist < 0) return "dist: global";
        if (dist == 0) return "dist: ∞";
        return "dist: " + dist;
    }

    private void stage(String key, String value) {
        pending.put(key, value);
    }

    private boolean currentBool(String key, boolean fromSnapshot) {
        String staged = pending.get(key);
        return staged != null ? Boolean.parseBoolean(staged) : fromSnapshot;
    }

    private int currentInt(String key, int fromSnapshot) {
        String staged = pending.get(key);
        return staged != null ? Integer.parseInt(staged) : fromSnapshot;
    }

    private double currentDouble(String key, double fromSnapshot) {
        String staged = pending.get(key);
        return staged != null ? Double.parseDouble(staged) : fromSnapshot;
    }

    private void addToggle(int x, int y, int w, String label, String key, boolean value) {
        Button b = Button.builder(toggleLabel(label, value), btn -> {
            boolean now = !currentBool(key, value);
            stage(key, String.valueOf(now));
            btn.setMessage(toggleLabel(label, now));
            rebuildWidgets();
        }).bounds(x, y, w, 20).build();
        b.active = snapshot.isOp();
        addRenderableWidget(b);
    }

    private static Component toggleLabel(String label, boolean on) {
        return Component.literal(label + ": ").append(on
            ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
            : Component.literal("OFF").withStyle(ChatFormatting.RED));
    }

    private void addSlider(int x, int y, int w, String label, String key, int value, int min, int max, String unit) {
        LabeledSlider slider = new LabeledSlider(x, y, w, value, min, max) {
            @Override
            protected String label(int v) {
                if (v <= 0 && min == 0) return label + ": unlimited";
                return label + ": " + v + unit;
            }

            @Override
            protected void commit(int v) {
                stage(key, String.valueOf(v));
            }
        };
        addRenderableWidget(slider);
    }

    /** A vanilla slider over an int range whose label and staging are supplied by the call site. */
    private abstract class LabeledSlider extends AbstractSliderButton {
        private final int min;
        private final int max;

        LabeledSlider(int x, int y, int w, int value, int min, int max) {
            super(x, y, w, 20, Component.empty(), (double) (value - min) / (max - min));
            this.min = min;
            this.max = max;
            this.active = snapshot.isOp();
            updateMessage();
        }

        private int intValue() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label(intValue())));
        }

        @Override
        protected void applyValue() {
            commit(intValue());
        }

        protected abstract String label(int v);

        protected abstract void commit(int v);
    }

    private void sendPending(boolean close) {
        if (!pending.isEmpty() && snapshot.isOp()
                && ClientPlayNetworking.canSend(NetworkHandler.SettingsUpdatePayload.TYPE)) {
            List<NetworkHandler.SettingsUpdatePayload.Op> staged = new ArrayList<>();
            pending.forEach((k, v) -> staged.add(new NetworkHandler.SettingsUpdatePayload.Op(k, v)));
            // The payload caps at MAX_OPS; the screen can't realistically exceed it, but never
            // send something the server would reject wholesale.
            List<NetworkHandler.SettingsUpdatePayload.Op> ops =
                staged.size() > NetworkHandler.SettingsUpdatePayload.MAX_OPS
                    ? staged.subList(0, NetworkHandler.SettingsUpdatePayload.MAX_OPS)
                    : staged;
            ClientPlayNetworking.send(new NetworkHandler.SettingsUpdatePayload(ops));
            pending.clear();
        }
        if (close) onClose();
    }

    // ---- rendering ----------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;

        g.centeredText(this.font, Component.literal(this.title.getString())
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), cx, 14, 0xFFFFFFFF);
        String sub = (snapshot.isOp() ? "op level 2" : "read-only")
            + (snapshot.singleplayerActive() ? " · singleplayer profile" : "");
        g.centeredText(this.font, Component.literal(sub).withStyle(ChatFormatting.GRAY), cx, 27, 0xFFA0A0A0);

        if (tab == Tab.PLAYERS) {
            renderPlayersText(g, cx);
        }

        // live stats strip above the footer, honoring the HUD toggles
        StringBuilder stats = new StringBuilder();
        if (snapshot.hudCompressed()) {
            stats.append(human(snapshot.wireBytesSent())).append(" sent · ")
                .append(String.format("%.2f MB/s wire", snapshot.wireBps() / 1_000_000.0));
        }
        if (snapshot.hudSavings() && snapshot.zipRatioX10() > 0) {
            if (stats.length() > 0) stats.append(" · ");
            stats.append(String.format("zip %.1f×", snapshot.zipRatioX10() / 10.0));
        }
        if (stats.length() > 0) stats.append(" · ");
        stats.append(String.format("queue %d/%d · %,d chunks done",
            snapshot.queued(), snapshot.maxQueued(), snapshot.chunksDone()));
        g.centeredText(this.font, Component.literal(stats.toString()).withStyle(ChatFormatting.DARK_GRAY),
            cx, this.height - 46, 0xFF707070);
    }

    private void renderPlayersText(GuiGraphicsExtractor g, int cx) {
        var players = snapshot.players();
        int left = cx - 230;
        g.text(this.font, Component.literal("PLAYER").withStyle(ChatFormatting.DARK_GRAY), left, 84, 0xFF707070);
        g.text(this.font, Component.literal("RECEIVED / ON DISK").withStyle(ChatFormatting.DARK_GRAY), left + 130, 84, 0xFF707070);

        int y = 96;
        for (int i = playerPage * PLAYERS_PER_PAGE;
             i < Math.min(players.size(), (playerPage + 1) * PLAYERS_PER_PAGE); i++) {
            var row = players.get(i);

            var name = Component.literal(row.online() ? "● " : "○ ")
                .withStyle(row.online() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                .append(Component.literal(row.name())
                    .withStyle(row.online() ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            if (row.hasCap() || row.hasDist()) {
                name = name.append(Component.literal(" [MODIFIED]").withStyle(ChatFormatting.GOLD));
            }
            g.text(this.font, name, left, y + 6, 0xFFFFFFFF);

            String traffic = human(row.wireBytes())
                + (row.diskBytes() >= 0 ? " / " + human(row.diskBytes()) : " / —")
                + (row.online() ? "" : " · " + ago(row.lastSeenMs()));
            g.text(this.font, Component.literal(traffic).withStyle(ChatFormatting.GRAY), left + 130, y + 6, 0xFFA0A0A0);
            y += 24;
        }

        if (players.isEmpty()) {
            g.centeredText(this.font, Component.literal("no players have joined yet")
                .withStyle(ChatFormatting.DARK_GRAY), cx, 110, 0xFF707070);
        }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    private static String ago(long thenMs) {
        long s = Math.max(0, (System.currentTimeMillis() - thenMs) / 1000);
        if (s < 90) return s + "s ago";
        if (s < 5400) return (s / 60) + "m ago";
        if (s < 129600) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
