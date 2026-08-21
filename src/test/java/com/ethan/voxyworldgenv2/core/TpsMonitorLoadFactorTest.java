package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The graduated load factor ported down from unified: 1.0 (healthy) at or below ~45ms/tick
 * (~22 tps), scaling linearly down to 0.0 (full stop) at or above 75ms/tick (~13 tps), instead of
 * the old binary isThrottled() cliff at a single threshold.
 *
 * <p>Drives {@link TpsMonitor#tick(long)} with synthetic nanosecond timestamps. An earlier version
 * of this test slept for real and asserted on wall-clock measurements; that failed at random on a
 * loaded machine, because a 5ms sleep can overshoot the 45ms soft threshold and a 60ms sleep can
 * overshoot the 75ms hard one. Deterministic timestamps remove the machine from the assertion.
 */
class TpsMonitorLoadFactorTest {

    private static final long MS = 1_000_000L;

    /** Feeds {@code ticks} evenly spaced ticks, {@code spacingMillis} apart. */
    private static TpsMonitor drive(int ticks, long spacingMillis) {
        TpsMonitor monitor = new TpsMonitor();
        long now = 1_000_000_000L;
        for (int i = 0; i < ticks; i++) {
            monitor.tick(now);
            now += spacingMillis * MS;
        }
        return monitor;
    }

    @Test
    void freshMonitorStartsAtFullLoadAndUnthrottled() {
        TpsMonitor monitor = new TpsMonitor();
        assertEquals(1.0, monitor.loadFactor(), 0.0001);
        assertFalse(monitor.isThrottled());
    }

    @Test
    void healthyTicksKeepLoadFactorAtOne() {
        TpsMonitor monitor = drive(25, 5);          // well under the ~45ms soft threshold
        assertEquals(1.0, monitor.loadFactor(), 0.0001);
        assertFalse(monitor.isThrottled());
    }

    @Test
    void severelyLaggedTicksDriveLoadFactorToZeroAndThrottle() {
        TpsMonitor monitor = drive(25, 120);        // comfortably past the 75ms hard threshold
        assertEquals(0.0, monitor.loadFactor(), 0.0001);
        assertTrue(monitor.isThrottled());
    }

    @Test
    void moderateLagProducesAGraduatedFactorRatherThanABinaryCliff() {
        // Between soft (~45.45ms) and hard (75ms). The graduated middle is the whole point of
        // this port over the old isThrottled()-only cliff.
        TpsMonitor monitor = drive(25, 60);
        double load = monitor.loadFactor();
        assertTrue(load > 0.0 && load < 1.0,
            "expected a graduated value strictly between 0 and 1, got " + load);
        assertFalse(monitor.isThrottled(), "isThrottled() only trips once load reaches exactly 0");
    }

    @Test
    void theGraduatedRampIsMonotonicAcrossTheBand() {
        // More lag must never mean a higher load factor.
        double prev = Double.MAX_VALUE;
        for (long spacing = 46; spacing <= 74; spacing += 4) {
            double load = drive(25, spacing).loadFactor();
            assertTrue(load <= prev, "loadFactor rose from " + prev + " to " + load
                + " when tick spacing grew to " + spacing + "ms");
            prev = load;
        }
    }

    @Test
    void resetRestoresFullLoadAndClearsThrottle() {
        TpsMonitor monitor = drive(25, 120);
        assertTrue(monitor.isThrottled());
        monitor.reset();
        assertEquals(1.0, monitor.loadFactor(), 0.0001);
        assertFalse(monitor.isThrottled());
    }
}
