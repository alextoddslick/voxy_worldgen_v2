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

    // Keep individual packets well under the protocol ceiling to prevent connection resets on
    // public servers. The binding limit for a clientbound custom payload in 1.21.1 is
    // ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 1 MiB (the 2 MiB figure is the frame
    // length cap, which is a separate, larger limit) -- so this leaves 32x headroom.
    static final int MAX_PACKET_BYTES = 32_768;

    public record HandshakePayload(boolean serverHasMod) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

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
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, b -> SectionData.read((RegistryFriendlyByteBuf) b))
            );
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimension.location().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            // cast to avoid ambiguous writeCollection / BiConsumer type issues
            buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
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
        
        VoxyWorldGenV2.LOGGER.info("voxy networking initialized");
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.toLong());
            } else {
                synced.remove(pos.toLong());
            }
        }
    }

    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk);

        if (sections.isEmpty()) return;

        double maxDistSq = 4096.0 * 4096.0;
        var registryAccess = chunk.getLevel().registryAccess();
        var dimension = chunk.getLevel().dimension();

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                setSyncedState(player, pos, false);
                continue;
            }

            LodSendQueue.getInstance().enqueue(player, dimension, pos, minY, sections, registryAccess);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk);

        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }

        LodSendQueue.getInstance().enqueue(player, chunk.getLevel().dimension(), pos, minY,
            sections, chunk.getLevel().registryAccess());
        setSyncedState(player, pos, true);
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
        int minY = chunk.getMinSection();
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
        ServerPlayNetworking.send(player, new HandshakePayload(true));
    }
}
