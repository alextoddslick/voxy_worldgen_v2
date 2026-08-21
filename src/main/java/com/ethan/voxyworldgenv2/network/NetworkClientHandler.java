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
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;

public class NetworkClientHandler {
    
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HandshakePayload.TYPE, (payload, context) -> {
            boolean serverHasMod = payload.serverHasMod();
            int protocol = payload.protocolVersion();
            context.client().execute(() -> {
                NetworkState.setServerConnected(serverHasMod);
                NetworkState.setServerProtocol(protocol);
                // Only ack a server that registered the payload -- an older server never
                // registered HandshakeAckPayload, and sending it one can drop the connection.
                if (NetworkState.supportsHandshakeAck()) {
                    ClientPlayNetworking.send(new NetworkHandler.HandshakeAckPayload(NetworkHandler.PROTOCOL_VERSION));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LODDataPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                handleLODData(payload);
            });
        });

        // Neither payload drives a client UI on this branch (the fork's settings-screen feature
        // was not ported here, only the wire format -- see the phase-3 report), but registering a
        // receiver still matters: Fabric decodes a clientbound payload it has no handler for and
        // just warns, so this is only a hair cheaper than leaving it unhandled -- it exists so a
        // future client build has somewhere to actually consume these without a protocol bump.
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.ServerConfigPayload.TYPE,
            (payload, context) -> context.client().execute(() ->
                VoxyWorldGenV2.LOGGER.debug("received server config (canEdit={})", payload.canEdit())));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.SettingsSnapshotPayload.TYPE,
            (payload, context) -> context.client().execute(() ->
                VoxyWorldGenV2.LOGGER.debug("received settings snapshot (isOp={})", payload.isOp())));
    }

    @SuppressWarnings("unchecked")
    private static void handleLODData(NetworkHandler.LODDataPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        // discard LOD data from a different dimension to prevent cross-dimension rendering artifacts (issue #43)
        if (!level.dimension().equals(payload.dimension())) return;
        
        // calculate approximate payload size
        long bytes = 0;
        for (NetworkHandler.LODDataPayload.SectionData sd : payload.sections()) {
            bytes += sd.states().length;
            bytes += sd.biomes().length;
            if (sd.blockLight() != null) bytes += sd.blockLight().length;
            if (sd.skyLight() != null) bytes += sd.skyLight().length;
        }
        NetworkState.incrementReceived(bytes);

        // LodMemory/RegionBitmask track presence per chunk column, not per section, so there is
        // no way to represent "half of this chunk is in Voxy" -- it is all-or-nothing. A payload
        // with no sections is never recorded as known.
        boolean allIngested = !payload.sections().isEmpty();
        for (NetworkHandler.LODDataPayload.SectionData sectionData : payload.sections()) {
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.states());
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                // recreate section using PalettedContainerFactory
                PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
                LevelChunkSection section = new LevelChunkSection(factory);
                
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
