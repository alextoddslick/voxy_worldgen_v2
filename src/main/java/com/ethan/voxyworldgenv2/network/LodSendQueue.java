package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serialises and sends LOD chunk data on a single dedicated thread, keeping that work off the
 * server main thread.
 *
 * <p><b>What can and cannot move off-thread.</b> Block-state serialisation is the expensive part —
 * {@code PalettedContainer.write} varint-encodes 4096 entries per section — but a live container
 * cannot be read off-thread: it is guarded by a {@code ThreadingDetector} and the main thread may
 * mutate it concurrently. So the main thread takes a cheap private {@code copy()} (a long[] clone,
 * no per-entry encoding) and this thread does the encoding against that copy, which nothing else
 * can see.
 *
 * <p>Biomes are serialised on the main thread instead. {@code getBiomes()} returns the read-only
 * {@code PalettedContainerRO} interface, which exposes no {@code copy()}, and a biome container
 * holds 64 entries against block states' 4096 — so inlining it costs little and avoids depending on
 * the concrete implementation type. Light layers must also be read on the main thread, but those
 * are already plain array clones.
 *
 * <p>The queue is bounded and <b>never blocks the main thread</b>: if the sender falls behind, jobs
 * are dropped and counted rather than stalling the tick loop. Dropping LOD data is cosmetic and
 * self-healing — the chunk re-syncs on the next load — whereas blocking the server is not.
 */
public final class LodSendQueue {
    private static final LodSendQueue INSTANCE = new LodSendQueue();

    /** Bounded so a player streaming thousands of chunks cannot grow this without limit. */
    private static final int MAX_QUEUED_JOBS = 512;

    /** Rolling throughput window, one bucket per second. */
    private static final int RATE_WINDOW_SECONDS = 10;

    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(MAX_QUEUED_JOBS);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong packetsSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicInteger jobsDropped = new AtomicInteger();
    private final AtomicLong throttleWaitMillis = new AtomicLong();
    private volatile Thread worker;

    /** Per-player lifetime totals, keyed by UUID so a reconnect does not resurrect a stale entry. */
    private final java.util.Map<java.util.UUID, long[]> perPlayerBytes = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per-player token buckets for the bandwidth cap. */
    private final java.util.Map<java.util.UUID, Bucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();

    private final long[] rateWindow = new long[RATE_WINDOW_SECONDS];
    private long rateWindowEpochSecond = 0;

    /**
     * Simple token bucket. Capacity is one second of budget, so a player who has been idle can
     * burst briefly and then settles to the configured rate.
     */
    private static final class Bucket {
        double tokens;
        long lastNanos = System.nanoTime();
    }

    private LodSendQueue() {}

    public static LodSendQueue getInstance() {
        return INSTANCE;
    }

    /** A block-state container copied off the live chunk, plus already-flat biome and light data. */
    public record PendingSection(int y,
                                 PalettedContainer<BlockState> states,
                                 byte[] biomes,
                                 byte[] blockLight,
                                 byte[] skyLight) {}

    private record Job(ServerPlayer player,
                       ResourceKey<Level> dimension,
                       ChunkPos pos,
                       int minY,
                       List<PendingSection> sections,
                       RegistryAccess registryAccess) {}

    public void start() {
        if (running.getAndSet(true)) return;
        queue.clear();
        Thread t = new Thread(this::loop, "Voxy-WorldGen-LodSender");
        t.setDaemon(true);
        // Below normal: this must never compete with the server tick thread.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        worker = t;
        t.start();
    }

    public void shutdown() {
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        queue.clear();
        packetsSent.set(0);
        bytesSent.set(0);
        jobsDropped.set(0);
        throttleWaitMillis.set(0);
        perPlayerBytes.clear();
        buckets.clear();
    }

    /**
     * Enqueue from the main thread. Returns immediately; never blocks.
     */
    public void enqueue(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos,
                        int minY, List<PendingSection> sections, RegistryAccess registryAccess) {
        if (!running.get() || sections.isEmpty()) return;
        if (!queue.offer(new Job(player, dimension, pos, minY, sections, registryAccess))) {
            int n = jobsDropped.incrementAndGet();
            // Log sparsely: a saturated queue produces a lot of these.
            if (n == 1 || n % 500 == 0) {
                VoxyWorldGenV2.LOGGER.warn(
                    "LOD send queue saturated, dropped {} chunk(s). Generation is outrunning the network; "
                    + "lower generationRadius or maxActiveTasks if this persists.", n);
            }
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                Job job = queue.take();
                process(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                VoxyWorldGenV2.LOGGER.error("LOD sender failed on a chunk", t);
            }
        }
    }

    private void process(Job job) {
        ServerPlayer player = job.player();
        if (player == null || player.connection == null || player.hasDisconnected()) return;

        List<NetworkHandler.LODDataPayload.SectionData> batch = new ArrayList<>();
        int batchBytes = 0;

        for (PendingSection ps : job.sections()) {
            byte[] states = serialiseStates(ps.states(), job.registryAccess());
            if (states == null) continue;

            var sd = new NetworkHandler.LODDataPayload.SectionData(
                ps.y(), states, ps.biomes(), ps.blockLight(), ps.skyLight());

            int size = states.length + ps.biomes().length
                + (ps.blockLight() != null ? ps.blockLight().length : 0)
                + (ps.skyLight() != null ? ps.skyLight().length : 0);

            if (!batch.isEmpty() && batchBytes + size > NetworkHandler.MAX_PACKET_BYTES) {
                dispatch(job, player, batch, batchBytes);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(sd);
            batchBytes += size;
        }

        if (!batch.isEmpty()) {
            dispatch(job, player, batch, batchBytes);
        }
    }

    /** Encodes our private copy. Safe here: nothing else can reach this container. */
    private byte[] serialiseStates(PalettedContainer<BlockState> states, RegistryAccess access) {
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(raw), access);
            states.write(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } catch (Throwable t) {
            VoxyWorldGenV2.LOGGER.error("failed to serialise LOD section states", t);
            return null;
        } finally {
            raw.release();
        }
    }

    private void dispatch(Job job, ServerPlayer player,
                          List<NetworkHandler.LODDataPayload.SectionData> batch, int bytes) {
        // Apply the per-player cap before sending. Throttling here, on the dedicated sender
        // thread, is safe: the main thread never blocks on this, and if the backlog grows past
        // the bounded queue the excess is dropped rather than accumulating.
        awaitBudget(player.getUUID(), bytes);

        try {
            // Fabric routes this to the connection, which hands off to the Netty event loop, so
            // calling from this thread is safe.
            ServerPlayNetworking.send(player,
                new NetworkHandler.LODDataPayload(job.dimension(), job.pos(), job.minY(), batch));
            packetsSent.incrementAndGet();
            bytesSent.addAndGet(bytes);
            recordRate(bytes);
            perPlayerBytes.computeIfAbsent(player.getUUID(), k -> new long[1])[0] += bytes;
        } catch (Throwable t) {
            // A player disconnecting mid-send is normal, not an error worth spamming about.
            VoxyWorldGenV2.LOGGER.debug("dropped LOD packet for {}", player.getName().getString(), t);
        }
    }

    /** Blocks this sender thread until the player's bandwidth budget covers {@code bytes}. */
    private void awaitBudget(java.util.UUID id, int bytes) {
        double mbps = com.ethan.voxyworldgenv2.core.Config.DATA.maxMbpsPerPlayer;
        if (mbps <= 0) return; // unlimited

        double bytesPerSec = (mbps * 1_000_000.0) / 8.0;
        Bucket b = buckets.computeIfAbsent(id, k -> new Bucket());

        synchronized (b) {
            long now = System.nanoTime();
            double elapsed = (now - b.lastNanos) / 1_000_000_000.0;
            b.lastNanos = now;
            // Refill, capped at one second of budget so idle players cannot bank unlimited burst.
            b.tokens = Math.min(bytesPerSec, b.tokens + elapsed * bytesPerSec);

            if (b.tokens < bytes) {
                double deficit = bytes - b.tokens;
                long waitMs = (long) Math.ceil((deficit / bytesPerSec) * 1000.0);
                // Cap any single wait so a tiny limit cannot wedge the sender for minutes.
                waitMs = Math.min(waitMs, 5000);
                if (waitMs > 0) {
                    throttleWaitMillis.addAndGet(waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    long after = System.nanoTime();
                    b.tokens = Math.min(bytesPerSec,
                        b.tokens + ((after - b.lastNanos) / 1_000_000_000.0) * bytesPerSec);
                    b.lastNanos = after;
                }
            }
            b.tokens = Math.max(0, b.tokens - bytes);
        }
    }

    private synchronized void recordRate(int bytes) {
        long sec = System.nanoTime() / 1_000_000_000L;
        if (sec != rateWindowEpochSecond) {
            long gap = Math.min(sec - rateWindowEpochSecond, RATE_WINDOW_SECONDS);
            for (long i = 0; i < gap; i++) {
                rateWindowEpochSecond++;
                rateWindow[(int) (rateWindowEpochSecond % RATE_WINDOW_SECONDS)] = 0;
            }
            rateWindowEpochSecond = sec;
        }
        rateWindow[(int) (sec % RATE_WINDOW_SECONDS)] += bytes;
    }

    /** Average bytes/sec over the rolling window. */
    public synchronized long getCurrentBytesPerSecond() {
        long sec = System.nanoTime() / 1_000_000_000L;
        long total = 0;
        // Skip the in-progress second so the figure is not artificially low.
        for (int i = 1; i <= RATE_WINDOW_SECONDS; i++) {
            long s = sec - i;
            if (s < 0) continue;
            total += rateWindow[(int) (s % RATE_WINDOW_SECONDS)];
        }
        return total / RATE_WINDOW_SECONDS;
    }

    public java.util.Map<java.util.UUID, Long> getPerPlayerBytes() {
        java.util.Map<java.util.UUID, Long> out = new java.util.HashMap<>();
        perPlayerBytes.forEach((k, v) -> out.put(k, v[0]));
        return out;
    }

    public long getThrottleWaitMillis() {
        return throttleWaitMillis.get();
    }

    public int getQueuedJobs() {
        return queue.size();
    }

    public int getMaxQueuedJobs() {
        return MAX_QUEUED_JOBS;
    }

    public long getPacketsSent() {
        return packetsSent.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public int getJobsDropped() {
        return jobsDropped.get();
    }

    public boolean isRunning() {
        return running.get();
    }
}
