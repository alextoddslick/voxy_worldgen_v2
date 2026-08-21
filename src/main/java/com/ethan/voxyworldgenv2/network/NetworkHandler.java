package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

public class NetworkHandler {
    public static final Identifier HANDSHAKE_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":handshake");
    public static final Identifier HANDSHAKE_ACK_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":handshake_ack");
    public static final Identifier LOD_DATA_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":lod_data");
    public static final Identifier SERVER_CONFIG_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":server_config");
    public static final Identifier SERVER_CONFIG_PUSH_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":server_config_push");
    public static final Identifier KNOWN_CHUNKS_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":known_chunks");
    public static final Identifier STORAGE_REPORT_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":storage_report");
    public static final Identifier SETTINGS_SNAPSHOT_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":settings_snapshot");
    public static final Identifier SETTINGS_UPDATE_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":settings_update");

    /**
     * Bumped whenever the payload wire format changes. Peers gate every serverbound payload on the
     * floor where it was introduced (known-chunks {@code >= 2}, storage report {@code >= 3},
     * handshake-ack/server-config/settings {@code >= 5}) -- never equality -- because sending a
     * payload a server has not registered can drop the connection.
     */
    public static final int PROTOCOL_VERSION = 5;

    // op level required to edit the live server config or push a settings-screen change
    private static final int PERMISSION_OP = 2;

    private static boolean canEditConfig(ServerPlayer player) {
        return player.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.byId(PERMISSION_OP)));
    }

    // Keep individual packets well under the protocol ceiling to prevent connection resets on
    // public servers. The binding limit for a clientbound custom payload in 1.21.1 is
    // ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 1 MiB (the 2 MiB figure is the frame
    // length cap, which is a separate, larger limit) -- so this leaves 32x headroom.
    static final int MAX_PACKET_BYTES = 32_768;

    public record HandshakePayload(boolean serverHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        /**
         * Protocol 1 wrote only the boolean. Reading the version unconditionally would throw
         * inside the netty decoder on a protocol-1 server and drop the connection with an opaque
         * "Internal Exception" before the player reaches the world, so an absent version reads as
         * 1 — which is exactly what it means. {@code supportsKnownChunks()} then stays false, the
         * client never uploads, and the session behaves as it did before this feature existed.
         */
        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.isReadable() ? buf.readVarInt() : 1);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
            buf.writeVarInt(this.protocolVersion);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> server ack carrying the client's own protocol, so the server can gate what it
     * sends on a floor rather than assuming a match. Only ever sent by a client that already
     * parsed a protocol-5+ HandshakePayload, so the server registering it is never in question.
     */
    public record HandshakeAckPayload(int clientProtocol) implements CustomPacketPayload {
        public static final Type<HandshakeAckPayload> TYPE = new Type<>(HANDSHAKE_ACK_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakeAckPayload> CODEC = CustomPacketPayload.codec(HandshakeAckPayload::write, HandshakeAckPayload::new);

        public HandshakeAckPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.clientProtocol);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: the live server values plus whether this client may edit them. */
    public record ServerConfigPayload(Config.ServerConfig config, boolean canEdit) implements CustomPacketPayload {
        public static final Type<ServerConfigPayload> TYPE = new Type<>(SERVER_CONFIG_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPayload> CODEC = CustomPacketPayload.codec(ServerConfigPayload::write, ServerConfigPayload::new);

        public ServerConfigPayload(FriendlyByteBuf buf) {
            this(readServerConfig(buf), buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            writeServerConfig(buf, config);
            buf.writeBoolean(canEdit);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client op -> server: pushes new values to apply. */
    public record ServerConfigPushPayload(Config.ServerConfig config) implements CustomPacketPayload {
        public static final Type<ServerConfigPushPayload> TYPE = new Type<>(SERVER_CONFIG_PUSH_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPushPayload> CODEC = CustomPacketPayload.codec(ServerConfigPushPayload::write, ServerConfigPushPayload::new);

        public ServerConfigPushPayload(FriendlyByteBuf buf) {
            this(readServerConfig(buf));
        }

        public void write(FriendlyByteBuf buf) {
            writeServerConfig(buf, config);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static Config.ServerConfig readServerConfig(FriendlyByteBuf buf) {
        return new Config.ServerConfig(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeServerConfig(FriendlyByteBuf buf, Config.ServerConfig c) {
        buf.writeBoolean(c.enabled());
        buf.writeVarInt(c.generationRadius());
        buf.writeVarInt(c.updateInterval());
        buf.writeVarInt(c.maxQueueSize());
        buf.writeVarInt(c.maxActiveTasks());
    }

    /**
     * Client -> server: how much disk Voxy's store for this world occupies, purely informational
     * (tab HUD / {@code /voxygen traffic}); a lying client can only misreport its own line.
     */
    public record StorageReportPayload(long bytesOnDisk) implements CustomPacketPayload {
        public static final Type<StorageReportPayload> TYPE = new Type<>(STORAGE_REPORT_ID);
        public static final StreamCodec<FriendlyByteBuf, StorageReportPayload> CODEC =
            CustomPacketPayload.codec(StorageReportPayload::write, StorageReportPayload::new);

        public StorageReportPayload(FriendlyByteBuf buf) {
            this(buf.readLong());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeLong(bytesOnDisk);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server -> client: a compact snapshot of the settings this branch actually exposes (this
     * port did not bring in the fork's separate settings-screen feature, only the wire format --
     * see the phase-3 report). {@code queued}/{@code maxQueued}/{@code chunksDone} are a live-stats
     * strip; {@code isOp} tells a future client whether {@link SettingsUpdatePayload} would do
     * anything.
     */
    public record SettingsSnapshotPayload(
        boolean isOp, boolean singleplayerActive,
        boolean enabled, int generationRadius, int maxActiveTasks, int maxChunksPerSecond,
        double maxMbpsPerPlayer,
        long bytesSent, long bytesPerSecond, int zipRatioX10,
        int queued, int maxQueued, long chunksDone
    ) implements CustomPacketPayload {
        public static final Type<SettingsSnapshotPayload> TYPE = new Type<>(SETTINGS_SNAPSHOT_ID);
        public static final StreamCodec<FriendlyByteBuf, SettingsSnapshotPayload> CODEC =
            CustomPacketPayload.codec(SettingsSnapshotPayload::write, SettingsSnapshotPayload::new);

        public SettingsSnapshotPayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readDouble(),
                buf.readLong(), buf.readLong(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readLong());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(isOp);
            buf.writeBoolean(singleplayerActive);
            buf.writeBoolean(enabled);
            buf.writeVarInt(generationRadius);
            buf.writeVarInt(maxActiveTasks);
            buf.writeVarInt(maxChunksPerSecond);
            buf.writeDouble(maxMbpsPerPlayer);
            buf.writeLong(bytesSent);
            buf.writeLong(bytesPerSecond);
            buf.writeVarInt(zipRatioX10);
            buf.writeVarInt(queued);
            buf.writeVarInt(maxQueued);
            buf.writeLong(chunksDone);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> server: string-keyed settings edits. Bounded hard at read time -- the op check
     * happens later, so the codec itself must not let an unauthenticated packet allocate freely.
     */
    public record SettingsUpdatePayload(List<Op> ops) implements CustomPacketPayload {
        public static final Type<SettingsUpdatePayload> TYPE = new Type<>(SETTINGS_UPDATE_ID);
        public static final StreamCodec<FriendlyByteBuf, SettingsUpdatePayload> CODEC =
            CustomPacketPayload.codec(SettingsUpdatePayload::write, SettingsUpdatePayload::new);

        public static final int MAX_OPS = 64;

        public record Op(String key, String value) {
            void write(FriendlyByteBuf buf) {
                buf.writeUtf(key, 96);
                buf.writeUtf(value, 64);
            }

            static Op read(FriendlyByteBuf buf) {
                return new Op(buf.readUtf(96), buf.readUtf(64));
            }
        }

        public SettingsUpdatePayload(FriendlyByteBuf buf) {
            this(readOps(buf));
        }

        private static List<Op> readOps(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_OPS) {
                throw new io.netty.handler.codec.DecoderException("settings update too large: " + count);
            }
            List<Op> ops = new ArrayList<>(count);
            for (int i = 0; i < count; i++) ops.add(Op.read(buf));
            return ops;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(ops.size());
            for (Op op : ops) op.write(buf);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Raw (pre-compression) section bytes written; server-side only. */
    public static final java.util.concurrent.atomic.AtomicLong RAW_SECTION_BYTES = new java.util.concurrent.atomic.AtomicLong();
    /** Deflated section bytes actually put on the wire; server-side only. */
    public static final java.util.concurrent.atomic.AtomicLong WIRE_SECTION_BYTES = new java.util.concurrent.atomic.AtomicLong();

    public record LODDataPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) implements CustomPacketPayload {
        public static final Type<LODDataPayload> TYPE = new Type<>(LOD_DATA_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LODDataPayload> CODEC = CustomPacketPayload.codec(LODDataPayload::write, LODDataPayload::new);

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(RegistryFriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }

            public static SectionData read(RegistryFriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(),
                    buf.readByteArray(),
                    buf.readByteArray(),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())
                );
            }
        }

        public LODDataPayload(RegistryFriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                readCompressedSections(buf)
            );
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimension.identifier().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            writeCompressedSections(buf, sections);
        }

        /**
         * The section block is deflated as one unit. Terrain data is highly repetitive
         * (palettes, runs of the same state, near-identical light arrays), so a whole-batch
         * zlib window typically shrinks it 3-6x — far better than the connection's per-packet
         * compression alone manages, and measured explicitly via RAW/WIRE_SECTION_BYTES.
         */
        private static void writeCompressedSections(RegistryFriendlyByteBuf buf, List<SectionData> sections) {
            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf inner = new RegistryFriendlyByteBuf(new FriendlyByteBuf(raw), buf.registryAccess());
                inner.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
                byte[] plain = new byte[inner.readableBytes()];
                inner.readBytes(plain);

                java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                byte[] out = new byte[plain.length + 64];
                int packed;
                try {
                    deflater.setInput(plain);
                    deflater.finish();
                    packed = deflater.deflate(out);
                    // Incompressible data can exceed the buffer; fall back to stored bytes.
                    if (!deflater.finished()) packed = 0;
                } finally {
                    deflater.end();
                }

                buf.writeVarInt(plain.length);
                if (packed > 0 && packed < plain.length) {
                    buf.writeByteArray(java.util.Arrays.copyOf(out, packed));
                    WIRE_SECTION_BYTES.addAndGet(packed);
                } else {
                    buf.writeByteArray(plain);
                    WIRE_SECTION_BYTES.addAndGet(plain.length);
                }
                RAW_SECTION_BYTES.addAndGet(plain.length);
            } finally {
                raw.release();
            }
        }

        private static List<SectionData> readCompressedSections(RegistryFriendlyByteBuf buf) {
            int plainLength = buf.readVarInt();
            byte[] body = buf.readByteArray();

            byte[] plain;
            if (body.length == plainLength) {
                plain = body; // stored uncompressed (incompressible fallback)
            } else {
                plain = new byte[plainLength];
                java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                try {
                    inflater.setInput(body);
                    int n = inflater.inflate(plain);
                    if (n != plainLength) throw new java.util.zip.DataFormatException("short inflate: " + n + " != " + plainLength);
                } catch (java.util.zip.DataFormatException e) {
                    throw new io.netty.handler.codec.DecoderException("bad LOD section data", e);
                } finally {
                    inflater.end();
                }
            }

            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.wrappedBuffer(plain);
            try {
                RegistryFriendlyByteBuf inner = new RegistryFriendlyByteBuf(new FriendlyByteBuf(raw), buf.registryAccess());
                return inner.readCollection(ArrayList::new, b -> SectionData.read((RegistryFriendlyByteBuf) b));
            } finally {
                raw.release();
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One batch of the client's "chunks I already have" set, as region bitmasks produced by
     * {@link RegionBitmask}. Several are sent per dimension; the final one carries {@code last}.
     */
    public record KnownChunksPayload(ResourceKey<Level> dimension, boolean last, byte[] body) implements CustomPacketPayload {
        public static final Type<KnownChunksPayload> TYPE = new Type<>(KNOWN_CHUNKS_ID);
        public static final StreamCodec<FriendlyByteBuf, KnownChunksPayload> CODEC =
            CustomPacketPayload.codec(KnownChunksPayload::write, KnownChunksPayload::new);

        public KnownChunksPayload(FriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf())),
                buf.readBoolean(),
                buf.readByteArray(MAX_PACKET_BYTES)
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(dimension.identifier().toString());
            buf.writeBoolean(last);
            buf.writeByteArray(body);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakePayload.TYPE, HandshakePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(KnownChunksPayload.TYPE, KnownChunksPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(HandshakeAckPayload.TYPE, HandshakeAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerConfigPushPayload.TYPE, ServerConfigPushPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StorageReportPayload.TYPE, StorageReportPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SettingsSnapshotPayload.TYPE, SettingsSnapshotPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingsUpdatePayload.TYPE, SettingsUpdatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(KnownChunksPayload.TYPE,
            (payload, context) -> receiveKnownChunks(context.player(), payload));

        ServerPlayNetworking.registerGlobalReceiver(HandshakeAckPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                // Record rather than compare: a mismatch is not a reason to send nothing, since
                // every feature is gated on a floor, and setClientProtocol must run before
                // sendServerConfig, which is gated on the recorded value.
                PlayerTracker.getInstance().setClientProtocol(player.getUUID(), payload.clientProtocol());
                if (payload.clientProtocol() != PROTOCOL_VERSION) {
                    VoxyWorldGenV2.LOGGER.info("client {} speaks voxy protocol {} (ours={}), serving what it supports",
                        player.getName().getString(), payload.clientProtocol(), PROTOCOL_VERSION);
                }
                sendServerConfig(player);
                sendSettingsSnapshot(player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(ServerConfigPushPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                if (!canEditConfig(player)) {
                    VoxyWorldGenV2.LOGGER.warn("ignoring server config push from non-op {}", player.getName().getString());
                    sendServerConfig(player); // snap their screen back to the real values
                    return;
                }
                Config.applyServerConfig(payload.config());
                Config.save();
                ChunkGenerationManager.getInstance().scheduleConfigReload();
                VoxyWorldGenV2.LOGGER.info("server config updated by op {}", player.getName().getString());
                broadcastServerConfig();
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StorageReportPayload.TYPE,
            (payload, context) -> receiveStorageReport(context.player(), payload));

        ServerPlayNetworking.registerGlobalReceiver(SettingsUpdatePayload.TYPE,
            (payload, context) -> receiveSettingsUpdate(context.player(), payload));

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized (protocol {})", PROTOCOL_VERSION);
    }

    /** Sends the live server config to one player; {@code canEdit} reflects their op status. */
    public static void sendServerConfig(ServerPlayer player) {
        boolean canEdit = canEditConfig(player);
        ServerPlayNetworking.send(player, new ServerConfigPayload(Config.ServerConfig.snapshot(), canEdit));
    }

    public static void broadcastServerConfig() {
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            sendServerConfig(player);
        }
    }

    /**
     * Stores the client's disk-usage figure. A negative count is a malformed or hostile packet,
     * not a value, so it is dropped rather than clamped; it must never render as "0 B" and read as
     * truth. Arrival rate is bounded in PlayerTracker's per-connection bookkeeping being simply
     * per-player state rather than a budget, since this is a rare, cheap, informational packet.
     */
    private static void receiveStorageReport(ServerPlayer player, StorageReportPayload payload) {
        if (payload.bytesOnDisk() < 0) return;
        PlayerTracker.getInstance().reportClientStoreBytes(player.getUUID(), payload.bytesOnDisk());
    }

    /**
     * Applies a settings-screen batch. The permission check happens HERE, per packet -- a settings
     * payload having been accepted at the codec level proves nothing about the sender's current op
     * status, and a deopped player's stale request must turn into a no-op, not an edit. A fresh
     * snapshot goes back either way so the client re-renders truth.
     */
    private static void receiveSettingsUpdate(ServerPlayer player, SettingsUpdatePayload payload) {
        if (!canEditConfig(player)) {
            VoxyWorldGenV2.LOGGER.warn("ignoring settings update from non-op {}", player.getName().getString());
            sendSettingsSnapshot(player);
            return;
        }

        boolean spActive = ChunkGenerationManager.getInstance().isSingleplayer()
            && Config.DATA.singleplayer != null && Config.DATA.singleplayer.enableSingleplayerDefaults;

        int applied = 0;
        for (SettingsUpdatePayload.Op op : payload.ops()) {
            if (applySettingsOp(op.key(), op.value(), spActive)) applied++;
        }
        if (applied > 0) {
            Config.save();
            ChunkGenerationManager.getInstance().scheduleConfigReload();
            VoxyWorldGenV2.LOGGER.info("{} applied {} settings change(s) via the settings payload",
                player.getName().getString(), applied);
        }
        sendSettingsSnapshot(player);
    }

    /**
     * Applies one key/value edit with the same clamps {@code /voxygen} uses, routing to the
     * singleplayer profile when it is the active one -- see VoxyGenCommand.spActive() for why.
     * Every value is clamped here rather than trusted, since the sender is a client.
     */
    private static boolean applySettingsOp(String key, String value, boolean spActive) {
        try {
            switch (key) {
                case "enabled" -> Config.DATA.enabled = Boolean.parseBoolean(value);
                case "generationRadius" -> {
                    int v = clampInt(value, 1, 512);
                    if (spActive) Config.DATA.singleplayer.generationRadius = v;
                    else Config.DATA.generationRadius = v;
                }
                case "maxActiveTasks" -> {
                    int v = clampInt(value, 1, 128);
                    if (spActive) Config.DATA.singleplayer.maxActiveTasks = v;
                    else Config.DATA.maxActiveTasks = v;
                }
                case "maxChunksPerSecond" -> {
                    int v = clampInt(value, 0, 100_000);
                    if (spActive) Config.DATA.singleplayer.maxChunksPerSecond = v;
                    else Config.DATA.maxChunksPerSecond = v;
                }
                case "maxMbpsPerPlayer" -> {
                    double v = clampDouble(value, 0.0, 1000.0);
                    if (spActive) Config.DATA.singleplayer.maxMbpsPerPlayer = v;
                    else Config.DATA.maxMbpsPerPlayer = v;
                }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int clampInt(String raw, int lo, int hi) {
        return Math.max(lo, Math.min(hi, Integer.parseInt(raw)));
    }

    private static double clampDouble(String raw, double lo, double hi) {
        return Math.max(lo, Math.min(hi, Double.parseDouble(raw)));
    }

    /** Builds and sends the settings snapshot for one player's own live/effective values. */
    public static void sendSettingsSnapshot(ServerPlayer player) {
        var mgr = ChunkGenerationManager.getInstance();
        var c = Config.DATA;
        var q = LodSendQueue.getInstance();
        boolean sp = mgr.isSingleplayer();
        boolean spActive = sp && c.singleplayer != null && c.singleplayer.enableSingleplayerDefaults;
        boolean isOp = canEditConfig(player);

        long raw = RAW_SECTION_BYTES.get();
        long wire = WIRE_SECTION_BYTES.get();
        int zipX10 = (raw > 0 && wire > 0) ? (int) Math.round(raw * 10.0 / wire) : 0;

        ServerPlayNetworking.send(player, new SettingsSnapshotPayload(
            isOp, spActive,
            c.enabled,
            Config.getGenerationRadius(sp),
            Config.getMaxActiveTasks(sp),
            Config.getMaxChunksPerSecond(sp),
            Config.getMaxMbpsPerPlayer(sp),
            q.getBytesSent(), q.getCurrentBytesPerSecond(), zipX10,
            q.getQueuedJobs(), q.getMaxQueuedJobs(),
            mgr.getStats().getCompleted()));
    }

    /**
     * Seeds a player's synced set from what their client says Voxy already holds.
     *
     * <p>Applied on the handler thread rather than off-thread: packets must be merged in arrival
     * order, and the cost is bounded — a 64-chunk radius is ~16k inserts (sub-millisecond). A
     * 512-chunk radius is ~1M inserts and costs roughly 100ms once per join, which the timing warn
     * below surfaces. That is three orders of magnitude short of the 60s watchdog.
     *
     * <p>"Once per join" is a description of the honest client, not something the protocol
     * enforces, so it is enforced here. Each accepted packet is up to 180 regions x 1024 chunks =
     * 184,320 set insertions on the main thread, and a modified client can send them at packet
     * rate. Two cheap guards bound that: the payload must name the dimension the player is
     * actually in, and only a small number of packets are accepted per dimension entry, ending as
     * soon as the client's {@code last} packet arrives. Rejections are logged and ignored rather
     * than disconnecting — a false negative here only costs a re-send.
     */
    private static void receiveKnownChunks(ServerPlayer player, KnownChunksPayload payload) {
        if (!com.ethan.voxyworldgenv2.core.Config.DATA.rememberSentChunks) return;

        var tracker = PlayerTracker.getInstance();
        var store = tracker.getStore(player.getUUID());
        if (store == null) return;

        // Budget first, and keyed on where the player actually is rather than on anything the
        // payload claims: a rejected packet must still cost the sender its allowance, or a client
        // spraying payloads this method refuses would drive the logging below at packet rate.
        String dim = PlayerTracker.dimensionId(player.level().dimension());
        if (!tracker.acceptKnownChunksPacket(player.getUUID(), dim)) return;

        // A known set for a dimension the player is not in cannot be verified against anything and
        // is not something an honest client sends: it uploads on entering a dimension, by which
        // point the server has already moved it. Rejecting costs at most one re-send.
        if (!player.level().dimension().equals(payload.dimension())) {
            VoxyWorldGenV2.LOGGER.warn("ignoring known-chunks batch from {} for {} while they are in {}",
                player.getName().getString(), payload.dimension().identifier(),
                player.level().dimension().identifier());
            return;
        }

        long startedNs = System.nanoTime();
        int added;
        try {
            added = store.applyRegions(dim, RegionBitmask.decode(payload.body()));
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("discarding malformed known-chunks batch from {}: {}",
                player.getName().getString(), e.toString());
            return;
        }

        long millis = (System.nanoTime() - startedNs) / 1_000_000L;
        if (millis > 50) {
            VoxyWorldGenV2.LOGGER.warn("applying known chunks for {} took {}ms ({} chunks)",
                player.getName().getString(), millis, added);
        }

        if (payload.last()) {
            tracker.finishKnownChunksUpload(player.getUUID(), dim);
            tracker.clearGate(player.getUUID(), dim);
            VoxyWorldGenV2.LOGGER.info("{} reports {} known chunks in {}; skipping re-send",
                player.getName().getString(), store.size(dim), dim);
        }
    }

    private static void setSyncedState(ServerPlayer player, ResourceKey<Level> dimension,
                                       ChunkPos pos, boolean isSynced) {
        var store = PlayerTracker.getInstance().getStore(player.getUUID());
        if (store == null) return;
        String dim = PlayerTracker.dimensionId(dimension);
        if (isSynced) {
            store.markSynced(dim, pos.toLong());
        } else {
            store.markUnsynced(dim, pos.toLong());
        }
    }

    /**
     * Enforced at the one point every send path passes through, so block-update pushes cannot leak
     * through the join window. A gated send is dropped, not queued, and the chunk is left unsynced
     * so the catch-up path collects it once the gate lifts.
     */
    private static boolean gated(ServerPlayer player, ResourceKey<Level> dimension) {
        return PlayerTracker.getInstance()
            .isGated(player.getUUID(), PlayerTracker.dimensionId(dimension));
    }

    /** Range every full LOD sync (broadcast or catch-up) is scoped to; also used by ChunkUpdateTracker. */
    public static final double MAX_SYNC_DIST_SQ = 4096.0 * 4096.0;

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    /**
     * If {@code onlySectionYs} is non-null, only those section y-levels are sent -- used to resend
     * just the edited section after a block change instead of the whole column. The synced
     * bookkeeping is left untouched in that case: the chunk was already claimed (or deliberately
     * left unclaimed) by a prior full broadcast, so a filtered resend is a supplementary push, not
     * a new claim, and must not perturb whether catch-up still considers the chunk owed.
     */
    public static void broadcastLODData(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, onlySectionYs);

        if (sections.isEmpty()) return;

        var registryAccess = chunk.getLevel().registryAccess();
        var dimension = chunk.getLevel().dimension();

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > MAX_SYNC_DIST_SQ)) {
                if (onlySectionYs == null) setSyncedState(player, dimension, pos, false);
                continue;
            }

            if (gated(player, dimension)) {
                if (onlySectionYs == null) setSyncedState(player, dimension, pos, false);
                continue;
            }

            boolean accepted = LodSendQueue.getInstance().enqueue(
                player, dimension, pos, minY, sections, registryAccess);
            // Mirror the enqueue result into the synced set: accepted chunks won't be re-sent by
            // the catch-up path, dropped ones will be. Only meaningful for a full broadcast --
            // a filtered resend must not touch whether the chunk itself is considered synced.
            if (onlySectionYs == null) setSyncedState(player, dimension, pos, accepted);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        ResourceKey<Level> dimension = chunk.getLevel().dimension();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, null);

        if (sections.isEmpty()) {
            setSyncedState(player, dimension, pos, false);
            return;
        }

        if (gated(player, dimension)) {
            setSyncedState(player, dimension, pos, false);
            return;
        }

        boolean accepted = LodSendQueue.getInstance().enqueue(player, dimension,
            pos, minY, sections, chunk.getLevel().registryAccess());
        setSyncedState(player, dimension, pos, accepted);
    }

    /**
     * Main-thread half of the send path: take a private snapshot of everything the sender thread
     * will need, and nothing more.
     *
     * <p>Block states are {@code copy()}d rather than serialised here. The copy is a long[] clone,
     * whereas serialisation varint-encodes 4096 entries per section — moving that encoding to the
     * sender thread is the entire point. The copy is required because a live container is guarded
     * by a {@code ThreadingDetector} and may be mutated by this thread while the sender reads it.
     *
     * <p>Biomes and light stay inline: {@code getBiomes()} returns the read-only interface with no
     * {@code copy()}, and at 64 entries against block states' 4096 it is not worth depending on the
     * concrete type to move. Light layers must be read on the main thread regardless.
     *
     * <p>If {@code onlySectionYs} is non-null, sections outside that set are skipped entirely --
     * used by {@link ChunkUpdateTracker} to resend just the section a block edit touched.
     */
    private static List<LodSendQueue.PendingSection> snapshotSections(
            LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LodSendQueue.PendingSection> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();
        var registryAccess = chunk.getLevel().registryAccess();

        for (int i = 0; i < chunk.getSections().length; i++) {
            int sectionY = minY + i;
            if (onlySectionYs != null && !onlySectionYs.contains(sectionY)) continue;

            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;

            byte[] biomes;
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf biomesBuf =
                    new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), registryAccess);
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);
            } finally {
                biomesRaw.release();
            }

            SectionPos sectionPos = SectionPos.of(pos, minY + i);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

            sections.add(new LodSendQueue.PendingSection(
                minY + i,
                section.getStates().copy(),
                biomes,
                bl != null ? bl.getData().clone() : null,
                sl != null ? sl.getData().clone() : null
            ));
        }

        return sections;
    }

    public static void sendHandshake(ServerPlayer player) {
        ServerPlayNetworking.send(player, new HandshakePayload(true, PROTOCOL_VERSION));
    }
}
