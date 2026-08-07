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
