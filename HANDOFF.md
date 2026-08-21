# Voxy World Gen V2 — unified port handoff

**Worktree:** `~/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified`
**Branch:** `port/unified-26.2`, cut from `upstream/unified` @ `d1c6372` (upstream `mod_version=2.4.3`)
**This build is `2.5.2`** — bumped deliberately. Upstream's own 2.4.3 is a different, smaller
artifact (it has none of the fork's features), and shipping a superset under the same number made
"which 2.4.3?" unanswerable from a filename or a log line.
**Main repo (all other branches):** `~/temp/Github-NOTSYNCED/voxy_worldgen_v2`

## What this branch is

The merge of two diverged lineages:

- **`upstream/unified`** — upstream's multiloader restructure (`common/` + `fabric/` + `neoforge/`),
  platform abstraction, Tellus integration, `DebugScreenEntry`-based F3 overlay. `PROTOCOL_VERSION=1`.
- **Alex's fork feature line** — `/voxygen` command suite, settings book, client LOD memory,
  `/voxygen refresh`, per-player limits, server settings screen, tab HUD. Reaches `PROTOCOL_VERSION=4`
  but only at **MC 1.21.1**.

The "Everything Voxy" modpack ships `2.4.2`, built from `upstream/unified` — so the client Alex
plays on has **none** of the fork's features. That is the gap this branch closes.

Design: `docs/superpowers/specs/2026-08-19-unified-port-and-spawn-pregen-design.md`

## LOD delivery: how terrain actually reaches a client

Learned the hard way on 2026-08-20, after a visible mid-distance hole. There are exactly THREE
delivery paths and they do not overlap:

| Band | Path | Reach |
|---|---|---|
| Near | `ServerEventHandler.onChunkLoad` | only the vanilla view-distance square |
| **Middle** | **`ChunkGenerationManager.runCatchup`, alone** | everything from view-distance to `generationRadius` |
| Far | `NetworkHandler.broadcastLODData` | only chunks JUST generated |

**A pre-generated chunk is never re-broadcast** — `dispatchBatch` filters out anything already in
`completedChunks`, and a spawn-pregenerated world has tens of thousands of them. So on a
pre-generated world the middle band depends *entirely* on catch-up throughput. It was capped at 8
chunks per 400 ms, one player per pass (20/s ceiling, ~1.6/s measured) against a ~51,000-chunk
annulus — half an hour at best, hours in practice, which presents as a permanent hole.

**The "claimed but not delivered" trap.** Callers mark a chunk synced BEFORE attempting the send
(`sendLODData`, `broadcastLODData`, and `runCatchup` pre-marks whole batches). Anything that then
skips the send MUST call `setSyncedState(player, pos, false)`, or `collectCompletedInRange` never
offers that chunk again and the terrain is missing for the whole session. Four paths in
`sendAsync` can skip; all four must un-mark. This is the single easiest way to reintroduce the hole.

**Ported components need their driver wired.** Three separate features shipped inert this way:
`LodSendQueue` (no `startSendQueue`), `TabHud` (no `tick`), and `LodMemory` (entirely orphaned — no
`record`, `tick` or `onDisconnect` caller, so no known-chunks payload was EVER sent and the join
gate could only expire on timeout). All present as "the feature does nothing", never as an error.
When porting, grep for callers of every new class.

## The batch is the unit of work, because it is the unit of completion

Fixed 2.5.1 (hardened in 2.5.2), after the live 26.2 server generated nothing for an hour with its worker pinned at 99%
of a core. `DistanceGraph` records completion in 4x4 batches and `markChunkCompleted` flags a batch
full only at mask `0xFFFF` — **all sixteen chunks**. But `findWork` admitted a batch on its
*nearest corner* (batch-space distance, `rb = (radius+3)>>2`) and then handed out only the chunks
inside the *chunk-space* radius. Any batch straddling the circle — a ring of ~90 of them at radius
128, and the count is non-zero at every radius — could therefore never fill. Once the interior was
done, `findWork` returned one of those forever:

```
findWork -> boundary batch -> dispatchBatch: every chunk already in completedChunks
         -> preFiltered empty -> untrack the batch (:531) -> return 0
         -> dispatchGeneration's while loop advances on nothing -> spin
```

Two symptoms, both silent. `dispatchGeneration` had no `sent == 0` guard (`dispatchSpawnPregen`
always had one at :462), so it never returned — which also meant the worker never re-read the
player list, and stayed anchored to a player who had disconnected half an hour earlier, ignoring
two teleports into fresh terrain. `dispatchSpawnPregen` did return, and so just quietly stopped
2,881 chunks short of its radius, once per second, forever.

The fix squares the boundary off: `findWork` returns the whole 4x4 block. It reaches at most 3
chunks past `generationRadius` — +0.96%, and 89 FEWER than a true radius-128 disc, because the
batch-space admission test already declined 576 in-radius chunks near the diagonals. A centre-dependent
completion mask was the alternative and is wrong — the graph is shared between the player anchors
and the spawn anchor, which have different centres. `DistanceGraphWorkTerminationTest` locks this
in by draining a radius the way `dispatchBatch` does and asserting `findWork` runs out of work.

**Dead settings to be aware of:** `lodSendDistanceChunks` has no production reader — the real send
ceiling is `NetworkHandler.syncRadiusSq()`, hardcoded to `generationRadius * 16` blocks.

**Sodium owns the video settings screen.** It ships its own `VideoSettingsScreen` and replaces
vanilla's, so a mixin on the vanilla screen is never reached in any pack with Sodium. Use the
`sodium:config_api_user` entrypoint. Sodium's `StatefulOptionBuilderImpl.validateData` requires ALL
of: name, storage handler, tooltip, default value, binding — plus a value formatter and range for
integers, and a version on the mod options. It **swallows whatever escapes the entrypoint**, so one
missing setter makes the whole page silently not exist.

## Deploying a build

**Every build lands in `~/Downloads/` at the top level, automatically.** Alex tests on a Windows
gaming PC (Voxy runs poorly on the Mac's GL path) and carries jars across by hand, so a build he
cannot find is a build he cannot test. Print the MD5 with it (`md5 -q <jar>`) — "it didn't work"
has more than once meant an older jar was still on the PC.

**Never copy a build into the ModrinthApp profile folder.** Stage in `~/Downloads`; he installs
from there himself.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build
cp "fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.5.2.jar" ~/Downloads/
cp "fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.5.2.jar" ~/Downloads/voxy-server-mods/
scp "fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.5.2.jar" razer@192.168.1.29:'Documents/minecraft/voxy/mods/'
ssh razer@192.168.1.29 '~/voxy-ctl stop && ~/voxy-ctl start'
md5 -q ~/Downloads/"Voxy World Gen V2-fabric-26.2-2.5.2.jar"
```

Client and server must move together — the merged build is protocol 5, and a mismatched peer is
dropped at the handshake.

## Build and test

```bash
cd ~/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test
```

Four rules that will cost you time if ignored:

1. **Write `JAVA_HOME` literally.** `$(/usr/libexec/java_home -v 25)` resolves to
   `/usr/local/Cellar/openjdk/25.0.2/...`, which is the **x86_64** JDK. The arm64 JDK 25.0.3 at
   `/opt/homebrew/opt/openjdk@25` is not registered with `java_home` at all.
2. **`:fabric:` targets only.** A bare `./gradlew build` pulls in `:neoforge:createMinecraftArtifacts`,
   which decompiles NeoForge 21.1.232 against Parchment for MC 1.21.1 — minutes of unrelated work.
3. **`git status` shows ` M gradlew`** (mode 100644 → 100755). That is expected and required — the
   exec bit does not survive `git worktree add`. Never `git checkout -- gradlew`.
4. **`org.gradle.daemon=false`** is set, so every invocation forks a single-use daemon. A ~3-6s
   floor per command is normal, not a hang.

## Placement rule: new code goes in `fabric/`, not `common/`

`common/` is not a Gradle subproject — `settings.gradle` deliberately omits it. It is a bare
directory `srcDir`-merged into **two** modules compiled against **different Minecraft versions**:
`:fabric` (MC 26.2, `options.release = 25`) and `:neoforge` (MC 1.21.1, Java 21). Every line in
`common/` must compile against both.

The fork's server code uses 26.2-only APIs (`src.permissions().hasPermission(...)`,
`ClickEvent.RunCommand`, `getSelectedSlot()`, `chunkPosition().x()`), so it cannot live there.
**NeoForge is not a target** — per Alex, he does not care about it. Putting the fork's code in
`fabric/src/main/java` satisfies that with less work than the alternatives.

**Stale claim, corrected 2026-08-21: `:neoforge` does NOT keep compiling for free.** It just needed
two independent fixes to actually build: `neoforge/.../NetworkHandler.java`'s
`getSyncedChunks(UUID)` call site was never updated when `PlayerTracker` became dimension-keyed
(`(UUID, ResourceKey<Level>)`), and `common/`'s `ChunkGenerationManager.cleanupTask` calls
`(MinecraftServerAccess) srv).setEmptyTicks(0)` via a mixin interface that only existed under
`fabric/src/main/java/.../mixin/` — `neoforge/src/main/java/.../mixin/MinecraftServerAccess.java`
now mirrors it, registered in `neoforge/src/main/resources/voxyworldgenv2.mixins.json`. Both are the
same "changed shared code in `common/`, left a NeoForge call site or platform file behind" pattern
that made `LodSendQueue`/`TabHud`/`LodMemory` inert earlier. **Every change to `common/` still needs
a `:neoforge:compileJava` check**, not just `:fabric:build` — it is a bonus target, not a free one.

`common/` is touched in exactly two places, both of which must stay 1.21.1-compatible:
`core/Config.java` (plain Java + Gson) and, in plan 2, `integration/VoxyIntegration.rawIngest`.

**`common/` must stay free of loader-specific imports.** Plan 1 briefly broke this: the merged
`Config` carried `import net.fabricmc.loader.api.FabricLoader`, which compiles under `:fabric` but
not `:neoforge`, since both `srcDir` the same directory. `getConfigPath()` now goes through
`Services.PLATFORM` inside a `try/catch (Throwable)` instead, which is loader-neutral **and** still
falls back to `Path.of("config", ...)` under a plain JUnit JVM where the ServiceLoader binding is
absent. Catching `Throwable` rather than `Exception` is deliberate — the first failure surfaces as
`ExceptionInInitializerError` and later ones as `NoClassDefFoundError`, both `Error`s.

Verify with `grep -rn "net.fabricmc" common/src/main/java/` — it must return nothing.

## Plan sequence

| # | Plan | Status |
|---|---|---|
| 1 | Foundation — test harness, Config merge, pure-JVM classes | **done** |
| 2 | Network reconciliation — merge the two NetworkHandlers, protocol 5, LodSendQueue | **done** |
| 3 | Commands and client GUI — `command/`, settings screen, tab HUD, LodMemory | **done** |
| 4 | Spawn pre-generation | **done** |
| 5 | Deployment to `razer@192.168.1.29` | **done — live** |

Plan 1: `docs/superpowers/plans/2026-08-19-plan1-foundation.md`
Plan 2: `docs/superpowers/plans/2026-08-19-plan2-network-reconciliation.md`
Plans 4 & 5: `docs/superpowers/plans/2026-08-19-plan4-5-spawn-pregen-and-deploy.md`

## State: all five plans complete

`:fabric:test` runs **23 classes / 119 tests, all green** (2026-08-21, after the convergence-guards
port below). This line has drifted stale three times already (14/78, then 17/88, then 20/108), so
re-run before quoting it: `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --rerun`.

The server is **live on `razer@192.168.1.29:25566`** (= `vanilla.alextoddslick.net`), NOT on xps.
`xps@192.168.1.23` died 2026-08-20 of a failed SODIMM and is not coming back without hardware work.

**Ported 2026-08-21 — five safety mechanisms the version branches (`port/26.2` etc.) had weeks
before unified.** The convergence plan assumed unified was strictly ahead; a reverse audit
(`~/Downloads/voxy-gen-tests-20260821/results/convergence-audit-reverse.md`, full evidence in
`~/Downloads/voxy-gen-tests-20260821/results/convergence-p25-unified-guards.md`) found five it was
missing, all in the player/worker-loop safety-net layer, all live-exposed:

1. **`PlayerTracker.players` is now `Map<UUID, ServerPlayer>`**, not an identity-keyed
   `Set<ServerPlayer>`. Highest priority — Minecraft constructs a new `ServerPlayer` instance for
   the same UUID on respawn and dimension change, so the old `Set` could never evict the stale
   instance, anchoring the generation worker to a phantom forever.
2. **`PlayerTracker.reconcile(MinecraftServer)`** — ported, called once a second from
   `ChunkGenerationManager.tick()`. `ServerPlayConnectionEvents.DISCONNECT` is not guaranteed to
   fire on a real dedicated server (measured on a Carpet fake player dying); reconcile is the
   second, independent layer of the same fix as #1.
3. **`pauseForTransition()`** now actually sets `generationPausedUntilMs` on join and dimension
   change, wired from `checkPlayerMovement` via a local `lastPlayerLevels` map (separate from
   `PlayerTracker.lastDimension`, which is already primed at join and so cannot itself signal one).
   `isTransitionPaused()` existed already; it was permanently `false` before this.
4. **`reapStuckTasks()`** — ported, called on the same once-a-second cadence as reconcile, wired to
   the previously-dead `Config.stuckTaskTimeoutSeconds`. Reclaims a throttle permit from a
   generation task whose future never resolves. Adapted for unified's spawn pre-generation (which
   `port/26.2` does not have): when nobody is online anywhere, every dimension gets the *full*
   timeout rather than the short "no players in this dimension" grace, or the pregen target's own
   legitimately in-flight tasks would be reaped prematurely.
5. **`Services.NETWORK.isSendQueueSaturated()` now has a caller** — `workerLoop()` backs off when
   the LOD send queue is at capacity, instead of dispatching at full speed into a saturated sender.

Adapted, not copied: unified's worker loop is per-player work-stealing (`budget`/`exhausted[]`),
structurally different from the version branches' one-batch-per-iteration shape, so each mechanism
was re-derived for unified's actual call sites rather than pasted in. Regression tests added:
`PlayerTrackerIdentityKeyingTest` (simulates a respawn — same UUID, new object identity — and
asserts no phantom entry survives), `ChunkGenerationManagerTransitionPauseTest`,
`ChunkGenerationManagerStuckTaskReaperTest`. None of the five had any test before this pass.
`isSendQueueSaturated`'s new call site lives inside `workerLoop()`'s infinite loop, which — like the
rest of that method — is not unit-testable in this harness; see the results doc for why.

**`:neoforge:build` now succeeds** (it did not before this pass — see "Placement rule" above for
what was actually broken and the stale doc claim that hid it).

**Verified 2026-08-21** — full record in `~/Downloads/voxy-gen-tests-20260821/HANDOFF.md`:

- The **batch-boundary termination fix works.** A from-scratch radius-48 run generated exactly 7056
  chunks and stopped: the worker accumulated < 1 ms CPU per 18 s afterwards and a thread dump had
  zero hits inside `DistanceGraph.findWork`. The batch geometry was confirmed analytically
  (`rb = (r+3)>>2`, whole 4x4 blocks) and reproduces the recorded −89-chunk delta at radius 128.
- A **live restart at radius 128 does not spin**: 16 samples over 4 min at `active tasks 0/40`,
  151,399 cached chunks reloaded intact, ~24 s downtime.
- **All four `sendAsync` skip paths correctly un-mark** via `setSyncedState(player, pos, false)`
  (`NetworkHandler.java:707-727`) — the invariant that reintroduces the terrain hole is intact.
- **All three historically-inert components are wired**, with call sites: `LodSendQueue`
  (`ServerEventHandler.java:16` → `FabricNetworkBridge.java:41` → `NetworkHandler.java:642`),
  `TabHud` (`VoxyWorldGenV2Fabric.java:50`), `LodMemory` (`NetworkClientHandler.java:188`,
  `VoxyWorldGenV2Client.java:31/40`). **`LodMemory` is in `fabric/src/client/java`, not `main`** —
  a `main`-only grep will wrongly report it orphaned all over again.

**Still unverified: no real client has ever connected.** The protocol-5 wire handshake, the join
gate's real trigger chain, `LodMemory.tick`'s scheduling and disk persistence, `upload()`'s send,
and all rendering (settings screen, tab HUD, the mid-distance LOD band) remain untested end to end.
The 2026-08-21 tests narrowed this (`JoinGateTest`, `NetworkStateProtocolGateTest`, `LodMemoryTest`)
but cannot close it. Client kit staged at `~/Downloads/voxy-gen-test-CLIENT/`.

**A from-scratch radius-128 completion has still never been run** — only radius 48 from scratch, and
radius 128 from a warm cache. To close it, wipe the gen cache on a *copy* of the live world.

**The 1.21.1 lineage has no spawn pre-generation at all.** `spawnPregenEnabled`, `spawnPregenRadius`
and `dispatchSpawnPregen` exist only on 26.2 — confirmed by `javap` on both jars' `Config$ConfigData`.
On 1.21.1, generation is purely player-anchored, so an empty BMC3 server generates nothing by design.

Deployment details, tuning and the 26.x world layout: see the plans 4 & 5 record.

## State after plan 2

`:fabric:test` runs **12 classes / 72 tests, all green**. The wire format is **protocol 5**, declared
on `fabric/.../network/NetworkHandler.java`; `:neoforge` keeps its own at 1 and `common/` has none.

Nine payloads are registered: `HandshakePayload` (both directions), `HandshakeAckPayload`,
`LODDataPayload`, `ServerConfigPayload`, `ServerConfigPushPayload`, `KnownChunksPayload`,
`StorageReportPayload`, `SettingsSnapshotPayload`, `SettingsUpdatePayload`.

What changed in behaviour, beyond new payloads:

- **The modded gate is gone.** Generation and streaming are no longer gated on the client having the
  mod; the time-boxed join gate decides who LOD data is withheld from, and it expires open after
  `knownChunksTimeoutSeconds`. A vanilla player now causes generation where previously they did not.
- **`LODDataPayload` carries a deflated body.** Compression happens once in `of()` on the sender
  thread, so the payload knows its wire size before sending — the precondition for honest per-player
  Mbps caps. `NetworkState` counters and the F3 lines now mean post-deflate bytes and are labelled
  "wire"; they read ~3-6x lower than before, which is a relabel, not a regression.
- **Palette serialisation moved off the tick thread.** `snapshotSections` takes a
  `PalettedContainer.copy()` on the main thread (a live container is guarded by a `ThreadingDetector`)
  and `LodSendQueue`'s single sender thread does the encode. **No test exercises this** — it only
  proves itself under load.
- **`PlayerTracker` is dimension-keyed.** Synced sets live in a per-player `SyncedChunkStore` keyed by
  dimension id, so a portal trip no longer discards the overworld's progress.
- **`rawIngest` returns `boolean`.** It must mean "Voxy has this", not "a packet arrived" — the client
  records what it reports and the server permanently skips those chunks.

`SyncedChunkStore` and `RegionBitmask` now live in `common/`, not `fabric/`: `PlayerTracker` uses them
and both are deliberately Minecraft-free.

## State after plan 1

`:fabric:test` runs **7 classes / 55 tests, all green**: `HarnessTest`, `SyncedChunkStoreTest` (12),
`RegionBitmaskTest` (13), `ConfigSingleplayerTest` (8), `ConfigPerPlayerTest` (10),
`PlayerHistoryTest` (5), `SettingsApplierTest` (6). The jar still builds as
`Voxy World Gen V2-fabric-26.2-2.5.2.jar`.

Landed in `fabric/src/main/java`: `core/SyncedChunkStore`, `core/PlayerHistory`,
`core/SettingsApplier`, `network/RegionBitmask`. All four were pure copies — `SyncedChunkStore` and
`RegionBitmask` are byte-identical across both fork branches, and `PlayerHistory`/`SettingsApplier`
import no Minecraft classes at all. **No 1.21.1 → 26.2 porting has happened yet**; that starts in
plan 3.

`common/core/Config.java` is the fork's version (a strict superset of `port/26.2`'s) plus unified's
`ServerConfig` / `snapshot()` / `applyServerConfig()` / `clamp()`.

## Things that will bite you

- **Never call `Config.load()` or `Config.save()` from a test.** Both reach
  `Services.PLATFORM.getConfigDir()`, which throws without loader ServiceLoader bindings. Unified's
  `CONFIG_PATH` was a `static final` resolved at class-init, so merely *touching* `Config` triggered
  `Services.load()`. The fork's lazy `getConfigPath()` with a `Path.of("config", ...)` fallback is
  kept for exactly this reason. Tests use `@BeforeEach { Config.DATA = new Config.ConfigData(); }`.
- **`generationRadius` defaults to 64, not unified's 128.** The singleplayer auto rule is
  `max(128, base)`, which is what `ConfigSingleplayerTest` asserts. Raising the base to 128 makes
  every arithmetic assertion in that file vacuous.
- **`update_interval` stays snake_case.** It is a deliberately preserved legacy JSON field name;
  renaming it breaks every existing `voxyworldgenv2.json` on disk.
- **Protocol gates are floors (`>=`), never equality.** `supportsKnownChunks()` is
  `serverProtocol >= 2`, `supportsStorageReport()` is `>= 3`. Write new assertions as `>= 5`.
- **`SyncedChunkStore.packChunk` reimplements `ChunkPos.asLong` on purpose** so the class carries no
  Minecraft types and stays unit-testable. Do not "simplify" it to `Services.CHUNK_POS.asLong`.
- **`SyncedChunkStore.deferred` is a second, separate set** from `synced` — "claimed but not
  delivered". Merging them breaks the catch-up loop's contract; three tests pin it.
- **`RegionBitmask.forgetWithin` is a circle, not a square**, and `regionKey` needs arithmetic (not
  unsigned) shift for negative coordinates.
- **`ChunkPersistence.java` and `QueuedChunk.java` are byte-identical** between `port/26.2` and
  `upstream/unified`, so existing `voxy_gen_<dim>.bin` caches survive the merge. Do not "fix" the
  spaces in `getDimensionId`'s filenames — it would orphan every cache file on the deployment box.

## Known issues inherited from `upstream/unified`

- **Resolved, corrected 2026-08-21** (this bullet was itself stale): `ChunkGenerationManager`'s
  `cleanupTask` used to claim the old `emptyTicks` reset was gone. It is not gone on 26.2 —
  `MinecraftServer.emptyTicks` exists, the `MinecraftServerAccess` `@Accessor` mixin was already
  compiled and wired on `:fabric` by the time this convergence pass started, `cleanupTask` does
  call it. The only real gap was `:neoforge`, which had no `MinecraftServerAccess` mixin of its own
  until this pass added one (see "Placement rule" above) — fixed, not merely documented.
- **Main-thread `getChunk` sites** at `ChunkGenerationManager.java:295`, `:427`/`:428`, and
  `ChunkUpdateTracker.java:102`. `ServerChunkCache.getChunk(x, z, load)` is never safe on the main
  thread — `load=false` only skips adding a ticket, the call still `managedBlock`s on the chunk's
  FULL future. Only `getChunkNow` is safe. When grepping for more, do **not** filter with
  `grep -v getChunkSource` — the call is written `level.getChunkSource().getChunk(...)`.
- **No `CONTRIBUTING.txt`** on this branch, unlike the fork branches. Its three prose rules are the
  repo's only style gate; there is no CI, checkstyle, spotless or git hook anywhere.

## Upstream / license

`origin` = `alextoddslick/voxy_worldgen_v2` (Alex's fork) — push here.
`upstream` = `iSeeEthan/voxy_worldgen_v2` — **never push**. Upstream's custom LICENSE only permits
forking in order to contribute back, so this stays a fork branch and changes stay PR-able.
