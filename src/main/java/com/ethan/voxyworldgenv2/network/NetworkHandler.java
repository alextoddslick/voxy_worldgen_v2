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
    public static final Identifier LOD_DATA_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":lod_data");
    public static final Identifier KNOWN_CHUNKS_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":known_chunks");

    /**
     * Bumped whenever the payload wire format changes. The client refuses to upload its known-chunk
     * set unless the server advertises at least 2, because sending a payload a server has not
     * registered can drop the connection.
     */
    public static final int PROTOCOL_VERSION = 2;

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

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(KnownChunksPayload.TYPE, KnownChunksPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(KnownChunksPayload.TYPE,
            (payload, context) -> receiveKnownChunks(context.player(), payload));

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized (protocol {})", PROTOCOL_VERSION);
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

    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk);

        if (sections.isEmpty()) return;

        double maxDistSq = 4096.0 * 4096.0;
        var registryAccess = chunk.getLevel().registryAccess();
        var dimension = chunk.getLevel().dimension();

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
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
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LodSendQueue.PendingSection> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();
        var registryAccess = chunk.getLevel().registryAccess();

        for (int i = 0; i < chunk.getSections().length; i++) {
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
