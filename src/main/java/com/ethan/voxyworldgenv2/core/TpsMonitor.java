package com.ethan.voxyworldgenv2.core;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class TpsMonitor {
    private final long[] recentTickTimes = new long[20];
    private int tickTimeIndex = 0;
    private long lastTickNanos = 0;
    private final AtomicBoolean throttled = new AtomicBoolean(false);
    private volatile double loadFactor = 1.0;

    // Ease off below ~22 tps and stop by ~13 tps, scaling in between. Starting earlier than the
    // old fixed 18-tps/55.5ms cutoff means the worker backs off before the tick is already
    // buried, not after, and a mild dip no longer snaps straight from full speed to a dead stop.
    private static final double MSPT_SOFT = 1000.0 / 22.0;
    private static final double MSPT_HARD = 75.0;

    public void tick() {
        long now = System.nanoTime();
        long delta = 0;
        if (lastTickNanos > 0) {
            delta = now - lastTickNanos;
            recentTickTimes[tickTimeIndex] = delta;
            tickTimeIndex = (tickTimeIndex + 1) % recentTickTimes.length;
        }
        lastTickNanos = now;

        long totalTickTime = 0;
        int count = 0;
        for (long tickNanos : recentTickTimes) {
            if (tickNanos > 0) {
                totalTickTime += tickNanos;
                count++;
            }
        }

        double mspt = count > 0 ? (totalTickTime / (double) count) / 1_000_000.0 : 0.0;

        if (mspt <= MSPT_SOFT) {
            loadFactor = 1.0;
        } else if (mspt >= MSPT_HARD) {
            loadFactor = 0.0;
        } else {
            loadFactor = 1.0 - (mspt - MSPT_SOFT) / (MSPT_HARD - MSPT_SOFT);
        }
        throttled.set(loadFactor <= 0.0);
    }

    public void reset() {
        lastTickNanos = 0;
        tickTimeIndex = 0;
        Arrays.fill(recentTickTimes, 0);
        throttled.set(false);
        loadFactor = 1.0;
    }

    /** True only at the hard floor (~13 tps); the worker fully pauses dispatch on this. */
    public boolean isThrottled() {
        return throttled.get();
    }

    /** 1.0 healthy down to 0.0 overloaded; scales how much generation work to dispatch. */
    public double loadFactor() {
        return loadFactor;
    }
}
