package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the fix ported from port/26.2 (commit 2bc4971): {@code PlayerTracker}
 * must key its live-player table by {@link UUID}, not by the {@code ServerPlayer} object's
 * identity.
 *
 * <p>Minecraft constructs a brand-new {@code ServerPlayer} instance for the same UUID on respawn
 * and on dimension change. {@code Entity} does not override {@code equals}/{@code hashCode}
 * (identity semantics), so a {@code Set<ServerPlayer>} (what this class shipped with before the
 * port) probes the wrong bucket when the player later actually disconnects with their *new*
 * instance -- the old instance's entry is never removed, and the generation worker never sees an
 * empty player list. It stays anchored to a phantom forever. See HANDOFF.md and
 * convergence-audit-reverse.md finding #1.
 *
 * <p>This cannot drive the fix through {@code addPlayer}/{@code removePlayer} directly: those take
 * a real {@code ServerPlayer}, and this test environment never calls vanilla's
 * {@code Bootstrap.bootStrap()}, so both constructing a real {@code ServerPlayer} and mocking one
 * (tried with Mockito's inline mock maker; verified experimentally) fail identically --
 * {@code Entity}'s static initializer reaches {@code BuiltInRegistries} and throws
 * {@code IllegalArgumentException: Not bootstrapped}. No test in this suite constructs one for the
 * same reason (see JoinGateTest's javadoc). Instead this reaches the actual {@code players} field
 * by reflection and exercises it directly. Generics are erased at runtime, so the reflected
 * reference is deliberately kept as a raw {@code Map} -- if this field ever reverts to
 * {@code Set<ServerPlayer>}, the cast below throws {@link ClassCastException} and every test here
 * fails loudly, which is exactly the signal a revert should produce.
 */
class PlayerTrackerIdentityKeyingTest {

    private final PlayerTracker tracker = PlayerTracker.getInstance();

    @BeforeEach
    void setUp() {
        tracker.clear();
    }

    @AfterEach
    void tearDown() {
        tracker.clear();
    }

    @Test
    void playersFieldIsAMapNotAnIdentityKeyedSet() throws Exception {
        Field f = PlayerTracker.class.getDeclaredField("players");
        f.setAccessible(true);
        Object value = f.get(tracker);

        assertFalse(value instanceof Set,
            "players reverted to a Set -- that is exactly the identity-keyed phantom-player bug "
                + "(port/26.2 commit 2bc4971): a Set<ServerPlayer> keys on Entity's identity "
                + "hashCode/equals, which changes across a respawn, so the stale instance can "
                + "never be evicted");
        assertTrue(value instanceof Map,
            "players must be a Map<UUID, ServerPlayer> so a respawn's new ServerPlayer instance "
                + "overwrites the old one under the same key instead of coexisting with it");
    }

    /**
     * Simulates a respawn: the same UUID is associated with two different object identities in
     * sequence (mirroring what {@code addPlayer} does on JOIN then again after a respawn replaces
     * the tracked instance). A correct UUID-keyed map collapses this to one entry holding the
     * latest instance; the identity-keyed Set this replaced would have held both, since two
     * distinct objects never collide in a hash set keyed on their own identity.
     */
    @Test
    @SuppressWarnings("unchecked")
    void respawnReplacesTheTrackedInstanceInsteadOfLeavingAPhantom() throws Exception {
        Field f = PlayerTracker.class.getDeclaredField("players");
        f.setAccessible(true);
        Map<UUID, Object> players = (Map<UUID, Object>) f.get(tracker);

        UUID uuid = UUID.randomUUID();
        Object preRespawnInstance = new Object();
        Object postRespawnInstance = new Object();
        assertNotSame(preRespawnInstance, postRespawnInstance, "test bug: need two distinct identities");

        players.put(uuid, preRespawnInstance);
        assertEquals(1, tracker.getPlayerCount(), "sanity: one player tracked before the respawn");

        // The respawn: same UUID, a new ServerPlayer instance. addPlayer's real body is
        // `players.put(id, player)` -- reproduced here since addPlayer needs a real ServerPlayer.
        players.put(uuid, postRespawnInstance);

        assertEquals(1, tracker.getPlayerCount(),
            "a respawn must not leave the pre-respawn instance behind as a phantom entry -- "
                + "getPlayerCount() must stay at 1, not grow to 2");
        assertSame(postRespawnInstance, players.get(uuid),
            "the tracked instance for this UUID must be the new (post-respawn) one");
        assertFalse(players.containsValue(preRespawnInstance),
            "the stale pre-respawn instance must not still be reachable from the tracker");
    }

    @Test
    void reconcileWithNoServerIsANoOp() {
        assertEquals(0, tracker.reconcile(null),
            "reconcile(null) must short-circuit rather than NPE -- called every second from tick()");
    }

    @Test
    void clearEmptiesThePlayerMap() throws Exception {
        Field f = PlayerTracker.class.getDeclaredField("players");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> players = (Map<UUID, Object>) f.get(tracker);

        players.put(UUID.randomUUID(), new Object());
        players.put(UUID.randomUUID(), new Object());
        assertEquals(2, tracker.getPlayerCount());

        tracker.clear();

        assertEquals(0, tracker.getPlayerCount());
        assertTrue(tracker.getPlayers().isEmpty());
    }
}
