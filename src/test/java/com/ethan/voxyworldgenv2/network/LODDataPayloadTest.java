package com.ethan.voxyworldgenv2.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The LOD payload compresses its section batch ONCE, at construction on the sender thread, and
 * carries the deflated body from then on. That is what makes per-player wire accounting and
 * wire-based throttling possible: the payload knows its own network size before it is sent.
 *
 * <p>The bytes written are identical to what the old encode-time compression produced (utf dim,
 * chunk pos, minY, varint plain length, byte-array body), so protocol version does not change
 * for this — only where the Deflater runs.
 */
class LODDataPayloadTest {

    private static final ResourceKey<Level> OVERWORLD =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));

    private static NetworkHandler.LODDataPayload.SectionData section(int y, byte fill, boolean withLight) {
        byte[] states = new byte[3000];
        java.util.Arrays.fill(states, fill);
        byte[] biomes = new byte[]{1, 2, 3, fill};
        byte[] light = null;
        if (withLight) {
            light = new byte[2048];
            java.util.Arrays.fill(light, (byte) 0x77);
        }
        return new NetworkHandler.LODDataPayload.SectionData(y, states, biomes, light, light);
    }

    private static void assertSectionEquals(NetworkHandler.LODDataPayload.SectionData expected,
                                            NetworkHandler.LODDataPayload.SectionData actual) {
        assertEquals(expected.y(), actual.y());
        assertArrayEquals(expected.states(), actual.states());
        assertArrayEquals(expected.biomes(), actual.biomes());
        assertArrayEquals(expected.blockLight(), actual.blockLight());
        assertArrayEquals(expected.skyLight(), actual.skyLight());
    }

    @Test
    void roundTripsSectionsThroughCompressionAndTheWire() {
        List<NetworkHandler.LODDataPayload.SectionData> sections =
            List.of(section(-4, (byte) 9, true), section(0, (byte) 1, false));

        NetworkHandler.LODDataPayload sent =
            NetworkHandler.LODDataPayload.of(OVERWORLD, new ChunkPos(12, -34), -4, sections);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sent.write(buf);
            NetworkHandler.LODDataPayload received = new NetworkHandler.LODDataPayload(buf);
            assertFalse(buf.isReadable(), "the whole payload must be consumed");

            assertEquals(OVERWORLD, received.dimension());
            assertEquals(new ChunkPos(12, -34), received.pos());
            assertEquals(-4, received.minY());

            List<NetworkHandler.LODDataPayload.SectionData> decoded = received.decodeSections();
            assertEquals(sections.size(), decoded.size());
            for (int i = 0; i < sections.size(); i++) {
                assertSectionEquals(sections.get(i), decoded.get(i));
            }
        } finally {
            buf.release();
        }
    }

    @Test
    void repetitiveSectionsShrinkAndWireSizeReportsTheShrunkBody() {
        NetworkHandler.LODDataPayload payload = NetworkHandler.LODDataPayload.of(
            OVERWORLD, new ChunkPos(0, 0), -4, List.of(section(0, (byte) 5, true)));

        assertTrue(payload.wireSize() < payload.plainLength(),
            "terrain-like data must deflate: wire " + payload.wireSize()
                + " vs plain " + payload.plainLength());
    }

    @Test
    void incompressibleSectionsFallBackToStoredBytesAndStillRoundTrip() {
        byte[] noise = new byte[4096];
        new Random(42).nextBytes(noise);
        var sections = List.of(new NetworkHandler.LODDataPayload.SectionData(3, noise, noise, null, null));

        NetworkHandler.LODDataPayload sent =
            NetworkHandler.LODDataPayload.of(OVERWORLD, new ChunkPos(1, 1), -4, sections);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sent.write(buf);
            NetworkHandler.LODDataPayload received = new NetworkHandler.LODDataPayload(buf);
            List<NetworkHandler.LODDataPayload.SectionData> decoded = received.decodeSections();
            assertSectionEquals(sections.get(0), decoded.get(0));
        } finally {
            buf.release();
        }
    }
}
