package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NetworkClientHandler {

    // drained nearest-first each tick, sorted once per tick instead of scanned per poll
    private static final ArrayDeque<NetworkHandler.LODDataPayload> INGEST_QUEUE = new ArrayDeque<>();
    private static final Object QUEUE_LOCK = new Object();
    // max sections to ingest per client tick
    private static final int MAX_SECTIONS_PER_TICK = 96;
    // cap on the queue so a huge backfill can't grow it without bound
    private static final int MAX_QUEUE_SIZE = 8192;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HandshakePayload.TYPE, (payload, context) -> {
            boolean serverHasMod = payload.serverHasMod();
            context.client().execute(() -> {
                // Record the server's protocol instead of demanding equality. Every serverbound
                // feature is gated on a floor (supportsKnownChunks, supportsStorageReport), because
                // sending a payload the peer never registered DROPS the connection. Order matters:
                // setServerConnected(false) zeroes the protocol, so connect first, then record.
                NetworkState.setServerConnected(serverHasMod);
                NetworkState.setServerProtocol(serverHasMod ? payload.protocolVersion() : 0);
                if (serverHasMod && payload.protocolVersion() != NetworkHandler.PROTOCOL_VERSION) {
                    VoxyWorldGenV2.LOGGER.info("server voxy protocol {} != ours {}, using the common subset",
                            payload.protocolVersion(), NetworkHandler.PROTOCOL_VERSION);
                }
                ClientPlayNetworking.send(new NetworkHandler.HandshakeAckPayload(NetworkHandler.PROTOCOL_VERSION));
            });
        });

        // queue lod data and drain it on the client tick to protect fps
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LODDataPayload.TYPE, (payload, context) -> {
            synchronized (QUEUE_LOCK) {
                INGEST_QUEUE.addLast(payload);
                // memory backstop, drain trims by distance, this only fires on a burst
                // between ticks where dropping oldest is fine
                if (INGEST_QUEUE.size() > MAX_QUEUE_SIZE) {
                    INGEST_QUEUE.pollFirst();
                }
            }
        });

        // server's live config + whether this client may edit it
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.ServerConfigPayload.TYPE, (payload, context) -> {
            context.client().execute(() ->
                    ServerConfigState.set(payload.config(), payload.canEdit()));
        });
    }

    // player's chunk position, call on the client main thread
    private static ChunkPos playerChunk() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return mc.player.chunkPosition();
    }

    private static long distSq(ChunkPos a, ChunkPos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    // drains a bounded number of queued sections per tick, nearest chunk first
    // sort once per tick, ingest outside the lock so the netty thread isn't blocked
    public static void drainIngestQueue() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // not in a world, drop anything queued so it doesn't leak into the next session
            synchronized (QUEUE_LOCK) {
                INGEST_QUEUE.clear();
            }
            return;
        }

        ChunkPos center = playerChunk();

        // grab everything under the lock, work on it after
        List<NetworkHandler.LODDataPayload> snapshot;
        synchronized (QUEUE_LOCK) {
            if (INGEST_QUEUE.isEmpty()) return;
            snapshot = new ArrayList<>(INGEST_QUEUE);
            INGEST_QUEUE.clear();
        }

        if (center != null) {
            final ChunkPos c = center;
            snapshot.sort(Comparator.comparingLong(p -> distSq(c, p.pos())));
        }

        int sectionsThisTick = 0;
        int i = 0;
        for (; i < snapshot.size() && sectionsThisTick < MAX_SECTIONS_PER_TICK; i++) {
            NetworkHandler.LODDataPayload payload = snapshot.get(i);
            // Inflate once here and hand the result down: decodeSections() allocates, and the
            // per-tick budget needs the section count before processing either way.
            List<NetworkHandler.LODDataPayload.SectionData> decoded;
            try {
                decoded = payload.decodeSections();
            } catch (RuntimeException e) {
                VoxyWorldGenV2.LOGGER.error("failed to decode LOD data for chunk " + payload.pos(), e);
                continue;
            }
            sectionsThisTick += decoded.size();
            processLODData(level, payload, decoded);
        }

        // put the rest back, dropping the farthest if we're over the cap
        if (i < snapshot.size()) {
            synchronized (QUEUE_LOCK) {
                int kept = 0;
                for (; i < snapshot.size() && kept < MAX_QUEUE_SIZE; i++, kept++) {
                    INGEST_QUEUE.addLast(snapshot.get(i));
                }
                // concurrent adds sit at the back, drop oldest to hold the cap
                while (INGEST_QUEUE.size() > MAX_QUEUE_SIZE) {
                    INGEST_QUEUE.pollFirst();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void processLODData(ClientLevel level, NetworkHandler.LODDataPayload payload,
                                       List<NetworkHandler.LODDataPayload.SectionData> sections) {
        boolean allIngested = true;
        // drop data from another dimension or it renders in the wrong world
        if (!level.dimension().equals(payload.dimension())) return;

        // Post-deflate bytes, matching what the server metered per player. The F3 overlay's
        // "bandwidth" line therefore reports real wire traffic, not decompressed terrain size.
        NetworkState.incrementReceived(payload.wireSize());

        for (NetworkHandler.LODDataPayload.SectionData sectionData : sections) {
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.states());
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
                LevelChunkSection section = new LevelChunkSection(factory);

                // On 26.2 the palette codec is registry-free: PalettedContainer.read takes a plain
                // FriendlyByteBuf, and the RegistryAccess it used to need now lives in the factory
                // above. Wrapping in a RegistryFriendlyByteBuf here would be pure ceremony.
                ((PalettedContainer<BlockState>) section.getStates())
                    .read(new net.minecraft.network.FriendlyByteBuf(statesRaw));
                ((PalettedContainer<Holder<Biome>>) section.getBiomes())
                    .read(new net.minecraft.network.FriendlyByteBuf(biomesRaw));

                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;

                // &= not |=: the chunk counts as remembered only if EVERY section landed. Recording
                // a partial ingest tells the server to skip a chunk Voxy only partly has, and the
                // hole it leaves is never re-sent.
                allIngested &= VoxyIntegration.rawIngest(
                    level, section, payload.pos().x(), sectionData.y(), payload.pos().z(), bl, sl);

            } catch (Exception e) {
                allIngested = false;
                VoxyWorldGenV2.LOGGER.error("failed to handle LOD data for chunk " + payload.pos(), e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }

        // Remember it only if Voxy actually took every section. The server uses this to skip
        // re-sending, so an optimistic record is a permanent hole.
        if (allIngested) {
            com.ethan.voxyworldgenv2.client.LodMemory.record(
                payload.dimension(), payload.pos().x(), payload.pos().z());
        }
    }
}
