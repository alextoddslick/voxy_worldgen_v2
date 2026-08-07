# Client LOD Memory and `/voxygen refresh` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop re-streaming LOD terrain the client already has in Voxy's database on reconnect, and give operators `/voxygen refresh` to deliberately re-send it.

**Architecture:** The client records every chunk it successfully ingests into Voxy as bits in 32x32-chunk region bitmasks, persists them per server-world and dimension, and uploads them to the server on join and on dimension change. The server seeds its per-player synced set from that upload and skips those chunks on all three send paths. `/voxygen refresh` clears synced bits so the existing worker catch-up loop re-sends them; it introduces no new send path.

**Tech Stack:** Java 21, Fabric Loader 0.17.2, Fabric API 0.116.6+1.21.1, Minecraft 1.21.1 with Mojang mappings, Gradle 9.2.1 + fabric-loom 1.14.10, fastutil (transitive from Minecraft), JUnit 5 (added by Task 1).

**Source spec:** `docs/superpowers/specs/2026-08-06-client-lod-memory-design.md`

## Global Constraints

- Mojang official mappings (`loom.officialMojangMappings()`). Yarn names will not compile.
- Java 21, `options.release = 21`.
- **Never call `ServerChunkCache.getChunk(x, z, load)` on the main thread**, not even with `load=false`. Only `getChunkNow` is safe. See HANDOFF.md — this has killed the server twice via the 60s watchdog.
- The mod is ONE universal jar (`"environment": "*"`) with both `main` and `client` entrypoints. Client-only classes must carry `@Environment(EnvType.CLIENT)` and must never be referenced from common code.
- `PROTOCOL_VERSION = 2`. This release already breaks protocol compatibility (LOD payload compression), so client and server jars must be deployed together regardless.
- Build with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew build`.
- Deploy with `~/mc-test/deploy-all.sh` — it builds once and pushes the same jar to `bmc3`, `vanilla`, and `client/vanilla`. A running server only picks up a new jar on restart.
- Region geometry is fixed: 32x32 chunks per region, 1024 bits, 128 bytes per mask, 180 regions per packet.
- Existing `/voxygen reload` (re-read config JSON) keeps its current meaning and must not be altered.

## Verification reality check

This branch has **no Minecraft test harness** and `:test` is currently `NO-SOURCE`. Tasks 1 and 2 build pure-logic classes with real JUnit tests. Tasks 3–7 touch Minecraft types that cannot be unit-tested here; each states a compile gate plus an explicit manual verification procedure. Do not claim those tasks pass without running the stated manual check.

---

### Task 1: Test infrastructure and the region bitmask codec

The codec is the highest-risk piece: an off-by-one in bit indexing silently corrupts which chunks a player is believed to have, and presents as randomly missing terrain on a live server. It is deliberately free of Minecraft types so it can be unit-tested.

**Files:**
- Modify: `build.gradle` (dependencies block, plus new `sourceSets` and `test` blocks)
- Create: `src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java`
- Test: `src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `long RegionBitmask.regionKey(int chunkX, int chunkZ)`
  - `int RegionBitmask.bitIndex(int chunkX, int chunkZ)`
  - `void RegionBitmask.set(byte[] mask, int chunkX, int chunkZ)`
  - `boolean RegionBitmask.get(byte[] mask, int chunkX, int chunkZ)`
  - `int RegionBitmask.regionBaseChunkX(long regionKey)`
  - `int RegionBitmask.regionBaseChunkZ(long regionKey)`
  - `List<byte[]> RegionBitmask.encode(Map<Long, byte[]> regions)` — always returns at least one packet
  - `Map<Long, byte[]> RegionBitmask.decode(byte[] packet) throws IOException`
  - `byte[] RegionBitmask.writeFile(Map<Long, byte[]> regions)`
  - `Map<Long, byte[]> RegionBitmask.readFile(byte[] bytes) throws IOException`
  - `int RegionBitmask.MASK_BYTES` = 128, `int RegionBitmask.MAX_REGIONS_PER_PACKET` = 180

- [ ] **Step 1: Wire up the test source set**

Add to `build.gradle` inside the existing `dependencies { }` block, after the `modImplementation "com.terraformersmc:modmenu:..."` line:

```gradle
    testImplementation "org.junit.jupiter:junit-jupiter:5.10.2"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
```

Then add these two new top-level blocks after the existing `java { }` block:

```gradle
// Loom does not always propagate the remapped Minecraft classpath to the test source set,
// and SyncedChunkStore (Task 2) needs fastutil, which arrives transitively from Minecraft.
// Adding it explicitly is inert when Loom already did it.
sourceSets {
    test {
        compileClasspath += sourceSets.main.compileClasspath
        runtimeClasspath += sourceSets.main.runtimeClasspath
    }
}

test {
    useJUnitPlatform()
    testLogging { events "passed", "skipped", "failed" }
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java`:

```java
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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test`
Expected: FAIL — compilation error, `RegionBitmask` does not exist.

- [ ] **Step 4: Implement `RegionBitmask`**

Create `src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java`:

```java
package com.ethan.voxyworldgenv2.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Packs "which chunks does this player already have" into 32x32-chunk region bitmasks.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested without a game harness —
 * this is the piece where an off-by-one presents as randomly missing terrain on a live server
 * rather than as an exception.
 *
 * <p>Region keys use the same packing as {@code ChunkPos.asLong}: low 32 bits x, high 32 bits z.
 * Arithmetic shift and mask are correct for negative coordinates — chunk -1 lands in region -1 at
 * index 1023, and region -1 covers chunks -32..-1.
 */
public final class RegionBitmask {
    /** 32x32 chunks per region. */
    public static final int REGION_SHIFT = 5;
    /** 1024 bits = 128 bytes. */
    public static final int MASK_BYTES = 128;

    /**
     * 180 regions is the largest count whose uncompressed body (5-byte varint count plus
     * 180 x 136 bytes = 24,485) stays comfortably inside NetworkHandler.MAX_PACKET_BYTES
     * (32,768) even when the data is incompressible and falls back to stored.
     */
    public static final int MAX_REGIONS_PER_PACKET = 180;

    private static final int ENTRY_BYTES = 8 + MASK_BYTES;
    private static final int MAGIC = 0x564C4D31; // "VLM1"
    private static final int FILE_VERSION = 1;

    private RegionBitmask() {}

    public static long regionKey(int chunkX, int chunkZ) {
        return ((long) (chunkX >> REGION_SHIFT) & 0xFFFFFFFFL)
             | (((long) (chunkZ >> REGION_SHIFT) & 0xFFFFFFFFL) << 32);
    }

    public static int bitIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) | ((chunkZ & 31) << 5);
    }

    public static int regionBaseChunkX(long regionKey) {
        return ((int) regionKey) << REGION_SHIFT;
    }

    public static int regionBaseChunkZ(long regionKey) {
        return ((int) (regionKey >>> 32)) << REGION_SHIFT;
    }

    public static void set(byte[] mask, int chunkX, int chunkZ) {
        int bit = bitIndex(chunkX, chunkZ);
        mask[bit >>> 3] |= (byte) (1 << (bit & 7));
    }

    public static boolean get(byte[] mask, int chunkX, int chunkZ) {
        int bit = bitIndex(chunkX, chunkZ);
        return (mask[bit >>> 3] & (1 << (bit & 7))) != 0;
    }

    /**
     * Splits into packet bodies of at most {@link #MAX_REGIONS_PER_PACKET} regions and deflates
     * each. Always returns at least one packet, so the caller can always mark a final one with
     * {@code last = true} even when the player knows nothing.
     *
     * <p>Packet layout: {@code int rawLength} followed by the body, deflated when that is
     * strictly smaller and stored verbatim otherwise. Because "stored" is used whenever deflate
     * fails to shrink, {@code remaining == rawLength} is an unambiguous stored marker on decode.
     */
    public static List<byte[]> encode(Map<Long, byte[]> regions) {
        // Sorted so the wire bytes are deterministic for a given input, which makes a
        // mismatch reproducible instead of intermittent.
        TreeMap<Long, byte[]> sorted = new TreeMap<>(regions);
        List<byte[]> packets = new ArrayList<>();
        List<Map.Entry<Long, byte[]>> batch = new ArrayList<>(MAX_REGIONS_PER_PACKET);

        for (Map.Entry<Long, byte[]> entry : sorted.entrySet()) {
            batch.add(entry);
            if (batch.size() == MAX_REGIONS_PER_PACKET) {
                packets.add(encodeBatch(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty() || packets.isEmpty()) packets.add(encodeBatch(batch));
        return packets;
    }

    private static byte[] encodeBatch(List<Map.Entry<Long, byte[]>> batch) {
        ByteBuffer body = ByteBuffer.allocate(5 + batch.size() * ENTRY_BYTES);
        writeVarInt(body, batch.size());
        for (Map.Entry<Long, byte[]> entry : batch) {
            body.putLong(entry.getKey());
            byte[] mask = entry.getValue();
            if (mask.length != MASK_BYTES) {
                throw new IllegalArgumentException("mask must be " + MASK_BYTES + " bytes, was " + mask.length);
            }
            body.put(mask);
        }
        byte[] raw = new byte[body.position()];
        body.flip();
        body.get(raw);

        byte[] packed = deflate(raw);
        ByteBuffer out = ByteBuffer.allocate(4 + packed.length);
        out.putInt(raw.length);
        out.put(packed);
        return out.array();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buf = new byte[raw.length + 64];
            int n = deflater.deflate(buf);
            // Incompressible input can overflow the buffer; store verbatim in that case.
            if (!deflater.finished() || n >= raw.length) return raw;
            return java.util.Arrays.copyOf(buf, n);
        } finally {
            deflater.end();
        }
    }

    public static Map<Long, byte[]> decode(byte[] packet) throws IOException {
        if (packet.length < 4) throw new IOException("packet too short: " + packet.length);
        ByteBuffer in = ByteBuffer.wrap(packet);
        int rawLength = in.getInt();
        if (rawLength < 0 || rawLength > 1 << 20) throw new IOException("implausible raw length " + rawLength);

        byte[] payload = new byte[packet.length - 4];
        in.get(payload);

        byte[] raw = payload.length == rawLength ? payload : inflate(payload, rawLength);
        return readEntries(ByteBuffer.wrap(raw));
    }

    private static byte[] inflate(byte[] payload, int rawLength) throws IOException {
        byte[] raw = new byte[rawLength];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload);
            int n = inflater.inflate(raw);
            if (n != rawLength) throw new IOException("short inflate: " + n + " != " + rawLength);
            return raw;
        } catch (DataFormatException e) {
            throw new IOException("corrupt known-chunks packet", e);
        } finally {
            inflater.end();
        }
    }

    private static Map<Long, byte[]> readEntries(ByteBuffer in) throws IOException {
        int count = readVarInt(in);
        if (count < 0 || (long) count * ENTRY_BYTES > in.remaining()) {
            throw new IOException("bad region count " + count);
        }
        Map<Long, byte[]> out = new java.util.HashMap<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            long key = in.getLong();
            byte[] mask = new byte[MASK_BYTES];
            in.get(mask);
            out.put(key, mask);
        }
        return out;
    }

    /** On-disk form for LodMemory. Uncompressed so the file stays inspectable. */
    public static byte[] writeFile(Map<Long, byte[]> regions) {
        TreeMap<Long, byte[]> sorted = new TreeMap<>(regions);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(12 + sorted.size() * ENTRY_BYTES);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeInt(sorted.size());
            for (Map.Entry<Long, byte[]> entry : sorted.entrySet()) {
                out.writeLong(entry.getKey());
                out.write(entry.getValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream cannot fail", e);
        }
        return bytes.toByteArray();
    }

    public static Map<Long, byte[]> readFile(byte[] bytes) throws IOException {
        if (bytes.length < 12) throw new IOException("file too short: " + bytes.length);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.getInt() != MAGIC) throw new IOException("not a voxy LOD memory file");
        int version = in.getInt();
        if (version != FILE_VERSION) throw new IOException("unsupported version " + version);

        int count = in.getInt();
        if (count < 0 || (long) count * ENTRY_BYTES != in.remaining()) {
            throw new IOException("truncated: " + count + " regions declared, " + in.remaining() + " bytes left");
        }
        Map<Long, byte[]> out = new java.util.HashMap<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            long key = in.getLong();
            byte[] mask = new byte[MASK_BYTES];
            in.get(mask);
            out.put(key, mask);
        }
        return out;
    }

    private static void writeVarInt(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    private static int readVarInt(ByteBuffer buf) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buf.hasRemaining()) throw new IOException("truncated varint");
            byte b = buf.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("varint too long");
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test`
Expected: PASS — 11 tests, 0 failures. If the test source set fails to resolve JUnit, confirm the `sourceSets` and `test` blocks from Step 1 are at top level in `build.gradle`, not nested inside `dependencies`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java
git commit -m "feat: region bitmask codec for tracking client-known chunks

Adds the first test source set on this branch (JUnit 5). The codec is
free of Minecraft types precisely so it can be tested without a game
harness — a bit-indexing error here would present as randomly missing
terrain rather than an exception."
```

---

### Task 2: Per-dimension synced chunk store

`PlayerTracker.syncedChunks` is one flat `LongSet` per player, so chunk `(0,0)` in the overworld and in the nether share a key. Today that self-hides because the set dies on disconnect; persisting it would make the missing terrain permanent. Extracting a store keyed by dimension both fixes the bug and makes the forget-operations that `/voxygen refresh` needs unit-testable.

**Files:**
- Create: `src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java`
- Test: `src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java`
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java` (lines 22–23, 34–50, 68–91)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java:185-194` (`setSyncedState`), `:207-221`, `:229-236`
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java:233`

**Interfaces:**
- Consumes: `RegionBitmask.regionBaseChunkX/Z`, `RegionBitmask.get`, `RegionBitmask.MASK_BYTES` (Task 1).
- Produces:
  - `LongSet SyncedChunkStore.setFor(String dimensionId)`
  - `boolean SyncedChunkStore.isSynced(String dimensionId, long chunkPos)`
  - `void SyncedChunkStore.markSynced(String dimensionId, long chunkPos)`
  - `void SyncedChunkStore.markUnsynced(String dimensionId, long chunkPos)`
  - `int SyncedChunkStore.forgetAll(String dimensionId)`
  - `int SyncedChunkStore.forgetWithin(String dimensionId, int centerChunkX, int centerChunkZ, int radiusChunks)`
  - `int SyncedChunkStore.applyRegions(String dimensionId, Map<Long, byte[]> regions)`
  - `int SyncedChunkStore.size(String dimensionId)`
  - `static long SyncedChunkStore.packChunk(int x, int z)` / `static int unpackChunkX(long)` / `static int unpackChunkZ(long)`
  - `SyncedChunkStore PlayerTracker.getStore(UUID uuid)` (null when the player is untracked)
  - `LongSet PlayerTracker.getSyncedChunks(UUID uuid, ResourceKey<Level> dimension)`
  - `static String PlayerTracker.dimensionId(ResourceKey<Level> dimension)`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java`:

```java
package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.network.RegionBitmask;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncedChunkStoreTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void marksAndReadsBack() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(9, 4)));

        store.markUnsynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9)));
    }

    /** The bug this class exists to fix: one flat set made these two the same chunk. */
    @Test
    void dimensionsDoNotCollide() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(0, 0));
        assertFalse(store.isSynced(NETHER, SyncedChunkStore.packChunk(0, 0)));
    }

    @Test
    void chunkPackingRoundTripsIncludingNegatives() {
        for (int[] c : new int[][]{{0, 0}, {1, -1}, {-2000, 3000}, {-1, -1}, {Integer.MIN_VALUE / 2, 7}}) {
            long packed = SyncedChunkStore.packChunk(c[0], c[1]);
            assertEquals(c[0], SyncedChunkStore.unpackChunkX(packed));
            assertEquals(c[1], SyncedChunkStore.unpackChunkZ(packed));
        }
    }

    @Test
    void forgetAllClearsOnlyTheNamedDimension() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(1, 1));
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(2, 2));
        store.markSynced(NETHER, SyncedChunkStore.packChunk(1, 1));

        assertEquals(2, store.forgetAll(OVERWORLD));
        assertEquals(0, store.size(OVERWORLD));
        assertEquals(1, store.size(NETHER));
    }

    @Test
    void forgetWithinUsesACircularRadiusAndSparesChunksOutside() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(0, 0));    // distance 0
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(3, 4));    // distance 5, inside r=5
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(6, 0));    // distance 6, outside
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(-5, 0));   // distance 5, inside

        assertEquals(3, store.forgetWithin(OVERWORLD, 0, 0, 5));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(0, 0)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(3, 4)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(-5, 0)));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(6, 0)));
    }

    @Test
    void forgetWithinOnAnUnknownDimensionIsANoOp() {
        SyncedChunkStore store = new SyncedChunkStore();
        assertEquals(0, store.forgetWithin("minecraft:the_end", 0, 0, 16));
        assertEquals(0, store.forgetAll("minecraft:the_end"));
    }

    @Test
    void applyRegionsSetsExactlyTheChunksTheBitmaskNames() {
        Map<Long, byte[]> regions = new HashMap<>();
        byte[] mask = new byte[RegionBitmask.MASK_BYTES];
        RegionBitmask.set(mask, 5, 6);
        RegionBitmask.set(mask, 31, 31);
        regions.put(RegionBitmask.regionKey(5, 6), mask);

        SyncedChunkStore store = new SyncedChunkStore();
        assertEquals(2, store.applyRegions(OVERWORLD, regions));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(5, 6)));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(31, 31)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(7, 7)));
    }

    @Test
    void applyRegionsHandlesNegativeRegionsAndIsAdditive() {
        Map<Long, byte[]> regions = new HashMap<>();
        byte[] mask = new byte[RegionBitmask.MASK_BYTES];
        RegionBitmask.set(mask, -1, -1);
        regions.put(RegionBitmask.regionKey(-1, -1), mask);

        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(100, 100));
        assertEquals(1, store.applyRegions(OVERWORLD, regions));

        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(-1, -1)));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(100, 100)));
        assertEquals(0, store.applyRegions(OVERWORLD, regions), "re-applying adds nothing new");
    }

    @Test
    void setForReturnsALiveViewUsableByTheCatchUpLoop() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(1, 2));
        assertTrue(store.setFor(OVERWORLD).contains(SyncedChunkStore.packChunk(1, 2)));
        assertTrue(store.setFor("minecraft:the_end").isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test --tests '*SyncedChunkStoreTest'`
Expected: FAIL — compilation error, `SyncedChunkStore` does not exist.

- [ ] **Step 3: Implement `SyncedChunkStore`**

Create `src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java`:

```java
package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.network.RegionBitmask;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which chunks one player is believed to already hold, keyed by dimension.
 *
 * <p>Keyed by dimension because the previous flat set made overworld and nether chunks at the
 * same coordinates share a key — visiting one suppressed the other. That self-hid while the set
 * died on disconnect; persisting it across sessions would have made the missing terrain permanent.
 *
 * <p>Chunk packing matches {@code ChunkPos.asLong} exactly (low 32 bits x, high 32 bits z) so
 * values are interchangeable with the rest of the codebase, but is reimplemented here to keep the
 * class free of Minecraft types and therefore testable.
 */
public final class SyncedChunkStore {
    private final Map<String, LongSet> byDimension = new ConcurrentHashMap<>();

    public static long packChunk(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int unpackChunkX(long packed) {
        return (int) packed;
    }

    public static int unpackChunkZ(long packed) {
        return (int) (packed >>> 32);
    }

    /** Live view for the worker catch-up loop, which passes it straight to DistanceGraph. */
    public LongSet setFor(String dimensionId) {
        return byDimension.computeIfAbsent(dimensionId,
            k -> LongSets.synchronize(new LongOpenHashSet()));
    }

    public boolean isSynced(String dimensionId, long chunkPos) {
        LongSet set = byDimension.get(dimensionId);
        return set != null && set.contains(chunkPos);
    }

    public void markSynced(String dimensionId, long chunkPos) {
        setFor(dimensionId).add(chunkPos);
    }

    public void markUnsynced(String dimensionId, long chunkPos) {
        LongSet set = byDimension.get(dimensionId);
        if (set != null) set.remove(chunkPos);
    }

    /** @return how many chunks were forgotten */
    public int forgetAll(String dimensionId) {
        LongSet set = byDimension.get(dimensionId);
        if (set == null) return 0;
        synchronized (set) {
            int n = set.size();
            set.clear();
            return n;
        }
    }

    /**
     * Forgets every chunk within a circular radius, matching how DistanceGraph measures range so
     * the catch-up loop re-sends exactly what was forgotten.
     *
     * @return how many chunks were forgotten
     */
    public int forgetWithin(String dimensionId, int centerChunkX, int centerChunkZ, int radiusChunks) {
        LongSet set = byDimension.get(dimensionId);
        if (set == null) return 0;
        long maxDistSq = (long) radiusChunks * radiusChunks;
        int removed = 0;
        synchronized (set) {
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                long packed = it.nextLong();
                long dx = unpackChunkX(packed) - (long) centerChunkX;
                long dz = unpackChunkZ(packed) - (long) centerChunkZ;
                if (dx * dx + dz * dz <= maxDistSq) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Adds every chunk named by a set of region bitmasks. Additive by design: a client uploads its
     * known set in several packets, and each is merged as it arrives.
     *
     * @return how many chunks were newly added
     */
    public int applyRegions(String dimensionId, Map<Long, byte[]> regions) {
        LongSet set = setFor(dimensionId);
        int added = 0;
        synchronized (set) {
            for (Map.Entry<Long, byte[]> entry : regions.entrySet()) {
                int baseX = RegionBitmask.regionBaseChunkX(entry.getKey());
                int baseZ = RegionBitmask.regionBaseChunkZ(entry.getKey());
                byte[] mask = entry.getValue();
                if (mask.length != RegionBitmask.MASK_BYTES) continue;
                for (int dz = 0; dz < 32; dz++) {
                    for (int dx = 0; dx < 32; dx++) {
                        if (RegionBitmask.get(mask, baseX + dx, baseZ + dz)
                            && set.add(packChunk(baseX + dx, baseZ + dz))) {
                            added++;
                        }
                    }
                }
            }
        }
        return added;
    }

    public int size(String dimensionId) {
        LongSet set = byDimension.get(dimensionId);
        return set == null ? 0 : set.size();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test`
Expected: PASS — all Task 1 and Task 2 tests green. If fastutil cannot be resolved from the test source set, the `sourceSets` block from Task 1 Step 1 is missing or misplaced.

- [ ] **Step 5: Swap `PlayerTracker` over to the store**

In `src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java`:

Replace the field declaration at line 23:

```java
    private final Map<UUID, SyncedChunkStore> syncedChunks;
```

Replace `addPlayer` (lines 34–39):

```java
    public void addPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        players.put(id, player);
        syncedChunks.computeIfAbsent(id, k -> new SyncedChunkStore());
    }
```

Replace `getSyncedChunks` (lines 89–91) and add the helpers:

```java
    public SyncedChunkStore getStore(java.util.UUID uuid) {
        return syncedChunks.get(uuid);
    }

    public it.unimi.dsi.fastutil.longs.LongSet getSyncedChunks(
            java.util.UUID uuid, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        SyncedChunkStore store = syncedChunks.get(uuid);
        return store == null ? null : store.setFor(dimensionId(dimension));
    }

    /** Stable string key for a dimension, e.g. "minecraft:overworld". */
    public static String dimensionId(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return dimension.location().toString();
    }
```

Delete the now-unused `it.unimi.dsi.fastutil.longs.LongSets` usage in the constructor if the compiler flags it.

- [ ] **Step 6: Update the call sites**

In `src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`, replace `setSyncedState` (lines 185–194):

```java
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
```

In `broadcastLODData`, change both call sites to pass `dimension` (which is already in scope as a local at line 206):

```java
                setSyncedState(player, dimension, pos, false);
```
```java
            setSyncedState(player, dimension, pos, accepted);
```

In `sendLODData`, hoist the dimension and pass it:

```java
    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        ResourceKey<Level> dimension = chunk.getLevel().dimension();
        List<LodSendQueue.PendingSection> sections = snapshotSections(chunk);

        if (sections.isEmpty()) {
            setSyncedState(player, dimension, pos, false);
            return;
        }

        boolean accepted = LodSendQueue.getInstance().enqueue(player, dimension,
            pos, minY, sections, chunk.getLevel().registryAccess());
        setSyncedState(player, dimension, pos, accepted);
    }
```

In `src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java:233`, add the dimension argument:

```java
                        var synced = PlayerTracker.getInstance()
                            .getSyncedChunks(player.getUUID(), player.level().dimension());
```

- [ ] **Step 7: Verify it compiles and tests still pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew build`
Expected: BUILD SUCCESSFUL. Any remaining `getSyncedChunks(UUID)` single-argument call is a compile error and must be updated — do not add an overload to silence it, the whole point is that every caller states its dimension.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java
git commit -m "fix: key the per-player synced chunk set by dimension

One flat LongSet per player meant chunk (0,0) in the overworld and in
the nether shared a key, so visiting one suppressed LOD delivery for the
other. It self-hid because the set died on disconnect; persisting it
across sessions would have made the missing terrain permanent.

Extracts SyncedChunkStore, which also carries the forget operations
/voxygen refresh needs and is unit-testable without a game harness."
```

---

### Task 3: Protocol — version handshake and the known-chunks upload

`PayloadTypeRegistry.playC2S().register(HandshakePayload.TYPE, ...)` at `NetworkHandler.java:177` already registers a C2S direction that nothing sends or receives. This task makes the C2S channel real.

**Files:**
- Modify: `src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java` (lines 26–52, 176–183, 291–293)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/network/NetworkState.java` (add protocol field)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java:20-25`

**Interfaces:**
- Consumes: `RegionBitmask.decode` (Task 1), `SyncedChunkStore.applyRegions` and `PlayerTracker.dimensionId`/`getStore` (Task 2).
- Produces:
  - `int NetworkHandler.PROTOCOL_VERSION` = 2
  - `record NetworkHandler.KnownChunksPayload(ResourceKey<Level> dimension, boolean last, byte[] body)` with `TYPE` and `CODEC`
  - `record NetworkHandler.HandshakePayload(boolean serverHasMod, int protocolVersion)`
  - `static void NetworkState.setServerProtocol(int version)` / `static int NetworkState.getServerProtocol()`
  - `static boolean NetworkState.supportsKnownChunks()` — true when the server advertised >= 2

- [ ] **Step 1: Add the protocol version to the handshake**

In `NetworkHandler.java`, add next to the existing ID constants (after line 28):

```java
    public static final ResourceLocation KNOWN_CHUNKS_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":known_chunks");

    /**
     * Bumped whenever the payload wire format changes. The client refuses to upload its known-chunk
     * set unless the server advertises at least 2, because sending a payload a server has not
     * registered can drop the connection.
     */
    public static final int PROTOCOL_VERSION = 2;
```

Replace the `HandshakePayload` record (lines 36–52):

```java
    public record HandshakePayload(boolean serverHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readVarInt());
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
```

Update `sendHandshake` (line 292):

```java
        ServerPlayNetworking.send(player, new HandshakePayload(true, PROTOCOL_VERSION));
```

- [ ] **Step 2: Add the known-chunks payload**

Add this record to `NetworkHandler.java`, after the `LODDataPayload` record:

```java
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
```

- [ ] **Step 3: Register the payload and the server receiver**

Replace `NetworkHandler.init()` (lines 176–183):

```java
    public static void init() {
        PayloadTypeRegistry.playC2S().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakePayload.TYPE, HandshakePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(KnownChunksPayload.TYPE, KnownChunksPayload.CODEC);

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
     */
    private static void receiveKnownChunks(ServerPlayer player, KnownChunksPayload payload) {
        if (!com.ethan.voxyworldgenv2.core.Config.DATA.rememberSentChunks) return;

        var tracker = PlayerTracker.getInstance();
        var store = tracker.getStore(player.getUUID());
        if (store == null) return;

        String dim = PlayerTracker.dimensionId(payload.dimension());
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
            tracker.clearGate(player.getUUID(), dim);
            VoxyWorldGenV2.LOGGER.info("{} reports {} known chunks in {}; skipping re-send",
                player.getName().getString(), store.size(dim), dim);
        }
    }
```

Note: `PlayerTracker.clearGate` is added in Task 5. Until then this will not compile — add a temporary no-op `clearGate(UUID, String)` to `PlayerTracker` now and fill it in during Task 5:

```java
    /** Filled in by the join-gate work; a no-op until then. */
    public void clearGate(java.util.UUID uuid, String dimensionId) {}
```

Add the import `com.ethan.voxyworldgenv2.network.RegionBitmask` is unnecessary (same package); add `import net.minecraft.server.level.ServerPlayer;` if not already present (it is, at line 15).

- [ ] **Step 4: Track the server's protocol version on the client**

Add to `src/main/java/com/ethan/voxyworldgenv2/network/NetworkState.java`, next to `serverConnected`:

```java
    private static volatile int serverProtocol = 0;

    public static void setServerProtocol(int version) {
        serverProtocol = version;
    }

    public static int getServerProtocol() {
        return serverProtocol;
    }

    /** The server registered the known-chunks payload, so sending it will not drop the connection. */
    public static boolean supportsKnownChunks() {
        return serverConnected && serverProtocol >= 2;
    }
```

And reset it inside the existing `setServerConnected` `if (!connected)` block:

```java
            serverProtocol = 0;
```

Update the client handshake receiver in `NetworkClientHandler.java` (lines 20–25):

```java
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HandshakePayload.TYPE, (payload, context) -> {
            boolean serverHasMod = payload.serverHasMod();
            int protocol = payload.protocolVersion();
            context.client().execute(() -> {
                NetworkState.setServerConnected(serverHasMod);
                NetworkState.setServerProtocol(protocol);
            });
        });
```

Note the ordering: `setServerConnected` clears the protocol when passed `false`, so it must be called before `setServerProtocol`.

- [ ] **Step 5: Add the config flag this task references**

In `src/main/java/com/ethan/voxyworldgenv2/core/Config.java`, add to `ConfigData` after `headlessPlayers`:

```java
        // Master switch for client LOD memory. When false the server ignores uploaded known-chunk
        // sets and re-streams everything, exactly as it did before that feature existed. This is
        // the rollback: the failure mode of remembering wrongly is invisible terrain.
        public boolean rememberSentChunks = true;
```

- [ ] **Step 6: Verify it compiles**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew build`
Expected: BUILD SUCCESSFUL, existing tests still green.

- [ ] **Step 7: Manual verification**

Deploy and confirm the handshake still works end to end — a protocol mismatch here presents as an instant disconnect, so this must be checked before building on it.

```bash
~/mc-test/deploy-all.sh
cd ~/mc-test/vanilla && ./run.sh    # or restart it however it is currently running
```

Expected in `~/mc-test/vanilla/logs/latest.log`: `voxy networking initialized (protocol 2)`. Connect a client built from the same jar and confirm no `DecoderException` on `custom_payload` in the client log. No known-chunks traffic is expected yet — nothing sends it until Task 4.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java src/main/java/com/ethan/voxyworldgenv2/network/NetworkState.java src/main/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java src/main/java/com/ethan/voxyworldgenv2/core/Config.java src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java
git commit -m "feat: protocol version handshake and known-chunks C2S payload

The C2S handshake type was registered but nothing ever sent or received
it. Adds a real C2S channel carrying the client's known-chunk bitmasks,
gated behind a protocol version so a client never sends a payload an
older server has not registered.

PROTOCOL CHANGE: client and server jars must be deployed together."
```

---

### Task 4: Client-side LOD memory

**Files:**
- Create: `src/main/java/com/ethan/voxyworldgenv2/client/LodMemory.java`
- Modify: `src/main/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java:35-88` (record on successful ingest)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2Client.java:18-42` (tick and disconnect hooks)

**Interfaces:**
- Consumes: `RegionBitmask.*` (Task 1), `NetworkHandler.KnownChunksPayload` and `NetworkState.supportsKnownChunks()` (Task 3).
- Produces:
  - `static void LodMemory.record(int chunkX, int chunkZ)`
  - `static void LodMemory.tick(Minecraft client)` — detects dimension change, debounced flush
  - `static void LodMemory.onDisconnect()`

- [ ] **Step 1: Implement `LodMemory`**

Create `src/main/java/com/ethan/voxyworldgenv2/client/LodMemory.java`:

```java
package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import com.ethan.voxyworldgenv2.network.NetworkState;
import com.ethan.voxyworldgenv2.network.RegionBitmask;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which chunks this client has successfully ingested into Voxy, so a reconnect does not
 * re-stream terrain Voxy already holds on disk.
 *
 * <p>A chunk is recorded only after ingest succeeds — the record must mean "Voxy has this", not
 * "a packet arrived", or the server will skip chunks that never made it in.
 *
 * <p>Keyed by a hash of the server address rather than by Voxy's own {@code WorldIdentifier}: this
 * key only has to be stable per server, and the codebase already carries enough version-fragile
 * reflection against Voxy internals. Known limitation — reaching one server by LAN IP and by
 * hostname yields two memory files, costing one redundant re-stream.
 */
@Environment(EnvType.CLIENT)
public final class LodMemory {
    private static final long FLUSH_INTERVAL_MS = 30_000L;

    private static final Map<Long, byte[]> regions = new HashMap<>();
    private static String worldKey;
    private static String dimensionId;
    private static ResourceKey<Level> dimension;
    private static boolean dirty;
    private static long lastFlushMs;

    // The handshake that tells us the server's protocol version arrives asynchronously, and the
    // client level can appear before it does. Uploading straight from switchTo() would therefore
    // silently no-op on a fast join, the server's gate would time out, and the whole radius would
    // re-stream — the exact bug this class exists to prevent. So the upload is deferred until the
    // handshake has landed, and abandoned if it never does (server without the mod).
    private static boolean pendingUpload;
    private static long pendingSinceMs;
    private static final long UPLOAD_WAIT_MS = 15_000L;

    private LodMemory() {}

    public static synchronized void record(int chunkX, int chunkZ) {
        if (dimensionId == null) return;
        byte[] mask = regions.computeIfAbsent(
            RegionBitmask.regionKey(chunkX, chunkZ), k -> new byte[RegionBitmask.MASK_BYTES]);
        if (!RegionBitmask.get(mask, chunkX, chunkZ)) {
            RegionBitmask.set(mask, chunkX, chunkZ);
            dirty = true;
        }
    }

    /** Called every client tick: detects a dimension change and flushes on a debounce. */
    public static synchronized void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }

        ResourceKey<Level> current = level.dimension();
        if (!current.equals(dimension)) {
            switchTo(client, current);
            return;
        }

        long now = System.currentTimeMillis();

        if (pendingUpload) {
            if (NetworkState.supportsKnownChunks()) {
                upload();
                pendingUpload = false;
            } else if (now - pendingSinceMs > UPLOAD_WAIT_MS) {
                // No handshake in 15s: this server does not have the mod, or is too old.
                pendingUpload = false;
            }
        }

        if (dirty && now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            flush();
            lastFlushMs = now;
        }
    }

    public static synchronized void onDisconnect() {
        flush();
        regions.clear();
        worldKey = null;
        dimensionId = null;
        dimension = null;
        dirty = false;
        pendingUpload = false;
    }

    /** Flush the previous dimension, load the new one, upload it. */
    private static void switchTo(Minecraft client, ResourceKey<Level> newDimension) {
        flush();
        regions.clear();

        worldKey = worldKeyFor(client);
        dimension = newDimension;
        dimensionId = newDimension.location().toString();
        dirty = false;
        lastFlushMs = System.currentTimeMillis();

        Path file = fileFor(worldKey, dimensionId);
        try {
            if (Files.exists(file)) {
                regions.putAll(RegionBitmask.readFile(Files.readAllBytes(file)));
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("discarding unreadable LOD memory {}: {}", file, e.toString());
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {}
            regions.clear();
        }

        pendingUpload = true;
        pendingSinceMs = System.currentTimeMillis();
    }

    /**
     * Uploads the known set for the current dimension. Always sends at least one packet — even
     * when this client knows nothing — because the final packet is what lifts the server's join
     * gate. Only called once {@link NetworkState#supportsKnownChunks()} is true.
     */
    private static void upload() {
        List<byte[]> packets = RegionBitmask.encode(regions);
        for (int i = 0; i < packets.size(); i++) {
            ClientPlayNetworking.send(new NetworkHandler.KnownChunksPayload(
                dimension, i == packets.size() - 1, packets.get(i)));
        }
        VoxyWorldGenV2.LOGGER.info("uploaded {} known LOD regions for {} in {} packet(s)",
            regions.size(), dimensionId, packets.size());
    }

    private static void flush() {
        if (!dirty || worldKey == null || dimensionId == null) return;
        Path file = fileFor(worldKey, dimensionId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, RegionBitmask.writeFile(regions));
            dirty = false;
        } catch (Exception e) {
            // Keep the in-memory set; the session still benefits, only persistence is lost.
            VoxyWorldGenV2.LOGGER.warn("could not persist LOD memory to {}: {}", file, e.toString());
        }
    }

    static Path fileFor(String worldKey, String dimensionId) {
        return FabricLoader.getInstance().getGameDir()
            .resolve("voxyworldgenv2").resolve("lodmemory").resolve(worldKey)
            .resolve(dimensionId.replace(':', '_').replace('/', '_') + ".bin");
    }

    private static String worldKeyFor(Minecraft client) {
        String raw;
        var server = client.getCurrentServer();
        if (server != null) {
            raw = "server:" + server.ip;
        } else if (client.getSingleplayerServer() != null) {
            raw = "single:" + client.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            raw = "unknown";
        }
        return sha256Hex(raw).substring(0, 16);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JRE spec", e);
        }
    }
}
```

- [ ] **Step 2: Record chunks on successful ingest**

In `src/main/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java`, modify `handleLODData`. Add a success flag before the section loop (after line 51):

```java
        boolean anyIngested = false;
```

Inside the loop, after the `VoxyIntegration.rawIngest(...)` call at line 79, add:

```java
                anyIngested = true;
```

And after the loop closes (after line 87), add:

```java
        // Record only what actually reached Voxy: the memory must mean "Voxy has this", not
        // "a packet arrived", or the server will skip chunks that never made it in.
        if (anyIngested) {
            com.ethan.voxyworldgenv2.client.LodMemory.record(payload.pos().x, payload.pos().z);
        }
```

- [ ] **Step 3: Hook the client lifecycle**

In `src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2Client.java`, replace the DISCONNECT and tick registrations:

```java
        // reset connection state on disconnect
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            com.ethan.voxyworldgenv2.network.NetworkState.setServerConnected(false);
            com.ethan.voxyworldgenv2.client.LodMemory.onDisconnect();
        });

        // tick network stats and the LOD memory (dimension change detection + debounced flush)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.ethan.voxyworldgenv2.network.NetworkState.tick();
            com.ethan.voxyworldgenv2.client.LodMemory.tick(client);
        });
```

- [ ] **Step 4: Verify it compiles**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew build`
Expected: BUILD SUCCESSFUL, existing tests still green.

- [ ] **Step 5: Manual verification**

```bash
~/mc-test/deploy-all.sh
```

Restart the `vanilla` server, transfer `~/mc-test/client/vanilla.zip` to the Windows client, and connect. Rendering must be tested on Windows — this Mac caps at OpenGL 4.1 and Voxy needs 4.3+.

Expected, in order:
1. Client log: `uploaded 0 known LOD regions for minecraft:overworld in 1 packet(s)` on first join to a fresh world.
2. Fly around until LOD terrain fills in, then disconnect.
3. `ls ~/.minecraft/voxyworldgenv2/lodmemory/*/minecraft_overworld.bin` (adjust for the instance's game dir) — the file exists and is non-empty.
4. Reconnect. Client log now reports a non-zero region count.
5. Server log: `<name> reports N known chunks in minecraft:overworld; skipping re-send`.

At this point the server logs the known set but does not yet act on it — the send paths are gated in Task 5. Do not expect reduced traffic yet.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ethan/voxyworldgenv2/client/LodMemory.java src/main/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2Client.java
git commit -m "feat: persist and upload the client's known LOD chunk set

Records a chunk only after ingest into Voxy actually succeeds, persists
per server-world and dimension, and uploads on join and dimension change.
The server logs the set but does not act on it yet."
```

---

### Task 5: Join gate and send-path gating

Three server paths push LOD data. This task makes them all respect the synced set, and adds the gate that stops the worker blasting during the second the client's upload is in flight.

**Files:**
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java` (replace the stub `clearGate` from Task 3)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/Config.java` (add `knownChunksTimeoutSeconds`)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java` (`broadcastLODData`, `sendLODData`)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/event/ServerEventHandler.java:28-31` and `:41-51`
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java:509-513`

**Interfaces:**
- Consumes: `SyncedChunkStore` and `PlayerTracker.dimensionId` (Task 2).
- Produces:
  - `void PlayerTracker.armGate(UUID uuid, String dimensionId)`
  - `void PlayerTracker.clearGate(UUID uuid, String dimensionId)` (replaces the Task 3 stub)
  - `boolean PlayerTracker.isGated(UUID uuid, String dimensionId)`

- [ ] **Step 1: Add the gate to `PlayerTracker`**

Add the field next to `syncedChunks`:

```java
    /**
     * When a player entered a dimension and has not yet uploaded what they already have.
     * Keyed UUID -> dimension id -> millis at which the wait started.
     */
    private final Map<UUID, Map<String, Long>> awaitingKnownSet = new ConcurrentHashMap<>();
```

Replace the Task 3 stub `clearGate` with the real trio:

```java
    public void armGate(UUID uuid, String dimensionId) {
        if (!Config.DATA.rememberSentChunks) return;
        awaitingKnownSet.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(dimensionId, System.currentTimeMillis());
    }

    public void clearGate(UUID uuid, String dimensionId) {
        Map<String, Long> byDim = awaitingKnownSet.get(uuid);
        if (byDim != null) byDim.remove(dimensionId);
    }

    /**
     * True while we are still waiting for this player's known-chunk upload for this dimension.
     *
     * <p>Without this the worker starts re-streaming during the second the upload is in flight,
     * which is exactly the traffic the feature exists to avoid. The timeout is what makes a
     * vanilla or older client — which never uploads — behave as it did before.
     */
    public boolean isGated(UUID uuid, String dimensionId) {
        if (!Config.DATA.rememberSentChunks) return false;
        Map<String, Long> byDim = awaitingKnownSet.get(uuid);
        if (byDim == null) return false;
        Long since = byDim.get(dimensionId);
        if (since == null) return false;
        if (System.currentTimeMillis() - since > Config.DATA.knownChunksTimeoutSeconds * 1000L) {
            byDim.remove(dimensionId);
            return false;
        }
        return true;
    }
```

Also clear gate state in `removePlayer` and `reconcile` alongside `syncedChunks.remove(...)`:

```java
        awaitingKnownSet.remove(id);
```
```java
                awaitingKnownSet.remove(entry.getKey());
```

And in `clear()`:

```java
        awaitingKnownSet.clear();
```

- [ ] **Step 2: Add the timeout config field**

In `Config.ConfigData`, after `rememberSentChunks`:

```java
        // How long to withhold LOD data from a joining player while waiting for their client to
        // upload what it already has. A vanilla or older client never uploads, so this timeout is
        // what makes it behave exactly as it did before the feature existed. 0 disables the gate.
        public int knownChunksTimeoutSeconds = 10;
```

- [ ] **Step 3: Enforce the gate at the single send boundary**

In `NetworkHandler.java`, add a helper next to `setSyncedState`:

```java
    /**
     * Enforced at the one point every send path passes through, so block-update pushes cannot leak
     * through the join window. A gated send is dropped, not queued, and the chunk is left unsynced
     * so the catch-up path collects it once the gate lifts.
     */
    private static boolean gated(ServerPlayer player, ResourceKey<Level> dimension) {
        return PlayerTracker.getInstance()
            .isGated(player.getUUID(), PlayerTracker.dimensionId(dimension));
    }
```

In `broadcastLODData`, inside the player loop, immediately after the existing dimension/distance `continue`:

```java
            if (gated(player, dimension)) {
                setSyncedState(player, dimension, pos, false);
                continue;
            }
```

In `sendLODData`, after the `sections.isEmpty()` block:

```java
        if (gated(player, dimension)) {
            setSyncedState(player, dimension, pos, false);
            return;
        }
```

- [ ] **Step 4: Stop `onChunkLoad` re-sending chunks the client already has**

Replace `ServerEventHandler.onChunkLoad` (lines 41–51):

```java
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        // re-ingest the freshly-loaded chunk so Voxy receives biome data with correct
        // neighbor context (fixes hard snow/biome blend edges on new worlds, issue #40).
        // also handles syncing pre-generated chunks that couldn't be sent at generation
        // time because the player wasn't loaded yet (issue #50).
        //
        // Skipped for a player who already holds the chunk: the issue-#40 re-send still happens
        // the first time a chunk is delivered, and a client that already has that version does not
        // need it again. Without this check every chunk load re-streams to every nearby player.
        String dim = PlayerTracker.dimensionId(level.dimension());
        long key = chunk.getPos().toLong();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (player.level() != level) continue;
            var store = PlayerTracker.getInstance().getStore(player.getUUID());
            if (store != null && store.isSynced(dim, key)) continue;
            NetworkHandler.sendLODData(player, chunk);
        }
    }
```

Add the import for `PlayerTracker` if the file does not already have it (it does, line 5).

- [ ] **Step 5: Arm the gate on join and on dimension change**

In `ServerEventHandler.onPlayerJoin` (lines 28–31):

```java
    public static void onPlayerJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        PlayerTracker.getInstance().addPlayer(player);
        PlayerTracker.getInstance().armGate(player.getUUID(),
            PlayerTracker.dimensionId(player.level().dimension()));
        NetworkHandler.sendHandshake(player);
    }
```

In `ChunkGenerationManager.checkPlayerMovement`, extend the existing dimension-change branch at lines 509–513:

```java
            ResourceKey<Level> currentLevelKey = player.level().dimension();
            ResourceKey<Level> lastLevelKey = lastPlayerLevels.put(player.getUUID(), currentLevelKey);
            if (!currentLevelKey.equals(lastLevelKey)) {
                PlayerTracker.getInstance().armGate(player.getUUID(),
                    PlayerTracker.dimensionId(currentLevelKey));
                pauseForTransition(player.getName().getString(), currentLevelKey, lastLevelKey == null);
            }
```

- [ ] **Step 6: Verify it compiles**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew build`
Expected: BUILD SUCCESSFUL, existing tests still green.

- [ ] **Step 7: Manual verification — this is the acceptance test for the whole feature**

```bash
~/mc-test/deploy-all.sh
```

Restart `vanilla`, transfer the client zip, then:

1. Connect. Fly until LOD terrain fills in. Run `/voxygen traffic` and note total bytes sent.
2. Disconnect and reconnect.
3. Run `/voxygen traffic` again.

Expected: LOD egress after reconnect is near zero while the distant terrain is still drawn. Server log shows `reports N known chunks`. This is the behaviour the whole feature exists to produce — if traffic is still high, stop and diagnose before continuing to Task 6.

Then verify the compatibility path: set `rememberSentChunks: false` in `~/mc-test/vanilla/config/voxyworldgenv2.json`, run `/voxygen reload`, reconnect, and confirm the full re-stream returns.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java src/main/java/com/ethan/voxyworldgenv2/core/Config.java src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java src/main/java/com/ethan/voxyworldgenv2/event/ServerEventHandler.java src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java
git commit -m "feat: skip LOD chunks the client already holds

Adds a join gate so nothing is sent while the client's known-set upload
is in flight, enforced at the single send boundary so block-update
pushes cannot leak through it. onChunkLoad now consults the synced set
instead of re-sending on every chunk load.

rememberSentChunks=false restores the previous behaviour without a
rollback."
```

---

### Task 6: `/voxygen refresh`

**Files:**
- Modify: `src/main/java/com/ethan/voxyworldgenv2/command/VoxyGenCommand.java` (lines 36–55, add builders and handlers)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/Config.java` (two more fields)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java` (refresh radius override)
- Modify: `src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java:236-240` (apply the override)

**Interfaces:**
- Consumes: `SyncedChunkStore.forgetAll`/`forgetWithin`, `PlayerTracker.getStore`/`dimensionId` (Task 2).
- Produces:
  - `void PlayerTracker.setRefreshRadius(UUID uuid, String dimensionId, int radius)`
  - `int PlayerTracker.getRefreshRadius(UUID uuid, String dimensionId)` — 0 when none
  - `void PlayerTracker.clearRefreshRadius(UUID uuid, String dimensionId)`

- [ ] **Step 1: Add config fields**

In `Config.ConfigData`, after `knownChunksTimeoutSeconds`:

```java
        // Permission level required for /voxygen refresh. 0 lets any player refresh themselves.
        // Targeting another player always requires level 2 regardless of this value, so lowering
        // it cannot let one player force egress onto another.
        public int refreshPermissionLevel = 2;
        // What "/voxygen refresh near" means, in chunks.
        public int refreshDefaultRadius = 16;
```

- [ ] **Step 2: Add the refresh radius override to `PlayerTracker`**

```java
    /** Catch-up radius override while a /voxygen refresh drains. UUID -> dimension id -> chunks. */
    private final Map<UUID, Map<String, Integer>> refreshRadius = new ConcurrentHashMap<>();

    public void setRefreshRadius(UUID uuid, String dimensionId, int radius) {
        refreshRadius.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(dimensionId, radius);
    }

    public int getRefreshRadius(UUID uuid, String dimensionId) {
        Map<String, Integer> byDim = refreshRadius.get(uuid);
        if (byDim == null) return 0;
        Integer r = byDim.get(dimensionId);
        return r == null ? 0 : r;
    }

    public void clearRefreshRadius(UUID uuid, String dimensionId) {
        Map<String, Integer> byDim = refreshRadius.get(uuid);
        if (byDim != null) byDim.remove(dimensionId);
    }
```

Clear it alongside the gate in `removePlayer`, `reconcile` and `clear()`:

```java
        refreshRadius.remove(id);
```

- [ ] **Step 3: Apply the override in the catch-up loop**

In `ChunkGenerationManager.java`, in the catch-up block, replace the radius computation at line 237:

```java
                        DimensionState ds = getOrSetupState((ServerLevel) player.level());
                        String dimId = PlayerTracker.dimensionId(player.level().dimension());
                        int baseRadius = ds.tellusActive
                            ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
                        // A /voxygen refresh can ask for a wider sweep than generationRadius. Widening
                        // the catch-up radius is what re-sends those chunks without a second send path.
                        int refreshOverride = PlayerTracker.getInstance()
                            .getRefreshRadius(player.getUUID(), dimId);
                        int radius = Math.max(baseRadius, refreshOverride);
```

The `List<ChunkPos> syncBatch = new ArrayList<>();` line that followed the old radius computation
stays exactly as it is. After the `collectCompletedInRange` call, drop the override once the
refresh has drained:

```java
                        ds.distanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced, syncBatch, 64);

                        if (syncBatch.isEmpty() && refreshOverride > 0) {
                            PlayerTracker.getInstance().clearRefreshRadius(player.getUUID(), dimId);
                        }
```

- [ ] **Step 4: Restructure the command permissions**

In `VoxyGenCommand.java`, replace `register` (lines 36–55):

```java
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The root is visible to anyone who could use ANY subtree, so refresh can carry a
        // configurable level of its own. Every pre-existing subtree keeps op level 2 explicitly.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voxygen")
            .requires(src -> src.hasPermission(
                Math.min(PERMISSION_OP, Math.max(0, Config.DATA.refreshPermissionLevel))));

        root.then(op(buildStatus()));
        root.then(op(buildRadius()));
        root.then(op(buildTasks()));
        root.then(op(buildQueue()));
        root.then(op(buildEnabled()));
        root.then(op(buildRateLimit()));
        root.then(op(buildLogInterval()));
        root.then(op(buildSettings()));
        root.then(op(buildHeadless()));
        root.then(op(buildLog()));
        root.then(op(buildTraffic()));
        root.then(op(buildReload()));
        root.then(buildRefresh());
        root.executes(VoxyGenCommand::status);

        dispatcher.register(root);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> op(ArgumentBuilder<CommandSourceStack, ?> node) {
        return node.requires(src -> src.hasPermission(PERMISSION_OP));
    }
```

Guard the bare-root `status` so a non-op who can only reach `refresh` does not get it. Add at the top of `status` (line 132):

```java
        if (!ctx.getSource().hasPermission(PERMISSION_OP)) {
            reply(ctx, "usage: /voxygen refresh <near|chunks|all>");
            return 0;
        }
```

- [ ] **Step 5: Build the refresh subtree**

Add these imports to `VoxyGenCommand.java`:

```java
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerLevel;
```

Add the builder. Each of `near`, `<chunks>` and `all` is an explicit node, because Brigadier handles optional middle arguments badly:

```java
    /** The re-send sweep is capped even for "all": collectCompletedInRange walks the distance
     *  graph, and an unbounded radius would make that walk arbitrarily expensive on the worker. */
    private static final int REFRESH_ALL_RADIUS = 512;

    private static ArgumentBuilder<CommandSourceStack, ?> buildRefresh() {
        return Commands.literal("refresh")
            .requires(src -> src.hasPermission(Math.max(0, Config.DATA.refreshPermissionLevel)))
            .executes(ctx -> refresh(ctx, Config.DATA.refreshDefaultRadius, false, null, null))
            .then(refreshTarget(Commands.literal("near"), c -> Config.DATA.refreshDefaultRadius, false))
            .then(refreshTarget(Commands.literal("all"), c -> REFRESH_ALL_RADIUS, true))
            .then(refreshTarget(Commands.argument("chunks", IntegerArgumentType.integer(1, 512)),
                c -> IntegerArgumentType.getInteger(c, "chunks"), false));
    }

    /**
     * Attaches the shared [player [dimension]] tail to one radius node.
     *
     * <p>{@code all} is passed explicitly rather than inferred from the radius. Inferring it
     * would make the literal `/voxygen refresh 512` silently mean "forget the whole dimension",
     * which is a different operation that happens to share a number.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> refreshTarget(
            ArgumentBuilder<CommandSourceStack, ?> node,
            java.util.function.Function<CommandContext<CommandSourceStack>, Integer> radius,
            boolean all) {
        return node
            .executes(ctx -> refresh(ctx, radius.apply(ctx), all, null, null))
            .then(Commands.argument("player", EntityArgument.player())
                .requires(src -> src.hasPermission(PERMISSION_OP))
                .executes(ctx -> refresh(ctx, radius.apply(ctx), all,
                    EntityArgument.getPlayer(ctx, "player"), null))
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .executes(ctx -> refresh(ctx, radius.apply(ctx), all,
                        EntityArgument.getPlayer(ctx, "player"),
                        DimensionArgument.getDimension(ctx, "dimension")))));
    }
```

- [ ] **Step 6: Implement the handler**

```java
    /**
     * Clears synced bits so the worker's catch-up loop re-sends them. Deliberately sends nothing
     * itself and never queues ungenerated chunks: "all" forgets the whole dimension but the
     * re-send sweep is still capped at REFRESH_ALL_RADIUS, and the rest arrives as the player
     * travels. The reply states the effective radius so that is never a surprise.
     */
    private static int refresh(CommandContext<CommandSourceStack> ctx, int radius, boolean all,
                               ServerPlayer explicitTarget, ServerLevel explicitDimension) {
        ServerPlayer target = explicitTarget;
        if (target == null) {
            if (!(ctx.getSource().getEntity() instanceof ServerPlayer self)) {
                reply(ctx, "console has no position; name a player: /voxygen refresh all <player>");
                return 0;
            }
            target = self;
        }

        ServerLevel level = explicitDimension != null ? explicitDimension : (ServerLevel) target.level();
        String dim = PlayerTracker.dimensionId(level.dimension());

        var store = PlayerTracker.getInstance().getStore(target.getUUID());
        if (store == null) {
            reply(ctx, "§c" + target.getName().getString() + " is not tracked yet; try again in a moment");
            return 0;
        }

        int forgotten = all
            ? store.forgetAll(dim)
            : store.forgetWithin(dim, target.chunkPosition().x, target.chunkPosition().z, radius);

        PlayerTracker.getInstance().setRefreshRadius(target.getUUID(), dim, radius);

        reply(ctx, String.format("forgot §b%d§r chunk(s) for §b%s§r in §b%s§r; re-sending within §b%d§r chunks",
            forgotten, target.getName().getString(), dim, radius));
        if (all) {
            reply(ctx, "§7 \"all\" forgets the whole dimension; anything beyond "
                + REFRESH_ALL_RADIUS + " chunks arrives as they travel");
        }
        if (forgotten == 0) {
            reply(ctx, "§7 nothing was remembered there, so nothing will be re-sent");
        }
        return 1;
    }
```

- [ ] **Step 7: Verify it compiles**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew build`
Expected: BUILD SUCCESSFUL, existing tests still green.

- [ ] **Step 8: Manual verification**

```bash
~/mc-test/deploy-all.sh
```

Restart `vanilla`, connect, let terrain fill, then over RCON (port 25579, password `vwgtest`) or in game:

| Command | Expected |
| --- | --- |
| `/voxygen refresh` | "forgot N chunk(s) … re-sending within 16 chunks", traffic resumes briefly |
| `/voxygen refresh 32` | larger N than the previous line |
| `/voxygen refresh all` | forgets everything, prints the 512-chunk caveat |
| `/voxygen refresh all @s the_end` | targets the End without naming a player |
| `/voxygen refresh near <otherplayer>` | works for an op |
| `/voxygen reload` | still reloads config — unchanged |

Then set `refreshPermissionLevel: 0`, `/voxygen reload`, reconnect a non-op client, and confirm `/voxygen refresh` works for them but `/voxygen refresh all <someone-else>` and `/voxygen status` do not.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/ethan/voxyworldgenv2/command/VoxyGenCommand.java src/main/java/com/ethan/voxyworldgenv2/core/Config.java src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java
git commit -m "feat: /voxygen refresh to re-send remembered LOD chunks

Clears synced bits and widens the catch-up radius until the sweep
drains, so no second send path is introduced. Ops can target another
player; refreshPermissionLevel can open self-refresh to everyone
without letting anyone force egress onto anyone else."
```

---

### Task 7: Documentation and final verification

**Files:**
- Modify: `HANDOFF.md`
- Modify: `README.md` (only if it documents the command surface — check first)
- Modify: `.gitignore` (add `logs/`)

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Check whether the README documents commands**

Run: `grep -n "voxygen" README.md`

If it lists subcommands, add `refresh` in the same style. If it does not mention them, skip the README entirely — do not invent a section.

- [ ] **Step 2: Ignore the stray logs directory**

`git status` currently shows an untracked `logs/` directory. Add to `.gitignore`:

```
logs/
```

- [ ] **Step 3: Update HANDOFF.md**

Add a new entry at the top of the "Current state" list:

```markdown
- **New (2026-08-06):** client LOD memory + `/voxygen refresh`. The client records every chunk
  it successfully ingests into Voxy as 32x32 region bitmasks
  (`client/LodMemory.java`, persisted under `<gamedir>/voxyworldgenv2/lodmemory/<worldkey>/`),
  uploads them on join and dimension change, and the server seeds its per-player synced set from
  that instead of re-streaming everything. Reconnect egress should be near zero.
  - **PROTOCOL CHANGE** (`PROTOCOL_VERSION = 2`): client and server jars must move together.
  - Fixed on the way: `PlayerTracker.syncedChunks` was one flat `LongSet` per player, so
    overworld and nether chunks at the same coordinates collided. Now `SyncedChunkStore`, keyed
    by dimension.
  - `/voxygen refresh <near|N|all> [player] [dimension]` forgets synced bits so the existing
    catch-up loop re-sends them. It never triggers generation. `all` forgets the whole dimension
    but the re-send sweep is capped at 512 chunks.
  - Rollback without redeploying: `rememberSentChunks: false` in the config.
  - This branch now has a test suite (`src/test/java`, JUnit 5) covering the bitmask codec and
    the synced store. `./gradlew build` runs it; `deploy-all.sh` therefore cannot ship a build
    with failing tests.
```

- [ ] **Step 4: Full build and test**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew clean build`
Expected: BUILD SUCCESSFUL, all tests pass. A clean build is worth it here because Task 1 changed the build script.

- [ ] **Step 5: Deploy and record the hash**

```bash
~/mc-test/deploy-all.sh
cat ~/mc-test/client/VERSION.txt
```

Copy the same jar to the Downloads transfer set, which `deploy-all.sh` does not touch:

```bash
JAR="build/libs/Voxy World Gen V2-1.21.1-2.2.4.jar"
cp "$JAR" ~/Downloads/voxyworldgenv2-1.21.1-2.2.4.jar
cp "$JAR" ~/Downloads/mods/voxyworldgenv2-1.21.1-2.2.4.jar
rm -f ~/Downloads/mods.zip && ( cd ~/Downloads && zip -qr mods.zip mods -x '*.DS_Store' )
```

Restart both servers so they load the new jar — a running server does not pick one up:

```bash
pkill -TERM -f fabric-server-launcher   # then relaunch each via its run.sh
```

- [ ] **Step 6: Commit**

```bash
git add HANDOFF.md .gitignore README.md
git commit -m "docs: record client LOD memory, /voxygen refresh, and the protocol bump"
```

---

## Self-review notes

Checked against the spec:

- Spec §1 (per-dimension synced set) → Task 2.
- Spec §2 (client memory, bit layout, file format, worldkey, flush policy) → Tasks 1 and 4.
- Spec §3 (wire protocol, splitting, version negotiation) → Tasks 1 and 3. The spec's "accumulate to 24 KB" is implemented as a fixed 180 regions per packet, which is the same bound expressed as a constant — simpler to implement and to test, and the derivation is in the `MAX_REGIONS_PER_PACKET` javadoc.
- Spec §4 (seeding, join gate, three send paths) → Tasks 3 and 5.
- Spec §5 (`/voxygen refresh`, semantics, permissions) → Task 6.
- Spec §6 (four config fields) → `rememberSentChunks` in Task 3, `knownChunksTimeoutSeconds` in Task 5, `refreshPermissionLevel` and `refreshDefaultRadius` in Task 6.
- Spec §7 (failure handling) → corrupt file and unwritable disk in Task 4 Step 1; malformed packet in Task 3 Step 3; older server in Task 3 Step 4; wiped Voxy DB is the `/voxygen refresh all` path in Task 6.
- Spec §8 (testing) → Tasks 1 and 2.

One deviation from the spec worth flagging to the reviewer: the spec left the known-set application thread unspecified. This plan applies it on the receiver thread and justifies it in the javadoc, because ordering matters across packets and the cost is bounded well below the watchdog. If profiling later shows a real hitch at radius 512, moving it off-thread needs a per-player ordered queue, not a bare `runAsync`.

Three problems found and fixed while reviewing this plan against itself:

1. **Upload race (Task 4).** The first draft uploaded from `switchTo()`, which runs the moment the client level appears. The handshake carrying the server's protocol version arrives asynchronously and can land later, so on a fast join `supportsKnownChunks()` would still be false, the upload would silently no-op, the server's gate would time out, and the entire radius would re-stream — the exact bug the feature exists to fix, and it would have looked like a timing-dependent "sometimes it works". Now deferred behind `pendingUpload` with a 15s abandon for servers that never handshake.
2. **`all` inferred from radius (Task 6).** `boolean all = radius >= REFRESH_ALL_RADIUS` made the literal `/voxygen refresh 512` mean "forget the whole dimension" rather than "forget within 512 chunks". Two different operations sharing a number. `all` is now passed explicitly.
3. **Ambiguous edit instruction (Task 6 Step 3).** The radius-computation replacement did not say what happened to the `syncBatch` declaration that sat between the lines being replaced. Now stated.
