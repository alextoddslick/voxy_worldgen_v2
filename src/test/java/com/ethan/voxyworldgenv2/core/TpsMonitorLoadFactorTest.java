package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The graduated load factor ported down from unified: 1.0 (healthy) at or below ~22 tps, scaling
 * linearly down to 0.0 (full stop) at or above 75ms/tick (~13 tps), instead of the old binary
 * isThrottled() cliff at a single 18-tps threshold. Simulated by feeding tick() a fixed
 * inter-tick delay via repeated calls, since it has no seam to inject a clock.
 */
class TpsMonitorLoadFactorTest {

    /** Drives `ticks` calls to tick(), each spaced `sleepMillis` apart via a busy-ish sleep. */
    private static TpsMonitor drive(int ticks, long sleepMillis) throws InterruptedException {
        TpsMonitor monitor = new TpsMonitor();
        for (int i = 0; i < ticks; i++) {
            if (sleepMillis > 0) Thread.sleep(sleepMillis);
            monitor.tick();
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
    void healthyTicksKeepLoadFactorAtOne() throws InterruptedException {
        // Well under the ~45ms soft threshold (1000/22).
        TpsMonitor monitor = drive(25, 5);
        assertEquals(1.0, monitor.loadFactor(), 0.0001);
        assertFalse(monitor.isThrottled());
    }

    @Test
    void severelyLaggedTicksDriveLoadFactorToZeroAndThrottle() throws InterruptedException {
        // Comfortably over the 75ms hard threshold.
        TpsMonitor monitor = drive(25, 120);
        assertEquals(0.0, monitor.loadFactor(), 0.0001);
        assertTrue(monitor.isThrottled());
    }

    @Test
    void moderateLagProducesAGraduatedFactorRatherThanABinaryCliff() throws InterruptedException {
        // Between soft (~45ms) and hard (75ms): must land strictly between 0 and 1, not snap to
        // either extreme -- that graduated middle ground is the entire point of this port over
        // the old isThrottled()-only design.
        TpsMonitor monitor = drive(25, 60);
        double load = monitor.loadFactor();
        assertTrue(load > 0.0 && load < 1.0,
            "expected a graduated value strictly between 0 and 1, got " + load);
        assertFalse(monitor.isThrottled(), "isThrottled() only trips once load reaches exactly 0");
    }

    @Test
    void resetRestoresFullLoadAndClearsThrottle() throws InterruptedException {
        TpsMonitor monitor = drive(25, 120);
        assertTrue(monitor.isThrottled());

        monitor.reset();

        assertEquals(1.0, monitor.loadFactor(), 0.0001);
        assertFalse(monitor.isThrottled());
    }
}
