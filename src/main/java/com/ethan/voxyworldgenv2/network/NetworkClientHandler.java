package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;

public class NetworkClientHandler {
    
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HandshakePayload.TYPE, (payload, context) -> {
            boolean serverHasMod = payload.serverHasMod();
            int protocol = payload.protocolVersion();
            context.client().execute(() -> {
                NetworkState.setServerConnected(serverHasMod);
                NetworkState.setServerProtocol(protocol);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LODDataPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                handleLODData(payload);
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static void handleLODData(NetworkHandler.LODDataPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        // discard LOD data from a different dimension to prevent cross-dimension rendering artifacts (issue #43)
        if (!level.dimension().equals(payload.dimension())) return;

        // Wire bytes, not decompressed bytes: the "received" figure must agree with what the
        // server's per-player accounting (and the player's actual connection) saw.
        NetworkState.incrementReceived(payload.wireSize());

        java.util.List<NetworkHandler.LODDataPayload.SectionData> sections;
        try {
            sections = payload.decodeSections();
        } catch (Exception e) {
            // Decode used to happen inside the netty pipeline; now that it runs here, a corrupt
            // body must not escape onto the client main thread.
            VoxyWorldGenV2.LOGGER.error("discarding undecodable LOD data for chunk " + payload.pos(), e);
            return;
        }

        // LodMemory/RegionBitmask track presence per chunk column, not per section, so there is
        // no way to represent "half of this chunk is in Voxy" -- it is all-or-nothing. A payload
        // with no sections is never recorded as known.
        boolean allIngested = !sections.isEmpty();
        for (NetworkHandler.LODDataPayload.SectionData sectionData : sections) {
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.states());
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                // 1.21.1 has no PalettedContainerFactory; the LevelChunkSection(Registry<Biome>)
                // constructor is the equivalent, building an all-air SECTION_STATES container and a
                // plains-default SECTION_BIOMES container that the reads below overwrite wholesale.
                Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
                LevelChunkSection section = new LevelChunkSection(biomeRegistry);
                
                // we need to read the states and biomes back using RegistryFriendlyByteBuf for palette consistency
                net.minecraft.network.RegistryFriendlyByteBuf statesBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    new net.minecraft.network.FriendlyByteBuf(statesRaw), 
                    level.registryAccess()
                );
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);
                
                net.minecraft.network.RegistryFriendlyByteBuf biomesBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    new net.minecraft.network.FriendlyByteBuf(biomesRaw), 
                    level.registryAccess()
                );
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);
                
                // ingest into voxy
                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;
                
                // AND, not assignment: one section that voxy did not take is enough to disqualify
                // the whole column. rawIngest reports false for a failed invoke AND for the case
                // where its reflected handles could not be resolved at all, which is otherwise a
                // silent no-op that would still get recorded as "voxy has this".
                allIngested &= VoxyIntegration.rawIngest(
                    level, section, payload.pos().x, sectionData.y(), payload.pos().z, bl, sl);

            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("failed to handle LOD data for chunk " + payload.pos(), e);
                allIngested = false;
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }

        // Record only what actually reached Voxy in full: the memory must mean "Voxy has this",
        // not "a packet arrived", or the server will skip chunks that never made it in. A chunk
        // with even one failed section is not recorded, since LodMemory tracks whole columns.
        if (allIngested) {
            com.ethan.voxyworldgenv2.client.LodMemory.record(payload.dimension(), payload.pos().x, payload.pos().z);
        }
    }
}
