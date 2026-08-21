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
    public static final Identifier KNOWN_CHUNKS_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":known_chunks");
    public static final Identifier SERVER_CONFIG_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":server_config");
    public static final Identifier SERVER_CONFIG_PUSH_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":server_config_push");
    public static final Identifier STORAGE_REPORT_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":storage_report");
    public static final Identifier SETTINGS_SNAPSHOT_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":settings_snapshot");
    public static final Identifier SETTINGS_UPDATE_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":settings_update");

    /**
     * Bumped whenever the payload wire format changes. The client refuses to upload its known-chunk
     * set unless the server advertises at least 2, because sending a payload a server has not
     * registered can drop the connection.
     *
     * <p>Protocol 5 adds the handshake ack, server-config sync/push, settings snapshot/update and
     * storage-report payloads (ported down from unified alongside spawn pre-generation). Every gate
     * on these, existing or new, is written as a floor ({@code >=}), never equality -- a future
     * protocol 6+ peer must keep passing every gate a protocol-5 peer passes today.
     */
    public static final int PROTOCOL_VERSION = 5;

    // Keep individual packets well under the protocol ceiling to prevent connection resets on
    // public servers. The binding limit for a clientbound custom payload in 1.21.1 is
    // ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 1 MiB (the 2 MiB figure is the frame
    // length cap, which is a separate, larger limit) -- so this leaves 32x headroom.
    static final int MAX_PACKET_BYTES = 32_768;

    /** Op level required to edit server config / settings from the client. */
    private static final int PERMISSION_OP = 2;

    private static boolean canEditConfig(ServerPlayer player) {
        return player.permissions()
            .hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(PERMISSION_OP)));
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

    /** Client -> server ack carrying the client's own protocol, so the server can gate protocol-5+
     *  clientbound payloads on a floor rather than assuming the handshake's version is symmetric. */
    public record HandshakeAckPayload(int clientProtocol) implements CustomPacketPayload {
        public static final Type<HandshakeAckPayload> TYPE = new Type<>(HANDSHAKE_ACK_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakeAckPayload> CODEC =
            CustomPacketPayload.codec(HandshakeAckPayload::write, HandshakeAckPayload::new);

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

    /** Server -> client: the live server config, plus whether this client may edit it. */
    public record ServerConfigPayload(Config.ServerConfig config, boolean canEdit) implements CustomPacketPayload {
        public static final Type<ServerConfigPayload> TYPE = new Type<>(SERVER_CONFIG_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPayload> CODEC =
            CustomPacketPayload.codec(ServerConfigPayload::write, ServerConfigPayload::new);

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

    /** Client (op only) -> server: push new config values to apply. */
    public record ServerConfigPushPayload(Config.ServerConfig config) implements CustomPacketPayload {
        public static final Type<ServerConfigPushPayload> TYPE = new Type<>(SERVER_CONFIG_PUSH_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPushPayload> CODEC =
            CustomPacketPayload.codec(ServerConfigPushPayload::write, ServerConfigPushPayload::new);

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

    private static Config.ServerConfig readConfig(FriendlyByteBuf buf) {
        return new Config.ServerConfig(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeConfig(FriendlyByteBuf buf, Config.ServerConfig c) {
        buf.writeBoolean(c.enabled());
        buf.writeVarInt(c.generationRadius());
        buf.writeVarInt(c.updateInterval());
        buf.writeVarInt(c.maxQueueSize());
        buf.writeVarInt(c.maxActiveTasks());
    }

    /** Client -> server: how many bytes of Voxy's on-disk store this client holds for this world. */
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
     * Server -> client: the settings a client-side screen would need to render, in one payload.
     * Scoped to what THIS branch's {@code Config.ConfigData} actually exposes -- unlike unified's
     * version there is no per-player rate/send-distance override table, because this branch does
     * not carry the per-player-limits feature (that only landed on {@code backport/1.21.1}).
     */
    public record SettingsSnapshotPayload(
        boolean isOp, boolean singleplayerActive,
        boolean enabled, int generationRadius, int maxActiveTasks, int maxChunksPerSecond,
        double maxMbpsPerPlayer, boolean spawnPregenEnabled, int spawnPregenRadius,
        long wireBytesSent, long wireBps, int queued, int maxQueued, long chunksDone
    ) implements CustomPacketPayload {
        public static final Type<SettingsSnapshotPayload> TYPE = new Type<>(SETTINGS_SNAPSHOT_ID);
        public static final StreamCodec<FriendlyByteBuf, SettingsSnapshotPayload> CODEC =
            CustomPacketPayload.codec(SettingsSnapshotPayload::write, SettingsSnapshotPayload::new);

        public SettingsSnapshotPayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readDouble(), buf.readBoolean(), buf.readVarInt(),
                buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readLong());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(isOp);
            buf.writeBoolean(singleplayerActive);
            buf.writeBoolean(enabled);
            buf.writeVarInt(generationRadius);
            buf.writeVarInt(maxActiveTasks);
            buf.writeVarInt(maxChunksPerSecond);
            buf.writeDouble(maxMbpsPerPlayer);
            buf.writeBoolean(spawnPregenEnabled);
            buf.writeVarInt(spawnPregenRadius);
            buf.writeLong(wireBytesSent);
            buf.writeLong(wireBps);
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
     * Client (op only) -> server: string-keyed settings edits. Bounded hard at read time -- the op
     * check happens later, so the codec itself must not let an unauthenticated packet allocate
     * freely.
     */
    public record SettingsUpdatePayload(List<Op> ops) implements CustomPacketPayload {
        public static final Type<SettingsUpdatePayload> TYPE = new Type<>(SETTINGS_UPDATE_ID);
        public static final StreamCodec<FriendlyByteBuf, SettingsUpdatePayload> CODEC =
            CustomPacketPayload.codec(SettingsUpdatePayload::write, SettingsUpdatePayload::new);

        public static final int MAX_OPS = 32;

        public record Op(String key, String value) {
            void write(FriendlyByteBuf buf) {
                buf.writeUtf(key, 32);
                buf.writeUtf(value, 32);
            }

            static Op read(FriendlyByteBuf buf) {
                return new Op(buf.readUtf(32), buf.readUtf(32));
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
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakeAckPayload.TYPE, HandshakeAckPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(KnownChunksPayload.TYPE, KnownChunksPayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ServerConfigPushPayload.TYPE, ServerConfigPushPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StorageReportPayload.TYPE, StorageReportPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SettingsSnapshotPayload.TYPE, SettingsSnapshotPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SettingsUpdatePayload.TYPE, SettingsUpdatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(KnownChunksPayload.TYPE,
            (payload, context) -> receiveKnownChunks(context.player(), payload));

        ServerPlayNetworking.registerGlobalReceiver(HandshakeAckPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                // Record rather than compare. A mismatch is not a reason to send nothing: every
                // protocol-5 feature is gated on a floor, so an older client keeps receiving
                // terrain and is simply not offered payloads its build cannot parse. This must run
                // before sendServerConfig/sendSettingsSnapshot, both of which are gated on it.
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

        ServerPlayNetworking.registerGlobalReceiver(SettingsUpdatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                if (!canEditConfig(player)) {
                    VoxyWorldGenV2.LOGGER.warn("ignoring settings update from non-op {}", player.getName().getString());
                    sendSettingsSnapshot(player);
                    return;
                }
                applySettingsUpdate(payload);
                Config.save();
                ChunkGenerationManager.getInstance().scheduleConfigReload();
                sendSettingsSnapshot(player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StorageReportPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() ->
                PlayerTracker.getInstance().setDiskBytes(player.getUUID(), payload.bytesOnDisk()));
        });

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized (protocol {})", PROTOCOL_VERSION);
    }

    /**
     * Applies one settings-update batch. Every value is clamped exactly as the equivalent
     * {@code /voxygen} command clamps it, because the sender is a client: a value outside these
     * bounds means a hand-crafted packet, not a UI mistake. Unknown keys are ignored rather than
     * rejecting the whole batch, so a newer client talking to this server degrades gracefully.
     */
    private static void applySettingsUpdate(SettingsUpdatePayload payload) {
        for (SettingsUpdatePayload.Op op : payload.ops()) {
            try {
                switch (op.key()) {
                    case "enabled" -> Config.DATA.enabled = Boolean.parseBoolean(op.value());
                    case "generationRadius" -> Config.DATA.generationRadius =
                        clampInt(Integer.parseInt(op.value()), 1, 512);
                    case "maxActiveTasks" -> Config.DATA.maxActiveTasks =
                        clampInt(Integer.parseInt(op.value()), 1, 128);
                    case "maxChunksPerSecond" -> Config.DATA.maxChunksPerSecond =
                        Math.max(0, Integer.parseInt(op.value()));
                    case "maxMbpsPerPlayer" -> Config.DATA.maxMbpsPerPlayer =
                        Math.max(0.0, Double.parseDouble(op.value()));
                    case "spawnPregenEnabled" -> Config.DATA.spawnPregenEnabled = Boolean.parseBoolean(op.value());
                    case "spawnPregenRadius" -> Config.DATA.spawnPregenRadius =
                        Math.max(1, Integer.parseInt(op.value()));
                    default -> VoxyWorldGenV2.LOGGER.debug("ignoring unknown settings-update key: {}", op.key());
                }
            } catch (NumberFormatException e) {
                VoxyWorldGenV2.LOGGER.warn("ignoring malformed settings-update value for {}: {}", op.key(), op.value());
            }
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Sends the live server config to one player; {@code canEdit} reflects their own op status. */
    public static void sendServerConfig(ServerPlayer player) {
        if (PlayerTracker.getInstance().getClientProtocol(player.getUUID()) < 5) return;
        boolean canEdit = canEditConfig(player);
        ServerPlayNetworking.send(player, new ServerConfigPayload(Config.ServerConfig.snapshot(), canEdit));
    }

    public static void broadcastServerConfig() {
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            sendServerConfig(player);
        }
    }

    /** Sends one player everything a settings screen would need to render, in one payload. */
    public static void sendSettingsSnapshot(ServerPlayer player) {
        if (PlayerTracker.getInstance().getClientProtocol(player.getUUID()) < 5) return;

        var mgr = ChunkGenerationManager.getInstance();
        var q = LodSendQueue.getInstance();
        boolean sp = mgr.isSingleplayer();

        ServerPlayNetworking.send(player, new SettingsSnapshotPayload(
            canEditConfig(player), sp,
            Config.DATA.enabled, Config.getGenerationRadius(sp), Config.getMaxActiveTasks(sp),
            Config.getMaxChunksPerSecond(sp), Config.getMaxMbpsPerPlayer(sp),
            Config.DATA.spawnPregenEnabled, Config.DATA.spawnPregenRadius,
            q.getBytesSent(), q.getCurrentBytesPerSecond(),
            q.getQueuedJobs(), q.getMaxQueuedJobs(), mgr.getStats().getCompleted()));
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

    /**
     * The LOD sync radius shared by {@link #broadcastLODData}'s own per-player range check and
     * {@code ChunkUpdateTracker}'s proximity pre-filter (the per-section dirty tracker flowed down
     * from unified as part of protocol-5 convergence). Exposed as a method, rather than
     * hand-copying the constant, so the two can never quietly disagree.
     */
    private static final double MAX_SYNC_DIST_BLOCKS = 4096.0;

    public static double syncRadiusSq() {
        return MAX_SYNC_DIST_BLOCKS * MAX_SYNC_DIST_BLOCKS;
    }

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    /**
     * If {@code onlySectionYs} is non-null, only those section y-levels are re-serialised and sent
     * (used by {@code ChunkUpdateTracker} for a block-edit resend) and the synced set is left
     * untouched -- a partial resend refreshes data for a chunk the recipient already has fully
     * claimed, so touching claim/deliver bookkeeping for it would be wrong. A whole-chunk broadcast
     * ({@code onlySectionYs == null}) keeps the original claimed-but-not-delivered invariant
     * verbatim: every path that does not enqueue a send for a whole-chunk broadcast must call
     * {@code setSyncedState(..., false)}.
     */
    public static void broadcastLODData(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, onlySectionYs);

        if (sections.isEmpty()) return;

        double maxDistSq = syncRadiusSq();
        var registryAccess = chunk.getLevel().registryAccess();
        var dimension = chunk.getLevel().dimension();

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                if (onlySectionYs == null) setSyncedState(player, dimension, pos, false);
                continue;
            }

            if (gated(player, dimension)) {
                if (onlySectionYs == null) setSyncedState(player, dimension, pos, false);
                continue;
            }

            boolean accepted = LodSendQueue.getInstance().enqueue(
                player, dimension, pos, minY, sections, registryAccess);
            if (onlySectionYs == null) {
                // Mirror the enqueue result into the synced set: accepted chunks won't be
                // re-sent by the catch-up path, dropped ones will be.
                setSyncedState(player, dimension, pos, accepted);
            }
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
     * <p>{@code onlySectionYs}, non-null for a per-section block-edit resend, is checked ported
     * from unified's {@code ChunkUpdateTracker} flow-down: null means "every non-air section",
     * exactly the original behaviour.
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

            SectionPos sectionPos = SectionPos.of(pos, sectionY);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

            sections.add(new LodSendQueue.PendingSection(
                sectionY,
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
