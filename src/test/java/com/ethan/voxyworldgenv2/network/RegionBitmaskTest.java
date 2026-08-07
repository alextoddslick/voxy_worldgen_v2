package com.ethan.voxyworldgenv2.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionBitmaskTest {

    /** Records a chunk into a region map the way LodMemory will. */
    private static void record(Map<Long, byte[]> regions, int cx, int cz) {
        byte[] mask = regions.computeIfAbsent(
            RegionBitmask.regionKey(cx, cz), k -> new byte[RegionBitmask.MASK_BYTES]);
        RegionBitmask.set(mask, cx, cz);
    }

    private static boolean has(Map<Long, byte[]> regions, int cx, int cz) {
        byte[] mask = regions.get(RegionBitmask.regionKey(cx, cz));
        return mask != null && RegionBitmask.get(mask, cx, cz);
    }

    @Test
    void regionKeyGroupsChunksInto32x32Blocks() {
        assertEquals(RegionBitmask.regionKey(0, 0), RegionBitmask.regionKey(31, 31));
        assertNotEquals(RegionBitmask.regionKey(31, 0), RegionBitmask.regionKey(32, 0));
        assertNotEquals(RegionBitmask.regionKey(0, 31), RegionBitmask.regionKey(0, 32));
    }

    @Test
    void negativeCoordinatesFloorIntoTheCorrectRegion() {
        // chunk -1 belongs to region -1, which covers chunks -32..-1
        assertEquals(RegionBitmask.regionKey(-1, -1), RegionBitmask.regionKey(-32, -32));
        assertNotEquals(RegionBitmask.regionKey(-33, -33), RegionBitmask.regionKey(-32, -32));
        assertEquals(1023, RegionBitmask.bitIndex(-1, -1));
        assertEquals(0, RegionBitmask.bitIndex(-32, -32));
        assertEquals(-32, RegionBitmask.regionBaseChunkX(RegionBitmask.regionKey(-1, -5)));
        assertEquals(-32, RegionBitmask.regionBaseChunkZ(RegionBitmask.regionKey(-1, -5)));
    }

    @Test
    void bitIndexIsUniqueAcrossAWholeRegion() {
        Set<Integer> seen = new LinkedHashSet<>();
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                assertTrue(seen.add(RegionBitmask.bitIndex(cx, cz)), "duplicate at " + cx + "," + cz);
            }
        }
        assertEquals(1024, seen.size());
    }

    @Test
    void setAndGetRoundTripWithoutTouchingNeighbours() {
        byte[] mask = new byte[RegionBitmask.MASK_BYTES];
        RegionBitmask.set(mask, 5, 7);
        assertTrue(RegionBitmask.get(mask, 5, 7));
        assertFalse(RegionBitmask.get(mask, 6, 7));
        assertFalse(RegionBitmask.get(mask, 5, 8));
        assertFalse(RegionBitmask.get(mask, 4, 7));
    }

    @Test
    void encodeDecodeRoundTripsASingleRegion() throws IOException {
        Map<Long, byte[]> regions = new HashMap<>();
        record(regions, 3, 4);
        record(regions, 31, 31);

        List<byte[]> packets = RegionBitmask.encode(regions);
        assertEquals(1, packets.size());

        Map<Long, byte[]> out = RegionBitmask.decode(packets.get(0));
        assertTrue(has(out, 3, 4));
        assertTrue(has(out, 31, 31));
        assertFalse(has(out, 5, 5));
    }

    @Test
    void encodeAlwaysProducesAtLeastOnePacketSoLastFlagIsAlwaysSent() throws IOException {
        List<byte[]> packets = RegionBitmask.encode(new HashMap<>());
        assertEquals(1, packets.size());
        assertTrue(RegionBitmask.decode(packets.get(0)).isEmpty());
    }

    @Test
    void encodeSplitsAtExactlyMaxRegionsPerPacket() throws IOException {
        Map<Long, byte[]> exact = new HashMap<>();
        for (int i = 0; i < RegionBitmask.MAX_REGIONS_PER_PACKET; i++) record(exact, i * 32, 0);
        assertEquals(1, RegionBitmask.encode(exact).size());

        Map<Long, byte[]> oneMore = new HashMap<>(exact);
        record(oneMore, RegionBitmask.MAX_REGIONS_PER_PACKET * 32, 0);
        assertEquals(2, RegionBitmask.encode(oneMore).size());
    }

    @Test
    void everyPacketStaysUnderTheProtocolCeiling() {
        Map<Long, byte[]> regions = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            byte[] mask = new byte[RegionBitmask.MASK_BYTES];
            // fill completely: worst case for compression
            java.util.Arrays.fill(mask, (byte) 0xFF);
            regions.put(RegionBitmask.regionKey(i * 32, 0), mask);
        }
        for (byte[] packet : RegionBitmask.encode(regions)) {
            assertTrue(packet.length < 32_768, "packet was " + packet.length + " bytes");
        }
    }

    @Test
    void multiPacketSplitReassemblesToTheOriginalSet() throws IOException {
        Random rng = new Random(20260806L);
        Map<Long, byte[]> regions = new HashMap<>();
        List<int[]> chunks = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            int cx = rng.nextInt(4000) - 2000;
            int cz = rng.nextInt(4000) - 2000;
            chunks.add(new int[]{cx, cz});
            record(regions, cx, cz);
        }

        List<byte[]> packets = RegionBitmask.encode(regions);
        assertTrue(packets.size() > 1, "test data should span multiple packets");

        Map<Long, byte[]> reassembled = new HashMap<>();
        for (byte[] packet : packets) reassembled.putAll(RegionBitmask.decode(packet));

        assertEquals(regions.size(), reassembled.size());
        for (int[] c : chunks) assertTrue(has(reassembled, c[0], c[1]), "lost " + c[0] + "," + c[1]);
    }

    @Test
    void incompressibleDataFallsBackToStoredAndStillDecodes() throws IOException {
        Random rng = new Random(1234L);
        Map<Long, byte[]> regions = new HashMap<>();
        byte[] noise = new byte[RegionBitmask.MASK_BYTES];
        rng.nextBytes(noise);
        regions.put(RegionBitmask.regionKey(0, 0), noise);

        Map<Long, byte[]> out = RegionBitmask.decode(RegionBitmask.encode(regions).get(0));
        assertArrayEquals(noise, out.get(RegionBitmask.regionKey(0, 0)));
    }

    @Test
    void fileRoundTrips() throws IOException {
        Map<Long, byte[]> regions = new HashMap<>();
        record(regions, -1, -1);
        record(regions, 100, 200);

        Map<Long, byte[]> out = RegionBitmask.readFile(RegionBitmask.writeFile(regions));
        assertTrue(has(out, -1, -1));
        assertTrue(has(out, 100, 200));
    }

    @Test
    void fileRejectsBadMagicWrongVersionAndTruncation() {
        Map<Long, byte[]> regions = new HashMap<>();
        record(regions, 1, 1);
        byte[] good = RegionBitmask.writeFile(regions);

        byte[] badMagic = good.clone();
        badMagic[0] ^= 0x7F;
        assertThrows(IOException.class, () -> RegionBitmask.readFile(badMagic));

        byte[] badVersion = good.clone();
        badVersion[7] = 99;
        assertThrows(IOException.class, () -> RegionBitmask.readFile(badVersion));

        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 10);
        assertThrows(IOException.class, () -> RegionBitmask.readFile(truncated));

        assertThrows(IOException.class, () -> RegionBitmask.readFile(new byte[3]));
    }
}
