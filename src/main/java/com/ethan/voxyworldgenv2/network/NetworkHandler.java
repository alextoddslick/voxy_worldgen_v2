package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
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
     * Bumped whenever the payload wire format changes. The client refuses to upload its known-chunk
     * set unless the server advertises at least 2, because sending a payload a server has not
     * registered can drop the connection. Protocol 5 adds the handshake ack, server-config
     * push/pull, storage report and settings sync payloads. Every feature gate derived from this is
     * a floor ({@code >=}), never equality, so a newer peer keeps serving/accepting everything an
     * older one did.
     */
    public static final int PROTOCOL_VERSION = 5;

    // Keep individual packets well under the protocol ceiling to prevent connection resets on
    // public servers. The binding limit for a clientbound custom payload in 1.21.1 is
    // ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 1 MiB (the 2 MiB figure is the frame
    // length cap, which is a separate, larger limit) -- so this leaves 32x headroom.
    static final int MAX_PACKET_BYTES = 32_768;

    /** Op level required to edit server config or settings from a client packet. */
    private static boolean isOp(ServerPlayer player) {
        return player.permissions().hasPermission(
            new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(2)));
    }

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

    /** Client -> server ack carrying the client's protocol, so the server can gate features on a floor. */
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
    public record ServerConfigPayload(com.ethan.voxyworldgenv2.core.Config.ServerConfig config, boolean canEdit) implements CustomPacketPayload {
        public static final Type<ServerConfigPayload> TYPE = new Type<>(SERVER_CONFIG_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPayload> CODEC = CustomPacketPayload.codec(ServerConfigPayload::write, ServerConfigPayload::new);

        public ServerConfigPayload(FriendlyByteBuf buf) {
            this(readConfig(buf), buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            writeConfig(buf, config);
            buf.writeBoolean(canEdit);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client op pushes new values to apply; permission is re-checked server-side on receipt. */
    public record ServerConfigPushPayload(com.ethan.voxyworldgenv2.core.Config.ServerConfig config) implements CustomPacketPayload {
        public static final Type<ServerConfigPushPayload> TYPE = new Type<>(SERVER_CONFIG_PUSH_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPushPayload> CODEC = CustomPacketPayload.codec(ServerConfigPushPayload::write, ServerConfigPushPayload::new);

        public ServerConfigPushPayload(FriendlyByteBuf buf) {
            this(readConfig(buf));
        }

        public void write(FriendlyByteBuf buf) {
            writeConfig(buf, config);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static com.ethan.voxyworldgenv2.core.Config.ServerConfig readConfig(FriendlyByteBuf buf) {
        return new com.ethan.voxyworldgenv2.core.Config.ServerConfig(
            buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeConfig(FriendlyByteBuf buf, com.ethan.voxyworldgenv2.core.Config.ServerConfig c) {
        buf.writeBoolean(c.enabled());
        buf.writeVarInt(c.generationRadius());
        buf.writeVarInt(c.updateInterval());
        buf.writeVarInt(c.maxQueueSize());
        buf.writeVarInt(c.maxActiveTasks());
    }

    /**
     * Live settings + generation stats for the settings UI, sent on request and after every
     * applied update. Scoped to what this branch actually exposes via /voxygen and the settings
     * book -- unlike unified, this branch carries no per-player admin table (that lives with the
     * separate per-player-limits feature line, which this port does not carry) and no HUD toggles
     * in Config, so this is a smaller wire shape by design, not an incomplete copy.
     */
    public record SettingsSnapshotPayload(
        boolean isOp, boolean singleplayerActive,
        boolean enabled, int generationRadius, int maxActiveTasks, int maxChunksPerSecond,
        double maxMbpsPerPlayer,
        long bytesSent, long bytesPerSecond,
        int queued, int maxQueued, long chunksDone
    ) implements CustomPacketPayload {
        public static final Type<SettingsSnapshotPayload> TYPE = new Type<>(SETTINGS_SNAPSHOT_ID);
        public static final StreamCodec<FriendlyByteBuf, SettingsSnapshotPayload> CODEC =
            CustomPacketPayload.codec(SettingsSnapshotPayload::write, SettingsSnapshotPayload::new);

        public SettingsSnapshotPayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readDouble(),
                buf.readLong(), buf.readLong(),
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
     * The screen's edits going back: string-keyed ops applied with the same semantics and clamps
     * as the matching /voxygen subcommand. Bounded hard at read time -- the op check happens
     * later, so the codec itself must not let an unauthenticated packet allocate freely.
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

    /** Client -> server: how many bytes of LOD data this client's Voxy store holds on disk. */
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
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(KnownChunksPayload.TYPE, KnownChunksPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(HandshakeAckPayload.TYPE, HandshakeAckPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ServerConfigPushPayload.TYPE, ServerConfigPushPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StorageReportPayload.TYPE, StorageReportPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SettingsSnapshotPayload.TYPE, SettingsSnapshotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SettingsUpdatePayload.TYPE, SettingsUpdatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(KnownChunksPayload.TYPE,
            (payload, context) -> receiveKnownChunks(context.player(), payload));

        // Client protocol arrives here so features can be gated on a floor; the server's own
        // config and settings snapshot go straight back so a client that just acked never has to
        // ask separately for either.
        ServerPlayNetworking.registerGlobalReceiver(HandshakeAckPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            PlayerTracker.getInstance().setClientProtocol(player.getUUID(), payload.clientProtocol());
            if (payload.clientProtocol() != PROTOCOL_VERSION) {
                VoxyWorldGenV2.LOGGER.info("client {} speaks voxy protocol {} (ours={}), serving what it supports",
                    player.getName().getString(), payload.clientProtocol(), PROTOCOL_VERSION);
            }
            sendServerConfig(player);
            sendSettingsSnapshot(player);
        });

        ServerPlayNetworking.registerGlobalReceiver(ServerConfigPushPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isOp(player)) {
                VoxyWorldGenV2.LOGGER.warn("ignoring server config push from non-op {}", player.getName().getString());
                sendServerConfig(player); // snap their screen back to the real values
                return;
            }
            com.ethan.voxyworldgenv2.core.Config.applyServerConfig(payload.config());
            com.ethan.voxyworldgenv2.core.Config.save();
            com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            VoxyWorldGenV2.LOGGER.info("server config updated by op {}", player.getName().getString());
            broadcastServerConfig();
        });

        ServerPlayNetworking.registerGlobalReceiver(StorageReportPayload.TYPE,
            (payload, context) -> receiveStorageReport(context.player(), payload));

        ServerPlayNetworking.registerGlobalReceiver(SettingsUpdatePayload.TYPE,
            (payload, context) -> receiveSettingsUpdate(context.player(), payload));

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized (protocol {})", PROTOCOL_VERSION);
    }

    /** Sends the live server config to one player; canEdit reflects their own op status. */
    public static void sendServerConfig(ServerPlayer player) {
        ServerPlayNetworking.send(player, new ServerConfigPayload(
            com.ethan.voxyworldgenv2.core.Config.ServerConfig.snapshot(), isOp(player)));
    }

    public static void broadcastServerConfig() {
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            sendServerConfig(player);
        }
    }

    /**
     * Stores the client's disk-usage figure. A negative count is a malformed or hostile packet,
     * not a value, so it is dropped rather than clamped -- it must never render as "0 B" and read
     * as truth.
     */
    private static void receiveStorageReport(ServerPlayer player, StorageReportPayload payload) {
        if (payload.bytesOnDisk() < 0) return;
        PlayerTracker.getInstance().reportClientStoreBytes(player.getUUID(), payload.bytesOnDisk());
    }

    /**
     * Applies a settings-screen batch. The permission check happens HERE, per packet -- the screen
     * being open proves nothing, and a deopped player's stale screen must turn into a no-op, not
     * an edit. A fresh snapshot goes back either way so the screen re-renders truth.
     */
    private static void receiveSettingsUpdate(ServerPlayer player, SettingsUpdatePayload payload) {
        if (!isOp(player)) {
            VoxyWorldGenV2.LOGGER.warn("ignoring settings update from non-op {}", player.getName().getString());
            sendSettingsSnapshot(player);
            return;
        }

        boolean sp = com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().isSingleplayer();
        var data = com.ethan.voxyworldgenv2.core.Config.DATA;
        boolean spActive = sp && data.singleplayer != null && data.singleplayer.enableSingleplayerDefaults;

        int applied = 0;
        for (SettingsUpdatePayload.Op op : payload.ops()) {
            if (applySettingsOp(op, spActive)) applied++;
        }
        if (applied > 0) {
            com.ethan.voxyworldgenv2.core.Config.save();
            com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            VoxyWorldGenV2.LOGGER.info("{} applied {} settings change(s) via the settings screen",
                player.getName().getString(), applied);
        }
        sendSettingsSnapshot(player);
    }

    /** Same key set, semantics and clamps as the matching /voxygen subcommand. */
    private static boolean applySettingsOp(SettingsUpdatePayload.Op op, boolean spActive) {
        var data = com.ethan.voxyworldgenv2.core.Config.DATA;
        try {
            switch (op.key()) {
                case "radius" -> {
                    int v = clampInt(op.value(), 1, 512);
                    if (spActive) data.singleplayer.generationRadius = v; else data.generationRadius = v;
                }
                case "tasks" -> {
                    int v = clampInt(op.value(), 1, 128);
                    if (spActive) data.singleplayer.maxActiveTasks = v; else data.maxActiveTasks = v;
                }
                case "queue" -> data.maxQueueSize = clampInt(op.value(), 100, 1_000_000);
                case "enabled" -> data.enabled = Boolean.parseBoolean(op.value());
                case "ratelimit" -> {
                    double v = clampDouble(op.value(), 0.0, 1000.0);
                    if (spActive) data.singleplayer.maxMbpsPerPlayer = v; else data.maxMbpsPerPlayer = v;
                }
                case "genrate" -> {
                    int v = clampInt(op.value(), 0, 100_000);
                    if (spActive) data.singleplayer.maxChunksPerSecond = v; else data.maxChunksPerSecond = v;
                }
                case "loginterval" -> data.logProgressIntervalSeconds = clampInt(op.value(), 0, 3600);
                case "singleplayer" -> {
                    if (data.singleplayer == null) {
                        data.singleplayer = new com.ethan.voxyworldgenv2.core.Config.SingleplayerConfig();
                    }
                    data.singleplayer.enableSingleplayerDefaults = Boolean.parseBoolean(op.value());
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

    private static int clampInt(String value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, Integer.parseInt(value.trim())));
    }

    private static double clampDouble(String value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, Double.parseDouble(value.trim())));
    }

    /** Builds and sends the settings screen's live state. */
    public static void sendSettingsSnapshot(ServerPlayer player) {
        var mgr = com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance();
        var data = com.ethan.voxyworldgenv2.core.Config.DATA;
        var q = LodSendQueue.getInstance();
        boolean sp = mgr.isSingleplayer();
        boolean spActive = sp && data.singleplayer != null && data.singleplayer.enableSingleplayerDefaults;

        ServerPlayNetworking.send(player, new SettingsSnapshotPayload(
            isOp(player), spActive,
            data.enabled,
            com.ethan.voxyworldgenv2.core.Config.getGenerationRadius(sp),
            com.ethan.voxyworldgenv2.core.Config.getMaxActiveTasks(sp),
            com.ethan.voxyworldgenv2.core.Config.getMaxChunksPerSecond(sp),
            com.ethan.voxyworldgenv2.core.Config.getMaxMbpsPerPlayer(sp),
            q.getBytesSent(), q.getCurrentBytesPerSecond(),
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
            store.markSynced(dim, pos.pack());
        } else {
            store.markUnsynced(dim, pos.pack());
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

    /** Distance beyond which a chunk is not worth streaming as LOD data to a player. */
    public static final double LOD_SYNC_MAX_DIST_SQ = 4096.0 * 4096.0;

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    /**
     * Broadcasts a chunk's LOD data. When {@code onlySectionYs} is non-null (a block-edit resend
     * of just the section(s) that changed, see {@link ChunkUpdateTracker}), the synced set is left
     * untouched: a partial resend is neither a fresh delivery nor a loss of what was already
     * delivered, so marking it either way would corrupt the "claimed but not delivered" invariant
     * that the catch-up path depends on. Only a full sync (the {@code null} overload) may mark or
     * unmark synced state.
     */
    public static void broadcastLODData(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        boolean fullSync = onlySectionYs == null;
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, onlySectionYs);

        var dimension = chunk.getLevel().dimension();

        if (sections.isEmpty()) {
            if (fullSync) {
                for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
                    setSyncedState(player, dimension, pos, false);
                }
            }
            return;
        }

        var registryAccess = chunk.getLevel().registryAccess();

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > LOD_SYNC_MAX_DIST_SQ)) {
                if (fullSync) setSyncedState(player, dimension, pos, false);
                continue;
            }

            if (gated(player, dimension)) {
                if (fullSync) setSyncedState(player, dimension, pos, false);
                continue;
            }

            boolean accepted = LodSendQueue.getInstance().enqueue(
                player, dimension, pos, minY, sections, registryAccess);
            // Mirror the enqueue result into the synced set: accepted chunks won't be re-sent by
            // the catch-up path, dropped ones will be. Only meaningful for a full sync.
            if (fullSync) setSyncedState(player, dimension, pos, accepted);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        ResourceKey<Level> dimension = chunk.getLevel().dimension();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk);

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
     */
    private static List<LodSendQueue.PendingSection> snapshotSections(LevelChunk chunk) {
        return snapshotSections(chunk, null);
    }

    /** {@code onlySectionYs} null = every non-air section; otherwise only the y-levels named. */
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
