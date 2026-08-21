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
import net.minecraft.resources.ResourceLocation;
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
    public static final ResourceLocation HANDSHAKE_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":handshake");
    public static final ResourceLocation LOD_DATA_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":lod_data");
    public static final ResourceLocation KNOWN_CHUNKS_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":known_chunks");
    public static final ResourceLocation STORAGE_REPORT_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":storage_report");
    public static final ResourceLocation HANDSHAKE_ACK_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":handshake_ack");
    public static final ResourceLocation SETTINGS_SNAPSHOT_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":settings_snapshot");
    public static final ResourceLocation SETTINGS_UPDATE_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":settings_update");
    public static final ResourceLocation SERVER_CONFIG_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":server_config");
    public static final ResourceLocation SERVER_CONFIG_PUSH_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":server_config_push");

    /**
     * Bumped whenever a payload is added or its wire format changes. Serverbound payloads are
     * gated on the version the server advertises (known-chunks needs ≥2, the storage report ≥3,
     * the settings screen ≥4, the server-config push ≥5), because sending a payload a server has
     * not registered can drop the connection. Gates are always floors (>=), never equality: a
     * newer server must keep accepting everything an older one did.
     */
    public static final int PROTOCOL_VERSION = 5;

    // op level required to edit server config from the client. 1.21.1 has no
    // src.permissions().hasPermission(...) (that is a 26.2-only API); hasPermissions(level) is
    // the equivalent used everywhere else on this branch (see receiveSettingsUpdate).
    private static boolean canEditConfig(ServerPlayer player) {
        return player.hasPermissions(2);
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

    /** Raw (pre-compression) section bytes written; server-side only. */
    public static final java.util.concurrent.atomic.AtomicLong RAW_SECTION_BYTES = new java.util.concurrent.atomic.AtomicLong();
    /** Deflated section bytes actually put on the wire; server-side only. */
    public static final java.util.concurrent.atomic.AtomicLong WIRE_SECTION_BYTES = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Carries one chunk's section batch, deflated as a single unit. Terrain data is highly
     * repetitive (palettes, runs of the same state, near-identical light arrays), so a whole-batch
     * zlib window typically shrinks it 3-6x — measured via RAW/WIRE_SECTION_BYTES.
     *
     * <p>Compression happens ONCE, in {@link #of} on the sender thread, not at netty encode time.
     * The payload then knows its own network size before it is sent, which is what lets the send
     * queue throttle and account per player in real wire bytes rather than raw bytes. The wire
     * format is byte-identical to when the Deflater ran inside {@code write}, so this carries no
     * protocol bump. Section bytes need no registry context (they are already-serialised arrays),
     * which is why the codec runs on plain {@link FriendlyByteBuf}.
     */
    public record LODDataPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                                 int plainLength, byte[] body) implements CustomPacketPayload {
        public static final Type<LODDataPayload> TYPE = new Type<>(LOD_DATA_ID);
        public static final StreamCodec<FriendlyByteBuf, LODDataPayload> CODEC = CustomPacketPayload.codec(LODDataPayload::write, LODDataPayload::new);

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(FriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }

            public static SectionData read(FriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(),
                    buf.readByteArray(),
                    buf.readByteArray(),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())
                );
            }
        }

        /** Serialises and deflates the batch on the calling (sender) thread. */
        public static LODDataPayload of(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                                        List<SectionData> sections) {
            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
            byte[] plain;
            try {
                FriendlyByteBuf inner = new FriendlyByteBuf(raw);
                inner.writeCollection(sections, (b, s) -> s.write((FriendlyByteBuf) b));
                plain = new byte[inner.readableBytes()];
                inner.readBytes(plain);
            } finally {
                raw.release();
            }

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

            byte[] body = (packed > 0 && packed < plain.length)
                ? java.util.Arrays.copyOf(out, packed)
                : plain;
            RAW_SECTION_BYTES.addAndGet(plain.length);
            WIRE_SECTION_BYTES.addAndGet(body.length);
            return new LODDataPayload(dimension, pos, minY, plain.length, body);
        }

        public LODDataPayload(FriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readVarInt(),
                buf.readByteArray()
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(dimension.location().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            buf.writeVarInt(plainLength);
            buf.writeByteArray(body);
        }

        /** The section body's network size; the header alongside it is a few dozen bytes. */
        public int wireSize() {
            return body.length;
        }

        /** Inflates and parses the batch; the client calls this once on receipt. */
        public List<SectionData> decodeSections() {
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
                FriendlyByteBuf inner = new FriendlyByteBuf(raw);
                return inner.readCollection(ArrayList::new, b -> SectionData.read((FriendlyByteBuf) b));
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
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf())),
                buf.readBoolean(),
                buf.readByteArray(MAX_PACKET_BYTES)
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(dimension.location().toString());
            buf.writeBoolean(last);
            buf.writeByteArray(body);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The client's report of how much disk Voxy's store for this world occupies. Purely
     * informational (surfaces in the tab HUD and /voxygen traffic); a lying client can only
     * misreport its own line.
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
     * The client's reply to the handshake: "I run protocol N too." What the server learns from it
     * is which UI it may offer — a client at ≥4 gets the settings screen from /voxygen settings, a
     * silent client keeps the written-book fallback forever.
     */
    public record HandshakeAckPayload(int clientProtocol) implements CustomPacketPayload {
        public static final Type<HandshakeAckPayload> TYPE = new Type<>(HANDSHAKE_ACK_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakeAckPayload> CODEC =
            CustomPacketPayload.codec(HandshakeAckPayload::write, HandshakeAckPayload::new);

        public HandshakeAckPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(clientProtocol);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server -> client: the live server values (the subset {@link com.ethan.voxyworldgenv2.core.Config.ServerConfig}
     * covers) plus whether this client may edit them. Sent right after the handshake ack, and again
     * after any accepted {@link ServerConfigPushPayload}, so a client-side screen never has to guess.
     */
    public record ServerConfigPayload(com.ethan.voxyworldgenv2.core.Config.ServerConfig config,
                                      boolean canEdit) implements CustomPacketPayload {
        public static final Type<ServerConfigPayload> TYPE = new Type<>(SERVER_CONFIG_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPayload> CODEC =
            CustomPacketPayload.codec(ServerConfigPayload::write, ServerConfigPayload::new);

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

    /**
     * Client -> server: an op pushes edited values from the ModMenu/Cloth screen. The permission
     * check happens on receipt, not client-side, for the same reason {@code receiveSettingsUpdate}
     * re-checks: the screen being reachable proves nothing about the sender's current rank.
     */
    public record ServerConfigPushPayload(com.ethan.voxyworldgenv2.core.Config.ServerConfig config)
        implements CustomPacketPayload {
        public static final Type<ServerConfigPushPayload> TYPE = new Type<>(SERVER_CONFIG_PUSH_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPushPayload> CODEC =
            CustomPacketPayload.codec(ServerConfigPushPayload::write, ServerConfigPushPayload::new);

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

    private static com.ethan.voxyworldgenv2.core.Config.ServerConfig readServerConfig(FriendlyByteBuf buf) {
        return new com.ethan.voxyworldgenv2.core.Config.ServerConfig(
            buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeServerConfig(FriendlyByteBuf buf, com.ethan.voxyworldgenv2.core.Config.ServerConfig c) {
        buf.writeBoolean(c.enabled());
        buf.writeVarInt(c.generationRadius());
        buf.writeVarInt(c.updateInterval());
        buf.writeVarInt(c.maxQueueSize());
        buf.writeVarInt(c.maxActiveTasks());
    }

    /**
     * Everything the settings screen renders, in one S2C payload: the editable values (already
     * resolved to the profile the commands would edit), the HUD toggles, a live-stats strip, and —
     * for ops only — the player table. Sent on /voxygen settings and again after every applied
     * update, so the screen never has to guess at server state.
     */
    public record SettingsSnapshotPayload(
        boolean isOp, boolean singleplayerActive,
        boolean enabled, int generationRadius, int maxActiveTasks, int maxChunksPerSecond,
        double maxMbpsPerPlayer, int lodSendDistanceChunks,
        boolean hudCompressed, boolean hudSavings, boolean hudClientDisk,
        long wireBytesSent, long wireBps, int zipRatioX10,
        int queued, int maxQueued, long chunksDone,
        List<PlayerRow> players
    ) implements CustomPacketPayload {
        public static final Type<SettingsSnapshotPayload> TYPE = new Type<>(SETTINGS_SNAPSHOT_ID);
        public static final StreamCodec<FriendlyByteBuf, SettingsSnapshotPayload> CODEC =
            CustomPacketPayload.codec(SettingsSnapshotPayload::write, SettingsSnapshotPayload::new);

        /** One admin-table row. diskBytes -1 = never reported; hasCap/hasDist mark overrides. */
        public record PlayerRow(java.util.UUID uuid, String name, boolean online, long lastSeenMs,
                                long wireBytes, long diskBytes,
                                boolean hasCap, double capMbps,
                                boolean hasDist, int distChunks) {
            void write(FriendlyByteBuf buf) {
                buf.writeUUID(uuid);
                buf.writeUtf(name, 64);
                buf.writeBoolean(online);
                buf.writeLong(lastSeenMs);
                buf.writeLong(wireBytes);
                buf.writeLong(diskBytes);
                buf.writeBoolean(hasCap);
                buf.writeDouble(capMbps);
                buf.writeBoolean(hasDist);
                buf.writeVarInt(distChunks);
            }

            static PlayerRow read(FriendlyByteBuf buf) {
                return new PlayerRow(buf.readUUID(), buf.readUtf(64), buf.readBoolean(),
                    buf.readLong(), buf.readLong(), buf.readLong(),
                    buf.readBoolean(), buf.readDouble(), buf.readBoolean(), buf.readVarInt());
            }
        }

        public SettingsSnapshotPayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readDouble(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readLong(), buf.readLong(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readLong(),
                buf.readCollection(ArrayList::new, PlayerRow::read));
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(isOp);
            buf.writeBoolean(singleplayerActive);
            buf.writeBoolean(enabled);
            buf.writeVarInt(generationRadius);
            buf.writeVarInt(maxActiveTasks);
            buf.writeVarInt(maxChunksPerSecond);
            buf.writeDouble(maxMbpsPerPlayer);
            buf.writeVarInt(lodSendDistanceChunks);
            buf.writeBoolean(hudCompressed);
            buf.writeBoolean(hudSavings);
            buf.writeBoolean(hudClientDisk);
            buf.writeLong(wireBytesSent);
            buf.writeLong(wireBps);
            buf.writeVarInt(zipRatioX10);
            buf.writeVarInt(queued);
            buf.writeVarInt(maxQueued);
            buf.writeLong(chunksDone);
            buf.writeCollection(players, (b, r) -> r.write((FriendlyByteBuf) b));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The screen's edits going back: string-keyed ops that {@code SettingsApplier} gives the same
     * semantics and clamps as the /voxygen commands. Bounded hard at read time — the op check
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

    public static void init() {
        PayloadTypeRegistry.playC2S().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakePayload.TYPE, HandshakePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(KnownChunksPayload.TYPE, KnownChunksPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StorageReportPayload.TYPE, StorageReportPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HandshakeAckPayload.TYPE, HandshakeAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SettingsSnapshotPayload.TYPE, SettingsSnapshotPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SettingsUpdatePayload.TYPE, SettingsUpdatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerConfigPushPayload.TYPE, ServerConfigPushPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(KnownChunksPayload.TYPE,
            (payload, context) -> receiveKnownChunks(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(StorageReportPayload.TYPE,
            (payload, context) -> receiveStorageReport(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(HandshakeAckPayload.TYPE,
            (payload, context) -> {
                // Record rather than compare: a mismatch is not a reason to send nothing, features
                // are gated on floors, so an older client keeps receiving terrain and is simply not
                // offered payloads its build cannot parse. setClientProtocol must run before
                // sendServerConfig, which reads the recorded value indirectly through canEditConfig.
                PlayerTracker.getInstance().setClientProtocol(context.player().getUUID(), payload.clientProtocol());
                sendServerConfig(context.player());
            });
        ServerPlayNetworking.registerGlobalReceiver(SettingsUpdatePayload.TYPE,
            (payload, context) -> receiveSettingsUpdate(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ServerConfigPushPayload.TYPE,
            (payload, context) -> receiveServerConfigPush(context.player(), payload));

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized (protocol {})", PROTOCOL_VERSION);
    }

    /**
     * Applies an operator's server-config push. Mirrors {@code receiveSettingsUpdate}'s per-packet
     * permission re-check: the client having sent the packet at all proves nothing about its
     * current rank, and a deopped player's stale screen must become a no-op, not an edit.
     */
    private static void receiveServerConfigPush(ServerPlayer player, ServerConfigPushPayload payload) {
        if (!canEditConfig(player)) {
            VoxyWorldGenV2.LOGGER.warn("ignoring server config push from non-op {}", player.getName().getString());
            sendServerConfig(player); // snap their screen back to the real values
            return;
        }
        com.ethan.voxyworldgenv2.core.Config.applyServerConfig(payload.config());
        com.ethan.voxyworldgenv2.core.Config.save();
        com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
        VoxyWorldGenV2.LOGGER.info("server config updated by op {}", player.getName().getString());
        broadcastServerConfig();
    }

    /** Sends the live server config to one player; {@code canEdit} reflects their op status. */
    public static void sendServerConfig(ServerPlayer player) {
        ServerPlayNetworking.send(player, new ServerConfigPayload(
            com.ethan.voxyworldgenv2.core.Config.ServerConfig.snapshot(), canEditConfig(player)));
    }

    public static void broadcastServerConfig() {
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            sendServerConfig(player);
        }
    }

    /**
     * Applies a settings-screen batch. The permission check happens HERE, per packet — the screen
     * being open proves nothing, and a deopped player's stale screen must turn into a no-op, not
     * an edit. A fresh snapshot goes back either way so the screen re-renders truth.
     */
    private static void receiveSettingsUpdate(ServerPlayer player, SettingsUpdatePayload payload) {
        if (!player.hasPermissions(2)) {
            VoxyWorldGenV2.LOGGER.warn("ignoring settings update from non-op {}", player.getName().getString());
            sendSettingsSnapshot(player);
            return;
        }

        boolean spActive = com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().isSingleplayer()
            && com.ethan.voxyworldgenv2.core.Config.DATA.singleplayer != null
            && com.ethan.voxyworldgenv2.core.Config.DATA.singleplayer.enableSingleplayerDefaults;

        var ops = payload.ops().stream()
            .map(op -> new com.ethan.voxyworldgenv2.core.SettingsApplier.Op(op.key(), op.value()))
            .toList();
        int applied = com.ethan.voxyworldgenv2.core.SettingsApplier.apply(ops, spActive);
        if (applied > 0) {
            com.ethan.voxyworldgenv2.core.Config.save();
            com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            VoxyWorldGenV2.LOGGER.info("{} applied {} settings change(s) via the settings screen",
                player.getName().getString(), applied);
        }
        sendSettingsSnapshot(player);
    }

    /** Builds and sends the settings screen's state; the player table only goes to ops. */
    public static void sendSettingsSnapshot(ServerPlayer player) {
        var mgr = com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance();
        var c = com.ethan.voxyworldgenv2.core.Config.DATA;
        var q = LodSendQueue.getInstance();
        boolean sp = mgr.isSingleplayer();
        boolean spActive = sp && c.singleplayer != null && c.singleplayer.enableSingleplayerDefaults;
        boolean isOp = player.hasPermissions(2);

        long raw = RAW_SECTION_BYTES.get();
        long wire = WIRE_SECTION_BYTES.get();
        int zipX10 = (raw > 0 && wire > 0) ? (int) Math.round(raw * 10.0 / wire) : 0;

        ServerPlayNetworking.send(player, new SettingsSnapshotPayload(
            isOp, spActive,
            c.enabled,
            com.ethan.voxyworldgenv2.core.Config.getGenerationRadius(sp),
            com.ethan.voxyworldgenv2.core.Config.getMaxActiveTasks(sp),
            com.ethan.voxyworldgenv2.core.Config.getMaxChunksPerSecond(sp),
            com.ethan.voxyworldgenv2.core.Config.getMaxMbpsPerPlayer(sp),
            com.ethan.voxyworldgenv2.core.Config.getSendDistanceChunks(sp),
            c.hudShowCompressed, c.hudShowSavings, c.hudShowClientDisk,
            q.getWireBytesSent(), q.getCurrentWireBytesPerSecond(), zipX10,
            q.getQueuedJobs(), q.getMaxQueuedJobs(),
            mgr.getStats().getCompleted(),
            isOp ? buildPlayerRows() : List.of()));
    }

    /**
     * The admin table: every player the history knows, refreshed from live counters first so
     * online rows are current. Sorted online-first then most-recently-seen; capped well under the
     * payload ceiling.
     */
    private static List<SettingsSnapshotPayload.PlayerRow> buildPlayerRows() {
        var tracker = PlayerTracker.getInstance();
        var history = com.ethan.voxyworldgenv2.core.PlayerHistory.getInstance();
        var q = LodSendQueue.getInstance();
        long now = System.currentTimeMillis();

        // Flush-on-read: fold each online player's live counters into the history so the table
        // reads uniformly from one source.
        var wireByPlayer = q.getPerPlayerWireBytes();
        java.util.Set<String> online = new java.util.HashSet<>();
        for (ServerPlayer p : tracker.getPlayers()) {
            online.add(p.getUUID().toString());
            history.recordSession(p.getUUID(), p.getName().getString(),
                wireByPlayer.getOrDefault(p.getUUID(), 0L),
                tracker.getClientStoreBytes(p.getUUID()), now);
        }

        var c = com.ethan.voxyworldgenv2.core.Config.DATA;
        List<SettingsSnapshotPayload.PlayerRow> rows = new ArrayList<>();
        for (var entry : history.entries().entrySet()) {
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue; // a hand-edited history line must not break the screen
            }
            var h = entry.getValue();
            Double cap = c.playerRateLimits != null ? c.playerRateLimits.get(entry.getKey()) : null;
            Integer dist = c.playerSendDistances != null ? c.playerSendDistances.get(entry.getKey()) : null;
            rows.add(new SettingsSnapshotPayload.PlayerRow(
                uuid, h.name, online.contains(entry.getKey()), h.lastSeenMs,
                h.wireBytes, h.diskBytes,
                cap != null, cap != null ? cap : 0.0,
                dist != null, dist != null ? dist : 0));
        }
        rows.sort(java.util.Comparator
            .comparing((SettingsSnapshotPayload.PlayerRow r) -> !r.online())
            .thenComparing(SettingsSnapshotPayload.PlayerRow::lastSeenMs, java.util.Comparator.reverseOrder()));
        return rows.size() > 100 ? rows.subList(0, 100) : rows;
    }

    /**
     * Stores the client's disk-usage figure. A negative count is a malformed or hostile packet,
     * not a value; it is dropped rather than clamped so it can never render as "0 B" and read as
     * truth. Arrival rate is bounded in PlayerTracker because the packet is otherwise free to
     * spam — the cost of a false rejection is one stale HUD line for a minute.
     */
    private static void receiveStorageReport(ServerPlayer player, StorageReportPayload payload) {
        if (payload.bytesOnDisk() < 0) return;
        PlayerTracker.getInstance().reportClientStoreBytes(player.getUUID(), payload.bytesOnDisk());
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
                player.getName().getString(), payload.dimension().location(),
                player.level().dimension().location());
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

    /**
     * True when a chunk {@code (dxBlocks, dzBlocks)} away from the player is inside their LOD send
     * distance. 0 chunks = unlimited (the config sentinel).
     */
    public static boolean withinSendDistance(double dxBlocks, double dzBlocks, int sendDistanceChunks) {
        if (sendDistanceChunks <= 0) return true;
        double maxDist = sendDistanceChunks * 16.0;
        return dxBlocks * dxBlocks + dzBlocks * dzBlocks <= maxDist * maxDist;
    }

    /**
     * Bounds a catch-up/refresh sweep radius by the send distance: chunks the broadcast path would
     * refuse must not be claimable by the sweep either, or they'd be marked synced and never sent.
     */
    public static int capCatchUpRadius(int desiredRadius, int sendDistanceChunks) {
        if (sendDistanceChunks <= 0) return desiredRadius;
        return Math.min(desiredRadius, sendDistanceChunks);
    }

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    /**
     * Same as {@link #broadcastLODData(LevelChunk)}, but when {@code sectionYs} is non-null only
     * those sections are sent rather than the whole chunk. Used by {@link com.ethan.voxyworldgenv2.core.ChunkUpdateTracker}
     * so a handful of block edits resend a handful of sections, not every non-air section in the
     * chunk. Safe to send a subset: the client ingests {@code LODDataPayload.SectionData} entries
     * one section at a time (see {@code NetworkClientHandler.handleLODData}), so a partial payload
     * merges into what Voxy already holds rather than replacing it.
     */
    public static void broadcastLODData(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet sectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, sectionYs);

        if (sections.isEmpty()) return;

        boolean sp = com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().isSingleplayer();
        var registryAccess = chunk.getLevel().registryAccess();
        var dimension = chunk.getLevel().dimension();

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());
            int sendDistance = com.ethan.voxyworldgenv2.core.Config
                .getSendDistanceForPlayer(player.getUUID(), sp);

            if (player.level() != chunk.getLevel() || !withinSendDistance(dx, dz, sendDistance)) {
                setSyncedState(player, dimension, pos, false);
                continue;
            }

            if (gated(player, dimension)) {
                setSyncedState(player, dimension, pos, false);
                continue;
            }

            boolean accepted = LodSendQueue.getInstance().enqueue(
                player, dimension, pos, minY, sections, registryAccess);
            // Mirror the enqueue result into the synced set: accepted chunks won't be re-sent by
            // the catch-up path, dropped ones will be.
            setSyncedState(player, dimension, pos, accepted);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
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
     */
    private static List<LodSendQueue.PendingSection> snapshotSections(
            LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet sectionYFilter) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSendQueue.PendingSection> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();
        var registryAccess = chunk.getLevel().registryAccess();

        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (sectionYFilter != null && !sectionYFilter.contains(minY + i)) continue;

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
