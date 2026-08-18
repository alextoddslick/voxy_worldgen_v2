package com.ethan.voxyworldgenv2.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two places the LOD send distance is enforced share these helpers: the live broadcast's
 * per-player block-distance check, and the catch-up sweep's radius bound. 0 chunks means
 * unlimited in both, matching the config sentinel.
 */
class SendDistanceTest {

    @Test
    void broadcastCheckConvertsChunksToBlocks() {
        // 256 chunks = 4096 blocks, the cap that used to be hardcoded.
        assertTrue(NetworkHandler.withinSendDistance(4096, 0, 256));
        assertFalse(NetworkHandler.withinSendDistance(4097, 0, 256));
        assertTrue(NetworkHandler.withinSendDistance(2896, 2896, 256), "diagonal inside the circle");
        assertFalse(NetworkHandler.withinSendDistance(2897, 2897, 256), "diagonal outside the circle");
    }

    @Test
    void zeroSendDistanceIsUnlimited() {
        assertTrue(NetworkHandler.withinSendDistance(1e9, 1e9, 0));
    }

    @Test
    void catchUpRadiusIsCappedBySendDistance() {
        assertEquals(64, NetworkHandler.capCatchUpRadius(64, 256), "under the cap passes through");
        assertEquals(256, NetworkHandler.capCatchUpRadius(512, 256), "a refresh sweep cannot outrun the send distance");
        assertEquals(512, NetworkHandler.capCatchUpRadius(512, 0), "unlimited leaves the sweep alone");
    }
}
