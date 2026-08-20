# Voxy World Gen V2 — unified port handoff

**Worktree:** `~/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified`
**Branch:** `port/unified-26.2`, cut from `upstream/unified` @ `d1c6372` (upstream `mod_version=2.4.3`)
**This build is `2.5.0`** — bumped deliberately. Upstream's own 2.4.3 is a different, smaller
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
cp "fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.5.0.jar" ~/Downloads/
cp "fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.5.0.jar" ~/Downloads/voxy-server-mods/
scp "fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.5.0.jar" xps@192.168.1.23:~/mc/voxy/mods/
ssh xps@192.168.1.23 '~/mc-ctl stop && ~/mc-ctl start'
md5 -q ~/Downloads/"Voxy World Gen V2-fabric-26.2-2.5.0.jar"
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
`fabric/src/main/java` satisfies that with less work than the alternatives and keeps `:neoforge`
compiling for free rather than knowingly breaking it.

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
| 5 | Deployment to `xps@192.168.1.23` | **done — live** |

Plan 1: `docs/superpowers/plans/2026-08-19-plan1-foundation.md`
Plan 2: `docs/superpowers/plans/2026-08-19-plan2-network-reconciliation.md`
Plans 4 & 5: `docs/superpowers/plans/2026-08-19-plan4-5-spawn-pregen-and-deploy.md`

## State: all five plans complete

`:fabric:test` runs **14 classes / 78 tests, all green**. The server is **live** at
`xps@192.168.1.23:25565` and pre-generating around spawn with nobody connected.

**The one thing nobody has verified: no client has connected yet.** The protocol-5 handshake, the
join gate, `LodMemory`'s upload, the settings screen and the tab HUD are all unexercised end to end.
The modpack jar was replaced with the same 2.4.3 build, so both sides speak protocol 5 by
construction, but that is an argument, not a test.

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
`Voxy World Gen V2-fabric-26.2-2.5.0.jar`.

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

- **`ChunkGenerationManager.java:692`** claims *"old emptyTicks reset is gone, that field isn't
  here"* and drops the reset. False on 26.2: `MinecraftServer.emptyTicks` exists, the
  `MinecraftServerAccess` `@Accessor` is still compiled and listed in `voxyworldgenv2.mixins.json`,
  and nothing calls it. `origin/port/26.2` does it correctly at line 706. Load-bearing for plan 4.
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
