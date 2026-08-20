package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
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
import net.minecraft.server.permissions.Permissions;
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

    /**
     * The merged Fabric wire format: unified's config push plus the fork's compressed LOD, known
     * chunks, storage report and settings payloads. It is a superset of neither lineage, so it gets
     * its own number rather than continuing either sequence. Peers announce this in the handshake
     * and gate features on floors (>=), never equality.
     */
    public static final int PROTOCOL_VERSION = 5;

    // keep packets well under netty 2mb limit so servers don't reset the connection
    // Package-private: LodSendQueue splits batches on it and RegionBitmask derives
    // MAX_REGIONS_PER_PACKET (180) from this exact value. Change the value and that derivation
    // silently goes wrong while its tests keep passing.
    static final int MAX_PACKET_BYTES = 32_768;
    private static final int SECTION_OVERHEAD_BYTES = 32;
    private static final int PACKET_OVERHEAD_BYTES = 256;

    // op level required to edit server config from the client
    private static boolean canEditConfig(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public record HandshakePayload(boolean serverHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        /**
         * Protocol 1 wrote only the boolean. Reading a varint unconditionally throws inside the
         * netty decoder and drops the connection with an opaque "Internal Exception" before the
         * player reaches the world, so a missing field must read as version 1.
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

    // client -> server ack carrying the client's protocol, so the server can gate features on a floor
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

    // server -> client the live server values plus whether this client may edit them
    public record ServerConfigPayload(Config.ServerConfig config, boolean canEdit) implements CustomPacketPayload {
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

    // client op pushes new values to apply
    public record ServerConfigPushPayload(Config.ServerConfig config) implements CustomPacketPayload {
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

    /** Raw section bytes before deflate; server-side only. */
    public static final java.util.concurrent.atomic.AtomicLong RAW_SECTION_BYTES = new java.util.concurrent.atomic.AtomicLong();
    /** Deflated section bytes actually put on the wire; server-side only. */
    public static final java.util.concurrent.atomic.AtomicLong WIRE_SECTION_BYTES = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Carries one chunk's section batch, deflated as a single unit. Terrain data is highly
     * repetitive (palettes, runs of the same state, near-identical light arrays), so a whole-batch
     * zlib window typically shrinks it 3-6x -- measured via RAW/WIRE_SECTION_BYTES.
     *
     * <p>Compression happens ONCE, in {@link #of} on the sender thread, not at netty encode time.
     * The payload then knows its own network size before it is sent, which is what lets the send
     * queue throttle and account per player in real wire bytes rather than raw bytes. Section bytes
     * need no registry context (they are already-serialised arrays), which is why the codec runs on
     * plain {@link FriendlyByteBuf}; on 26.2 the palette codec itself is registry-free, the
     * RegistryAccess having moved into PalettedContainerFactory.
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

            // approx serialized size incl framing, used for batch splitting
            int sizeBytes() {
                return states.length + biomes.length
                    + (blockLight != null ? blockLight.length : 0)
                    + (skyLight != null ? skyLight.length : 0)
                    + SECTION_OVERHEAD_BYTES;
            }
        }

        /** Serialises and deflates the batch on the calling (sender) thread. */
        public static LODDataPayload of(ResourceKey<Level> dimension, ChunkPos pos, int minY,
                                        List<SectionData> sections) {
            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
            byte[] plain;
            try {
                FriendlyByteBuf inner = new FriendlyByteBuf(raw);
                inner.writeCollection(sections, (b, sec) -> sec.write((FriendlyByteBuf) b));
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
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readVarInt(),
                buf.readByteArray()
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(dimension.identifier().toString());
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

    // one sync radius shared by the broadcast, catch-up and chunk-load paths. Derived from the
    // configured generationRadius rather than a fixed constant, so tuning the radius moves the
    // set of players who receive a chunk with it.
    public static double syncRadiusSq() {
        long radiusBlocks = (long) Config.DATA.generationRadius * 16L;
        double r = radiusBlocks;
        return r * r;
    }

    public static boolean inSyncRange(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (player.level() != chunk.getLevel()) return false;
        double dx = player.getX() - pos.getMiddleBlockX();
        double dz = player.getZ() - pos.getMiddleBlockZ();
        return dx * dx + dz * dz <= syncRadiusSq();
    }

    // coarse range/dim check for the send pool, pos is read racily but worst case
    // is one extra chunk
    private static boolean stillRelevant(ServerPlayer player, ResourceKey<Level> dim, ChunkPos pos) {
        if (!player.level().dimension().equals(dim)) return false;
        double dx = player.getX() - pos.getMiddleBlockX();
        double dz = player.getZ() - pos.getMiddleBlockZ();
        return dx * dx + dz * dz <= syncRadiusSq();
    }

    // registers payload types + serverbound receivers clientbound receivers live in the client
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakeAckPayload.TYPE, HandshakeAckPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ServerConfigPushPayload.TYPE, ServerConfigPushPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HandshakeAckPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                // Record rather than compare. A mismatch is not a reason to send nothing: features
                // are gated on floors, so an older client keeps receiving terrain and is simply not
                // offered payloads its build cannot parse. setClientProtocol must run before
                // sendServerConfig, which is gated on the recorded value.
                PlayerTracker.getInstance().setClientProtocol(player.getUUID(), payload.clientProtocol());
                if (payload.clientProtocol() != PROTOCOL_VERSION) {
                    VoxyWorldGenV2.LOGGER.info("client {} speaks voxy protocol {} (ours={}), serving what it supports",
                            player.getName().getString(), payload.clientProtocol(), PROTOCOL_VERSION);
                }
                sendServerConfig(player);
                com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().onPlayerModded();
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
                com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
                VoxyWorldGenV2.LOGGER.info("server config updated by op {}", player.getName().getString());
                broadcastServerConfig();
            });
        });

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized");
    }

    // send the live server config to one player (canEdit reflects their op status)
    public static void sendServerConfig(ServerPlayer player) {
        boolean canEdit = canEditConfig(player);
        ServerPlayNetworking.send(player, new ServerConfigPayload(Config.ServerConfig.snapshot(), canEdit));
    }

    public static void broadcastServerConfig() {
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            sendServerConfig(player);
        }
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.pack());
            } else {
                synced.remove(pos.pack());
            }
        }
    }

    public static void shutdown() {
        LodSendQueue.getInstance().shutdown();
    }

    public static void startSendQueue() {
        LodSendQueue.getInstance().start();
    }

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    // if onlySectionYs is non-null, only those section y-levels are sent (used for block edits)
    public static void broadcastLODData(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        List<ServerPlayer> recipients = new ArrayList<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (!inSyncRange(player, chunk)) {
                if (onlySectionYs == null) setSyncedState(player, pos, false);
                continue;
            }
            recipients.add(player);
        }

        if (recipients.isEmpty()) return;

        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, onlySectionYs);
        if (sections.isEmpty()) {
            if (onlySectionYs == null) {
                for (ServerPlayer player : recipients) setSyncedState(player, pos, false);
            }
            return;
        }
        if (onlySectionYs == null) {
            for (ServerPlayer player : recipients) setSyncedState(player, pos, true);
        }
        sendAsync(dim, pos, minY, sections, recipients);
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        if (!inSyncRange(player, chunk)) {
            setSyncedState(player, pos, false);
            return;
        }

        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk, null);
        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }
        setSyncedState(player, pos, true);
        sendAsync(dim, pos, minY, sections, List.of(player));
    }

    private static void sendAsync(ResourceKey<Level> dim, ChunkPos pos, int minY, List<LodSendQueue.PendingSection> sections, List<ServerPlayer> recipients) {
        for (ServerPlayer player : recipients) {
            if (player.hasDisconnected()) continue;
            if (!stillRelevant(player, dim, pos)) continue;
            // A refused enqueue means the sender is saturated. Leave the chunk unsynced so the
            // catch-up path retries it rather than silently losing it.
            if (!LodSendQueue.getInstance().enqueue(player, dim, pos, minY, sections)) {
                setSyncedState(player, pos, false);
            }
        }
    }


    // true if every byte is zero, used to drop empty block-light arrays
    /**
     * Takes a private copy of each section's state container on the MAIN thread and serialises the
     * cheap parts (biomes, light) inline. A live PalettedContainer is guarded by a ThreadingDetector
     * and the main thread may mutate it while the sender reads, so the copy is required, not an
     * optimisation. The expensive palette encode then happens on the send thread.
     *
     * <p>Keeps both of unified's refinements over the fork's version: the onlySectionYs filter, so a
     * block edit resends one section rather than the whole column, and the all-zero block-light skip.
     */
    private static List<LodSendQueue.PendingSection> snapshotSections(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LodSendQueue.PendingSection> out = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        LevelChunkSection[] sectionArray = chunk.getSections();
        for (int i = 0; i < sectionArray.length; i++) {
            int sectionY = minY + i;
            if (onlySectionYs != null && !onlySectionYs.contains(sectionY)) continue;

            LevelChunkSection section = sectionArray[i];
            if (section == null || section.hasOnlyAir()) continue;

            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            try {
                @SuppressWarnings("unchecked")
                PalettedContainer<BlockState> statesCopy =
                    ((PalettedContainer<BlockState>) section.getStates()).copy();

                FriendlyByteBuf biomesBuf = new FriendlyByteBuf(biomesRaw);
                section.getBiomes().write(biomesBuf);
                byte[] biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);

                SectionPos sectionPos = SectionPos.of(pos, sectionY);
                DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                // empty block light is meaningless, voxy ingests null block light anyway
                // so send null instead of a dead 2048-byte array
                byte[] blData = (bl != null && !isAllZero(bl.getData())) ? bl.getData().clone() : null;

                out.add(new LodSendQueue.PendingSection(
                    sectionY, statesCopy, biomes, blData,
                    sl != null ? sl.getData().clone() : null));
            } catch (Throwable t) {
                VoxyWorldGenV2.LOGGER.debug("skipped section {} of chunk {}: {}", sectionY, pos, t.toString());
            } finally {
                biomesRaw.release();
            }
        }
        return out;
    }

    private static boolean isAllZero(byte[] data) {
        if (data == null) return true;
        for (byte b : data) {
            if (b != 0) return false;
        }
        return true;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections) {
        List<LODDataPayload.SectionData> batch = new ArrayList<>();
        int batchBytes = PACKET_OVERHEAD_BYTES;

        for (LODDataPayload.SectionData sd : sections) {
            int sectionBytes = sd.sizeBytes();

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                ServerPlayNetworking.send(player, LODDataPayload.of(dimension, pos, minY, batch));
                batch = new ArrayList<>();
                batchBytes = PACKET_OVERHEAD_BYTES;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, LODDataPayload.of(dimension, pos, minY, batch));
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        ServerPlayNetworking.send(player, new HandshakePayload(true, PROTOCOL_VERSION));
    }
}
