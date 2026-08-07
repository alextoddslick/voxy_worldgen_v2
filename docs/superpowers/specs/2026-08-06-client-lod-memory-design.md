# Client LOD memory and `/voxygen refresh`

Status: approved design, not yet implemented
Date: 2026-08-06
Branch: `backport/1.21.1`

## Problem

Reconnecting to a server re-streams LOD terrain the client already has. The player waits
through a full re-send of the generation radius, rate-limited to 2 Mbps by default, for data
already sitting in Voxy's database.

## Root cause

Voxy persists its own LOD database across sessions — confirmed. The mod ignores that.

The server tracks what a player has in `PlayerTracker.syncedChunks`, but that set is created
empty on join (`PlayerTracker.java:37`) and destroyed on disconnect (`PlayerTracker.java:44`).
Every reconnect therefore starts from "this player knows nothing."

Three server paths push LOD data, and two of them never consult the set at all:

| Path | Consults synced set? |
| --- | --- |
| `ServerEventHandler.onChunkLoad:48` → `sendLODData` | No — fires on every chunk load for every nearby player |
| `ChunkUpdateTracker.processDirty:52` → `broadcastLODData` | No — every 2s per dirty chunk |
| Worker catch-up, `ChunkGenerationManager.java:239` | Yes, but the set is empty on join |

## Goal

The server learns what the client actually holds and stops re-sending it. Once a chunk has
been delivered, new LOD data reaches the player only by the chunk genuinely changing, by the
chunk being newly generated, or by an operator running `/voxygen refresh`.

---

## 1. Prerequisite: make the synced set per-dimension

`PlayerTracker.syncedChunks` is one flat `LongSet` per player (`PlayerTracker.java:23`). Chunk
`(0,0)` in the overworld and `(0,0)` in the nether share a key, so visiting one suppresses the
other. Today this self-hides because the set dies on disconnect; persisting it would make the
missing terrain permanent.

Change the value type to `Map<ResourceKey<Level>, LongSet>` and thread the dimension through
`getSyncedChunks`, `NetworkHandler.setSyncedState`, and the worker catch-up loop. This is a
prerequisite for everything below, not an optional cleanup.

## 2. Client memory

New class `client/LodMemory.java`, client-only.

**What it records.** A chunk is recorded only after `VoxyIntegration.rawIngest` returns without
throwing in `NetworkClientHandler.handleLODData`. The record must mean "Voxy has this," not "a
packet arrived."

**In-memory shape.** `Long2ObjectMap<byte[]>` keyed by region, one 32x32-chunk region per entry,
128 bytes each.

- region key: `ChunkPos.asLong(cx >> 5, cz >> 5)`
- bit index within region: `(cx & 31) | ((cz & 31) << 5)`, range 0–1023
- byte `bit >>> 3`, mask `1 << (bit & 7)`

Arithmetic shift and mask are correct for negative coordinates: `cx = -1` gives region `-1`,
index 31, and region `-1` covers chunks `-32..-1`. This is the single most likely place for an
off-by-one, which is why it is the thing under test (section 7).

**On disk.** `<gamedir>/voxyworldgenv2/lodmemory/<worldkey>/<dimension>.bin`

```
magic  int   0x564C4D31   ("VLM1")
version int  1
count  int   number of regions
repeat count times:
  regionKey long
  bitmask   byte[128]
```

**worldkey** is `SHA-256` of the server address (or `singleplayer:<levelId>` for a local world),
truncated to 16 hex chars. Deliberately *not* derived from Voxy's `WorldIdentifier`: we already
carry version-fragile reflection against Voxy internals, and our key only needs to be stable per
server, not to match Voxy's own keying. Known limitation: reaching the same server by LAN IP and
by hostname yields two memory files, costing one redundant re-stream.

**Flush policy.** Dirty flag, flushed on a 30s client tick, on dimension change, and on
`ClientPlayConnectionEvents.DISCONNECT`. Never per chunk.

## 3. Wire protocol

New C2S payload. Note that `PayloadTypeRegistry.playC2S().register(HandshakePayload.TYPE, ...)`
at `NetworkHandler.java:177` already registers a C2S direction that nothing sends or receives —
a dead half this work finally makes use of.

```java
record KnownChunksPayload(ResourceKey<Level> dimension, boolean last, byte[] deflated)
```

Body before deflate: `varint regionCount`, then `regionCount` x (`long regionKey`,
`byte[128] bitmask`). Deflated as one unit with a stored-uncompressed fallback, mirroring the
approach already proven in `LODDataPayload.writeCompressedSections` (`NetworkHandler.java:105`).

**Splitting.** Accumulate regions until the raw body reaches 24 KB, then emit a packet. Deflate
only shrinks, and the stored fallback adds only framing, so every packet stays under the
existing 32 KB `MAX_PACKET_BYTES` ceiling. The final packet carries `last = true`.

Size in practice: radius 64 is 16,641 chunks — 25 regions, ~3.4 KB raw, one packet. Radius 512
is ~1.05M chunks — 1,089 regions, ~139 KB raw, ~6 packets.

**When the client sends.** A client tick compares `Minecraft.getInstance().level.dimension()`
against the last observed value and uploads on change. First observation covers join; every
subsequent change covers portals and `/execute in`. One mechanism, both cases.

**Version negotiation.** `HandshakePayload` gains an `int protocolVersion` field
(`PROTOCOL_VERSION = 2`). The client uploads only when the server advertises >= 2. Sending an
unregistered custom payload to a server that does not know it risks a disconnect, so this gate
is required, not cosmetic. This is a protocol change; client and server jars must move together
— which the `LODDataPayload` compression work already forced for this release, so it costs
nothing extra now.

## 4. Server side

**Seeding.** The server registers the receiver, ORs each arriving bitmask into that player's
per-dimension synced set, and marks the dimension as known when `last` arrives.

**Join gate.** Until the known-set for a player's current dimension arrives, or
`knownChunksTimeoutSeconds` (default 10) elapses, that player receives no LOD data. Without this
the worker starts blasting during the second the upload is in flight. Dimension entry is already
detected in `ChunkGenerationManager.checkPlayerMovement` (`ChunkGenerationManager.java:509`),
which is where the gate is armed; `ServerEventHandler.onPlayerJoin` arms it for the join case.

The gate is enforced at the single send boundary — the entry to `LodSendQueue.enqueue` in
`NetworkHandler.sendLODData` and `broadcastLODData` — so it covers all three paths including
block-update pushes, which would otherwise leak sends during the window. A gated send is
dropped, not queued, and leaves the chunk unsynced so the normal catch-up path picks it up
afterwards.

A vanilla or older client never uploads, the gate times out, and behaviour is identical to
today. Backward compatibility in that direction is preserved by construction.

**Send path changes.**

| Path | Change |
| --- | --- |
| `ServerEventHandler.onChunkLoad:48` | Now checks the synced set. Its issue-#40 biome-blend re-send still happens the first time a chunk is delivered; a client that already has that version does not need it again. |
| `ChunkUpdateTracker.processDirty:52` | **Unchanged.** The terrain genuinely changed, so it should push. This is the "or they visit the chunk" case. |
| Worker catch-up `ChunkGenerationManager.java:239` | Code unchanged; correct once the set is seeded. |

## 5. `/voxygen refresh`

```
/voxygen refresh                                        you, 16 chunks, current dimension
/voxygen refresh near | <1..512> | all                  you, current dimension
/voxygen refresh near | <1..512> | all <player>         that player, their dimension
/voxygen refresh near | <1..512> | all <player> <dim>   that player, named dimension
```

`<player>` is a vanilla `EntityArgument.player`, so `/voxygen refresh all @s the_end` scopes a
dimension without naming anyone. Branches are explicit nodes rather than optional middle
arguments, which Brigadier handles badly.

The existing `/voxygen reload` keeps its current meaning (re-read the config JSON) and is not
touched.

**Semantics.** Refresh only clears synced bits. It sends nothing itself; the existing worker
catch-up loop re-sends what it now sees as unsynced. To honour a radius larger than
`generationRadius` without introducing a second send path, the worker's catch-up radius for that
player becomes `max(generationRadius, activeRefreshRadius)` until the refresh drains — that is,
until `collectCompletedInRange` at that radius yields nothing, at which point the override is
cleared. Refresh never queues ungenerated chunks for generation.

`all` and a radius argument differ only in what they *forget*. `all` clears the entire
dimension's synced set; a radius clears only bits within that radius of the target player. Both
then use a catch-up override capped at 512 chunks — the same ceiling as the explicit argument —
because `collectCompletedInRange` walks the distance graph and an unbounded radius would make
that walk arbitrarily expensive on the worker thread. So `refresh all` means "forget the whole
dimension, re-send up to 512 chunks out, and deliver the rest as the player travels." The
command's reply states the effective re-send radius so this is never a surprise.

**Permissions.** The op requirement moves off the `/voxygen` root onto each existing subtree, so
`refresh` can carry its own configurable level. Targeting a player other than yourself always
requires level 2 regardless of `refreshPermissionLevel` — otherwise setting that to 0 would let
any player force egress onto any other player.

Caveat to document in the command's own help text: Brigadier only re-sends the command tree on
relog, so a `refreshPermissionLevel` change does not appear in an online player's tab-completion
until they reconnect. The command still executes correctly.

## 6. Config additions

```java
public boolean rememberSentChunks    = true; // master switch; false restores today's behaviour
public int refreshPermissionLevel    = 2;    // 0 lets any player refresh themselves
public int refreshDefaultRadius      = 16;   // what "near" means
public int knownChunksTimeoutSeconds = 10;   // join gate before assuming a vanilla client
```

`rememberSentChunks = false` makes the server ignore uploaded known-sets entirely and restores
current behaviour without a rollback, which matters because the failure mode of this feature is
invisible terrain.

## 7. Failure handling

| Situation | Behaviour |
| --- | --- |
| Corrupt or truncated memory file | Log once, treat as empty, delete the file. Costs a re-stream, not correctness. |
| Client memory wiped, Voxy DB intact | Client uploads nothing, server re-sends everything. Wasteful, self-correcting, no user action. |
| Voxy DB wiped, client memory intact | Blank horizon. This is the case `/voxygen refresh all` exists for. |
| Disk full or unwritable | Log once, keep the memory in RAM for the session. |
| Server on an older protocol | Client does not upload; server behaves as today. |

## 8. Testing

This branch currently has no test source set (`:test NO-SOURCE`) and `build.gradle` declares no
test dependencies. Add JUnit 5 and `test { useJUnitPlatform() }`, and create `src/test/java`.

Tests cover the region-bitmask codec only — pure logic, no Minecraft classes, runs under plain
`./gradlew test`:

- round-trip over a randomised chunk set
- negative coordinates, including the `-1` / region `-1` / index `31` case
- region boundaries at `+/-32` and `+/-31`
- multi-packet split and reassembly, including a set that lands exactly on the 24 KB boundary
- corrupt input: bad magic, wrong version, truncated body

Everything else is verified as HANDOFF.md describes: build, deploy via
`~/mc-test/deploy-all.sh`, manual reconnect test. The specific manual check is: connect, let a
radius fill, disconnect, reconnect, and confirm `/voxygen traffic` shows near-zero LOD egress
while the terrain is still drawn.

## 9. Out of scope

- Refresh does not trigger generation of ungenerated chunks.
- No automatic detection of a wiped Voxy database; `/voxygen refresh all` is the manual remedy.
- No change to `LODDataPayload` or its compression.
- No change to the generation scheduler or the LOD send queue.
