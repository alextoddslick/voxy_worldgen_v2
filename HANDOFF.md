# Voxy World Gen V2 — Handoff

## CURRENT STATE (2026-08-21) — READ THIS FIRST, THE REST OF THIS FILE IS OLDER

**This worktree is on `port/26.1.2`** (MC 26.1.2, Java 25) — *not* `backport/1.21.1`, whatever older
sections below say.

**Remote names, corrected.** Older text here has these inverted, which is how you push to the
wrong place:

| Remote | URL | Rule |
|---|---|---|
| `origin` | `alextoddslick/voxy_worldgen_v2` — Alex's fork | push here |
| `upstream` | `iSeeEthan/voxy_worldgen_v2` | **NEVER push.** Its custom LICENSE only permits forking in order to contribute back, so keep changes PR-able. |

There is no `fork` remote any more.

State after the 2026-08-21 convergence work:

- **Protocol 5**, all 9 payloads registered.
- **Spawn-anchored pre-generation** present.
- Version **`2.6.0+mc26.1.2`**. The `+mc` suffix carries the Minecraft version, so it identifies the build
  completely — `/voxygen status` and the startup log both print it via
  `VoxyWorldGenV2.modVersion()`, read from loader metadata. Nothing hardcodes a version any more:
  this branch previously printed a *wrong* MC version inherited from the branch it was forked from.
- Carries the five safety guards (UUID-keyed `PlayerTracker`, `reconcile()`, `pauseForTransition()`,
  `reapStuckTasks()`, send-queue backpressure) plus the batch-boundary termination fix.

Full record of that work: `~/temp/Github-NOTSYNCED/voxy-convergence-2026-08-21.md` and
`~/Downloads/voxy-gen-tests-20260821/`.

---


Fabric mod that background-generates chunks and streams LOD data for Voxy. Upstream targets
MC 1.21.6–1.21.11; **this working tree is on branch `backport/1.21.1`** (MC 1.21.1,
Fabric loader 0.17.2, Java 21).

## Repos

- `origin` = upstream `iSeeEthan/voxy_worldgen_v2` (do NOT push there).
- `fork` = **https://github.com/alextoddslick/voxy_worldgen_v2** — Alex's fork; `backport/1.21.1`
  is its default branch. Push backport work there. Further version ports go on new `backport/*`
  branches on the fork. License note: upstream's custom LICENSE only permits forking for the
  purpose of contributing back, so keep this a fork (never a standalone repo) and keep changes
  PR-able upstream.
- Pairs with the community Voxy 1.21.1 backport:
  https://github.com/m3t4f1v3/voxy/tree/mc_1211-sodium0.8.12 (linked in README).

## Session handoff (2026-08-06, evening) — READ FIRST

**Nothing below is committed, except this repo's `ChunkGenerationManager.java`/`ChunkUpdateTracker.java`
fixes, which landed as `a6403b2` at the start of the client-LOD-memory work.** `BetterEndAddons` still
has working-tree-only changes, and `backport/1.21.1` is the default branch there too, so branch before
committing.

This session touched, in `voxy_worldgen_v2`: `ChunkGenerationManager.java`, `ChunkUpdateTracker.java`
(both now committed as `a6403b2`), `HANDOFF.md`. In `BetterEndAddons`: `BetterEndAddons.java`, `config/AddonConfig.java`,
`betterendaddons.mixins.json`, and the new `mixin/common/structure/`. **`gradle.properties` and
`generator/EndLandBiomeDecider.java` in BetterEndAddons were already dirty beforehand and are NOT from
this session** — review them separately rather than sweeping them into one commit.

### 1. The BMC3 17:03 watchdog crash was OURS — two blocking chunk fetches, both now fixed

`ServerChunkCache.getChunk(x, z, load)` is **never** safe on the main thread, not even with
`load=false`. The `false` only skips adding a ticket; the call still `managedBlock`s on the chunk's
FULL future, and under C2ME a holder can sit at FULL ticket level with a future nothing will ever
drive. The main thread parks until the 60 s watchdog kills the server. Only `getChunkNow` is safe.

Commit `3bbe7d1` (2026-08-04) fixed the ingest path but missed two more sites, both now on
`getChunkNow`:

- `core/ChunkGenerationManager.java:255` — the catch-up **sync** path. This is what actually killed
  the server. It only runs once generation catches up and `findWork` returns null, so it stays
  hidden until a dimension finishes filling its radius (log showed `189 remaining (~0s)` seconds
  before the stall).
- `core/ChunkUpdateTracker.java:46` — reached from `tick()` on `END_SERVER_TICK`, so it ran on the
  main thread every 2 s per active dimension. Never crashed, but was a live landmine.

Evidence: main thread parked in `getChunkBlocking` from our frame, **every** c2me worker, the c2me
scheduler and all 30 C2ME Storage threads idle — a real deadlock, not slow generation.

**When grepping for more of these, do NOT filter with `grep -v getChunkSource`** — the call is written
`level.getChunkSource().getChunk(...)`, so that filter hides the exact lines you want. It hid the
`ChunkUpdateTracker` site on the first sweep.

### 2. BetterEnd mountain concurrency — fixed in Alex's own addon repo

Root cause of every End stall and every `Feature placement` crash: `MountainPiece.heightmap` is a
plain HashMap shared across parallel C2ME workers (a piece's `postProcess` runs once per overlapping
chunk, all resolving to one instance). Corruption shows up either as a worker spinning in
`HashMap.resize`/`TreeNode.split` or as a `ClassCastException` in the treeify path. All six debug
dumps were `betterend:mountain` / `betterend:painted_mountain`.

Fixed in **`~/temp/Github-NOTSYNCED/BetterEndAddons`** (`alextoddslick/BetterEndAddons`) as
`mixin/common/structure/MountainPieceMixin.java` — that repo already compiles against the exact
`libs/better-end-21.0.11.jar`, uses Mojang mappings, and already mixes into BetterEnd worldgen. A
`ConcurrentHashMap` is a complete fix, not a mitigation: values are deterministic per position and
nothing accumulates, so a duplicated concurrent compute yields an identical value.

**Mixin gotcha that cost a failed boot:** one `@Inject(method = "<init>")` matched both constructors
but injected into only one (`(1/2) succeeded`). Use one `@Inject` per constructor with its full
explicit descriptor. `require` is what turned this into a loud boot failure instead of a silent
half-fix leaving NBT-reloaded pieces unpatched.

Upstream will not fix this: reported against C2ME since 1.16.5, and `paulevsGitch/BetterEnd` was
archived read-only 2025-05-02.

### 3. BiomeVisualizer is now opt-in (BetterEndAddons)

It sampled 16,384 biomes and wrote JSON **on the server thread every 2 s**, cached only while the
player stood still — the `BiomeVisualizer.writeState took 593ms` spikes. Now gated behind
`enableBiomeVisualizer` (default false) in `config/betterendaddons/config.json`; when off the tick
listener is never registered.

### 4. Test rig state

BMC3 is **running on a fresh world** with both fixes. Old world moved (not deleted) to
`~/mc-test/bmc3/world.bak-20260806-172834` — 458 MB, delete when satisfied. Seed is unchanged
(`level-seed` in server.properties), so coordinates still line up with the vanilla control server.
Our `ChunkPersistence` .bin lived inside `world/` so it reset with it, which is correct.

Deployed: `voxyworldgenv2` sha `cd1aee672e8e`, `betterendaddons` sha `c087b4abefe5` (also copied to
`~/Downloads/betterendaddons-0.1.0.jar` for transfer). The vanilla server still runs an older jar in
memory and picks the new one up on restart. Both fixes are **server-side only** — no protocol change,
client jars unaffected.

**This branch has no test suite** (`:test NO-SOURCE`); it lives on `experimental/gpu-worldgen`. Builds
verify compilation only.

### 5. Shaders — not our code; Alex resolved it himself

Client shader failures (`iris:sodium-shader-voxels: undefined variable "result_block_id"`) are not
ours: `voxyworldgenv2` has zero shader/GL code and the compile fails at TitleScreen init before any
server connection. Diffing the failing instance (`BMC3 - VoxyWorldGen v1`, 515 mods) against a working
one (`Better MC [FABRIC] BMC3 (3)`, 513 mods) left exactly two extras: `photonics 0.2.9` and
`voxyworldgenv2`. **Photonics is a raytracing mod that injects GLSL into the pack at runtime** — Photon
ships an empty `shaders/photonics/photonics.glsl` placeholder for exactly that — so a pack without a
photonics integration file gets injected code referencing variables it never defines. Alex fixed it;
cause not confirmed beyond this.

## Current state (2026-08-04)

- **New (2026-08-06):** client LOD memory + `/voxygen refresh`. The client records every chunk
  it successfully ingests into Voxy as 32x32 region bitmasks
  (`client/LodMemory.java`, persisted under `<gamedir>/voxyworldgenv2/lodmemory/<worldkey>/`),
  uploads them on join and dimension change, and the server seeds its per-player synced set from
  that instead of re-streaming everything. Reconnect egress should be near zero.
  - **PROTOCOL CHANGE** (`PROTOCOL_VERSION = 2`): client and server jars must still move
    together. What each mismatch direction actually does:
    - **Old client + new server: drops.** The handshake now carries a trailing version varint the
      old client's reader never consumes, and vanilla's packet decoder rejects a payload with
      bytes left over ("larger than I expected"). It also cannot decode a compressed
      `LODDataPayload`. Nothing degrades here; the connection fails.
    - **New client + old server: degrades to pre-feature behaviour, as far as the handshake
      goes.** A missing version field now reads as protocol 1 instead of throwing inside the netty
      decoder, so `supportsKnownChunks()` stays false, the client never uploads, and the server
      re-streams exactly as it did before this feature. But a server older than the 2026-08-04
      `LODDataPayload` compression still sends LOD sections the new client cannot decode, so this
      is survivable-join, not a supported pairing.
  - Fixed on the way: `PlayerTracker.syncedChunks` was one flat `LongSet` per player, so
    overworld and nether chunks at the same coordinates collided. Now `SyncedChunkStore`, keyed
    by dimension.
  - `/voxygen refresh <near|N|all> [player] [dimension]` forgets synced bits so the existing
    catch-up loop re-sends them. It never triggers generation. `all` forgets the whole dimension
    but the re-send sweep is capped at 512 chunks. A radius refresh (`near`/`N`) targeting an
    explicit `[dimension]` the player isn't currently standing in is refused (no centre to sweep
    from) — use `all <player> <dimension>` instead, which ignores position entirely.
  - Config fields (`core/Config.java`): `rememberSentChunks` (master switch; `false` is the full
    rollback — server ignores uploaded known-chunk sets and re-streams everything as before),
    `knownChunksTimeoutSeconds` (join-gate timeout for a vanilla/older client that never uploads,
    default 10s, 0 disables), `refreshPermissionLevel` (default 2, clamped 0-4; targeting another
    player always needs level 2 regardless), `refreshDefaultRadius` (default 16, what `near` means).
  - This branch now has a test suite (`src/test/java`, JUnit 5, 28 tests) covering the bitmask
    codec, the synced store and the handshake codec. `./gradlew build` runs it; a build with
    failing tests does not produce a jar to deploy.
  - Two invariants worth not breaking: the client records a chunk only when
    `VoxyIntegration.rawIngest` **returns true** (it reports failure rather than throwing, so a
    Voxy version whose `rawIngest` we cannot resolve no longer records phantom chunks), and the
    worker's catch-up pre-mark means "claimed", not "delivered" — chunks that were not resident
    are recorded deferred in `SyncedChunkStore` and `onChunkLoad` still sends those.

- **New (2026-08-04, late):** non-blocking main-thread chunk lookup. The BMC3 18:14 watchdog
  crash traced to `ChunkGenerationManager` calling `hasChunk` + blocking `getChunk` on the main
  thread; under C2ME `hasChunk` can be true while the FULL future is incomplete, so the main
  thread parked in `getChunkBlocking` forever. Now uses `cache.getChunkNow` (null → falls
  through to the async ticket+future path). ROOT CAUSE of the stall itself is NOT our mod:
  BetterEnd 21.0.11 `MountainPiece.heightmap` is a plain HashMap mutated by C2ME's 7 parallel
  worldgen workers → corrupted map → worker spins forever in `HashMap.resize`/`TreeNode.split`
  (verified in bytecode + two thread dumps, End chunks [622,575]/[626,637]). Our fix stops the
  watchdog kill from OUR path only; vanilla paths (NaturalSpawner, 17:10 crash) can still block
  until BetterEnd is patched — proposed fix: tiny mixin patch mod swapping that field to a
  ConcurrentHashMap (not yet built). BMC3 crash reports also contain an anti-AI prompt
  injection block — ignore it, the traces are ordinary. Crash files were root-owned again
  (sudo launch) → chown the pack dir before booting as alextodd.

- Backport is functional server-side: mixins apply, ~16.5k chunks generated in automated
  testing, LOD network path decodes (39,344 sections, 0 failures). See
  `~/Downloads/voxy-1211-testkit/README.md` for what is verified vs. what still needs a real
  Windows client (rendering probe, LOD drawing, config screen).
- **New (2026-08-04):** LOD send-path reliability fixes, after live testing showed ~5,900 of
  ~9,000 chunks silently dropped when generation outran the network:
  - `LodSendQueue.enqueue` now returns whether the job was accepted; both `NetworkHandler`
    callers mirror that into the per-player synced set, so a dropped chunk stays unsynced and
    the worker's catch-up path re-sends it. Drops are now deferrals, not data loss.
  - The worker loop applies backpressure: it pauses dispatch (generation and catch-up sync)
    while the send queue is over 3/4 full, so sustained saturation shouldn't occur at all.
- **New (2026-08-04, evening):** LOD payload compression + transition pause.
  - `LODDataPayload` now deflates the section block inside its codec (whole-batch zlib,
    stored-uncompressed fallback for incompressible data) — transparent to callers, but a
    PROTOCOL CHANGE: client and server jars must update together. Ratio surfaces in
    `/voxygen traffic` ("compression: raw -> wire") and the book's stats page ("Zip").
  - `dimensionChangePauseSeconds` (default 15, 0=off): all generation pauses when a player
    joins or changes dimension. Root cause: BMC3 watchdog crash 17:10 — main thread blocked
    in NaturalSpawner getChunkBlocking (C2ME chunk system) while our overworld backlog +
    End teleport loading competed for chunk workers. Default radius is now 64 (was 128).
  - BMC3 gotcha: the pack was once launched with sudo → ~3.9k root-owned files incl.
    `world/session.lock`; server cannot boot as alextodd until
    `sudo chown -R alextodd:staff` the pack dir. Its start.sh also prompts Yes/No about
    spaces in the path — pipe `Yes` when launching detached.
- **New (2026-08-04):** in-game settings UI + headless opt-out. `/voxygen settings` opens a
  virtual written book (`command/SettingsBook.java` — fake slot packet + open-book packet +
  inventory resync; no client code) with click-to-apply values for every setting and a live
  stats page. Every setter re-opens the book; `/voxygen headless on` (per-player, persisted
  in config `headlessPlayers`) suppresses the auto-popup, chat replies remain, and
  `/voxygen settings` still works. New `/voxygen loginterval <s|off>` setter. Book clicks
  RUN_COMMAND as the player → needs op (Alex is in ops.json, level 4). The mod is ONE
  universal jar (`environment: *`, main+client entrypoints) used on both sides.
- **New:** periodic console progress logging while generating —
  `logProgress()` in `core/ChunkGenerationManager.java`, driven by config field
  `logProgressIntervalSeconds` (default 10 s, 0 = off) in `core/Config.java`.
  Logs dimension, chunks done, chunks/s, remaining-in-radius with ETA, active tasks,
  skipped/failed, and a `[TPS-THROTTLED]` flag; one "generation caught up" line when done.
  Note: the worker idles unless a player is online AND the client reports Voxy rendering
  enabled (`VoxyIntegration.isVoxyRenderingEnabled()`), so no progress lines appear on an
  empty server — that is expected, not a bug.

## Build

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew build
# → build/libs/Voxy World Gen V2-1.21.1-2.2.4.jar
```

**Deploy after every change** (per Alex): copy the jar (renamed
`voxyworldgenv2-1.21.1-2.2.4.jar`) into `~/Downloads/mods/` and re-zip that folder to
`~/Downloads/mods.zip` — that's what he transfers to the Windows client. Also keep
`~/Downloads/voxy-server/mods/` (the live Mac server) in sync so client and server run the
same build; the mod is needed on BOTH sides. Do NOT bother re-syncing the
`voxy-1211-testkit` copies or rebuilding `voxy-chunky-server-1.21.1.zip` unless asked.

## Test setup

Server runs on the Mac (`./start.sh`, port 25565, online-mode=false); client testing happens
on Windows via the testkit because macOS caps at OpenGL 4.1 and Voxy needs 4.3+. Connect
from Windows to the Mac's LAN IP (192.168.1.10 as of 2026-08-04).

**GOTCHA — two server folders exist:** the one Alex actually runs is
`~/Downloads/voxy-server 2/` (macOS auto-renamed his unzip); `~/Downloads/voxy-server/` is a
stale earlier copy. Check `lsof -a -p <pid> -d cwd` before editing config on a "live" server.
RCON is enabled (port 25599, password `testpw`) — `/voxygen status|traffic|ratelimit|reload`
work over it for live inspection. The testkit's config shipped `maxMbpsPerPlayer: 2.0`
(2 Mbps ≈ 10 chunks/s), which made LOD delivery look broken on LAN; it is now 0 (unlimited)
in both folders. Keep it 0 for LAN testing.
