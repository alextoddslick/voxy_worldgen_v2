# Unified port + spawn pre-generation — design

Date: 2026-08-19
Branch: `port/unified-26.2` (worktree `~/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified`)
Base: `upstream/unified` @ `d1c6372`, `mod_version=2.4.3`

## Goal

Produce a single Voxy World Gen V2 build for Minecraft 26.2 that carries **both** lineages:

1. Upstream's `unified` multiloader work (platform abstraction, Tellus integration, debug
   renderer, debug-screen entries).
2. Alex's fork feature line, currently reaching only MC 1.21.1: `/voxygen` command suite,
   settings book, client LOD memory, `/voxygen refresh`, per-player limits, server settings
   screen, tab HUD, client storage report.

Then add spawn-anchored pre-generation, and deploy the result to a dedicated server at
`xps@192.168.1.23` alongside the server-side subset of the "Everything Voxy" modpack.

## Why this is needed

The "Everything Voxy" client pack ships `Voxy World Gen V2-fabric-26.2-2.4.2.jar`, built from
`upstream/unified`. That jar contains **none** of the fork's features — no `command/` package,
no `SyncedChunkStore`, no `LodMemory`, no `RegionBitmask`, no `LodSendQueue`. Meanwhile the
fork's newest work (`feature/per-player-limits`, protocol 4) exists only at MC 1.21.1.

The two lineages have structurally diverged: upstream restructured from a single Gradle project
into `common/` + `fabric/` + `neoforge/`, so the fork's commits are not cherry-pickable.

## Non-goals

- NeoForge. Only `:fabric:build` is exercised. The `neoforge` module is left untouched and its
  toolchain is never resolved.
- Upstreaming. Per `HANDOFF.md`, upstream's custom LICENSE only permits forking in order to
  contribute back, so this stays a fork branch pushed to `origin`
  (`alextoddslick/voxy_worldgen_v2`) and never to `upstream`.
- Content mods on the server. The deployment is a vanilla world with a performance-mod subset.
- C2ME on the server. Explicitly excluded — see "Deployment".

## Current state (verified 2026-08-19)

| Branch | MC | Version | Protocol | Has fork features? |
|---|---|---|---|---|
| `upstream/unified` | 26.2 (fabric) | 2.4.3 | 1 | no |
| `origin/port/26.2` | 26.2 | 2.2.5 | 2 | server half only, no client half |
| `origin/feature/client-lod-memory` | 1.21.1 | 2.2.5 | 2 | + LOD memory, refresh |
| `origin/feature/per-player-limits` | 1.21.1 | 2.2.5 | 4 | + limits, settings screen, tab HUD |

`origin/port/26.2` matters a great deal to scope: it already carries the 1.21.1 → 26.2 API
migration for `command/VoxyGenCommand`, `command/SettingsBook`, `command/TabHud`,
`core/SyncedChunkStore`, `network/LodSendQueue`, and `network/RegionBitmask`. Those files need
re-homing, not re-porting.

## Design

### 1. Source layout

`common` is a shared source directory consumed by each loader module via `srcDir`, **not** a
Gradle subproject (`settings.gradle` includes only `fabric` and `neoforge`). Placement is
therefore a file-location decision, not a build-graph one.

**Correction (2026-08-19).** An earlier draft put the fork's server half in `common/`. That is
wrong: `common/` is `srcDir`-merged into **two** modules compiled against **different Minecraft
versions** — `:fabric` (MC 26.2, `options.release = 25`) and `:neoforge` (MC 1.21.1, Java 21).
Every line in `common/` must compile against both. The fork's server files use 26.2-only APIs
(`src.permissions().hasPermission(...)`, `ClickEvent.RunCommand`, `getSelectedSlot()`,
`chunkPosition().x()`), so they cannot live there.

There is a second, subtler blocker: `VoxyGenCommand`, `SettingsBook` and `TabHud` are themselves
Fabric-free, but all three read `NetworkHandler.RAW_SECTION_BYTES` / `WIRE_SECTION_BYTES` and call
`LodSendQueue.getInstance()` — symbols that live in the Fabric module and do not exist on unified
at all.

**Resolution: the fork's code goes in `fabric/`, not `common/`.** Per Alex, NeoForge is not a
target. Placing the code in `fabric/src/main/java` satisfies that with strictly less work than the
alternatives, keeps `:neoforge` compiling for free rather than knowingly breaking it, and leaves
upstream free to promote files into `common/` later.

| Destination | Files | Origin |
|---|---|---|
| `fabric/src/main/java` | `core/SyncedChunkStore`, `core/PlayerHistory`, `core/SettingsApplier`, `network/RegionBitmask`, `network/LodSendQueue`, `command/VoxyGenCommand`, `command/SettingsBook`, `command/TabHud`, NetworkHandler merge | `port/26.2` (already 26.2) except `PlayerHistory`/`SettingsApplier` (1.21.1 → 26.2) |
| `fabric/src/client/java` | `client/LodMemory`, `client/ClientStorageReporter`, `client/VoxyWorldGenSettingsScreen`, `mixin/GuiTabListMixin`, `mixin/PlayerTabOverlayAccessor` | `LodMemory` from `port/26.2` (already 26.2); the other four 1.21.1 → 26.2 |
| `common/src/main/java` | `core/Config` field merge; `integration/VoxyIntegration.rawIngest` return type | edited in place |

`common/` is touched in exactly two places, both of which must stay 1.21.1-compatible: the Config
field additions (plain Java, safe) and `VoxyIntegration.rawIngest`, which must change from `void`
to `boolean` — LodMemory's correctness depends on recording "Voxy has this", not "a packet
arrived". Returning `false` for both a failed invoke and unresolvable reflected handles is required
before LodMemory is ported, or the client records chunks Voxy never received and the server
permanently skips them.

**Only six files are true 1.21.1 → 26.2 ports.** `origin/port/26.2` already carries 26.2 versions of
the command suite, `SyncedChunkStore`, `LodSendQueue`, `RegionBitmask`, `NetworkState` and
`LodMemory`. The genuinely unported files are `ClientStorageReporter`,
`VoxyWorldGenSettingsScreen`, `PlayerHistory`, `SettingsApplier`, `GuiTabListMixin` and
`PlayerTabOverlayAccessor`.

### 2. Config merge

Unified's `common/.../core/Config.java` is the survivor. The fork's added fields are merged in:
`maxMbpsPerPlayer`, `maxChunksPerSecond`, `logProgressIntervalSeconds`,
`stuckTaskTimeoutSeconds`, `dimensionChangePauseSeconds`, `singleplayer` (the
`SingleplayerConfig` profile), `headlessPlayers`, `rememberSentChunks`,
`knownChunksTimeoutSeconds`, `refreshPermissionLevel`, `refreshDefaultRadius`.

The `SingleplayerConfig` invariant is preserved as written: the profile may only ever **raise**
the base radius/task limits, never silently shrink them.

### 3. Protocol reconciliation

Unified declares `PROTOCOL_VERSION = 1` in `common/.../VoxyWorldGenV2.java`; the fork's line
reached 4. The merged build sets **`PROTOCOL_VERSION = 5`**.

5 rather than 4 so that neither a stock 2.4.2 client nor a protocol-4 1.21.1 client can
half-connect to this server. `HANDOFF.md` establishes that a mismatch drops the connection
cleanly rather than corrupting state, which is the desired behavior. Both the client jar (for
the modpack) and the server jar come out of this one tree, so they cannot drift.

### 4. Spawn-anchored pre-generation

**Problem.** Generation today is strictly player-anchored: `ChunkGenerationManager` returns
early on `if (players.isEmpty()) return;` and every work-finding path iterates
`PlayerTracker.getPlayers()`. There is no spawn anchor and no offline generation.
(`headlessPlayers` is unrelated — it is a per-player settings-book preference.)

**Config.** Two new fields on `ConfigData`:

- `spawnPregenEnabled` — default `true`
- `spawnPregenRadius` — default `128` (chunks)

**Mechanism.** A virtual anchor at the overworld spawn is injected into the work-finding path
so an empty player list no longer short-circuits generation.

**Three blockers, not one.** Corrected 2026-08-19 after bytecode verification; an earlier draft of
this spec claimed the dimension is "parked by the chunk system" with nobody online. That is false.
`ServerLevel.tick` calls `ServerChunkCache.tick(haveTime, true)` unconditionally, before the
`emptyTime` block, and that block only skips dragonFight, entityTickList and blockEntities. The
chunk system keeps advancing. `HANDOFF.md` never claimed otherwise — it says only that *the mod's
own worker* idles without a player.

What actually has to be solved:

1. **The whole-server pause.** `MinecraftServer.tickServer` stops ticking an empty dedicated server
   after `pause-when-empty-seconds` (vanilla default 60), which also silences Fabric's server-tick
   events. No ticket influences this. Defeat it with `pause-when-empty-seconds=0` in
   `server.properties` **and** by restoring the `emptyTicks` reset — `((MinecraftServerAccess)
   server).setEmptyTicks(0);`, which `origin/port/26.2` does at `ChunkGenerationManager.java:706`
   and which unified dropped (see "Known regression" below).
2. **The player-anchored work source.** `dispatchGeneration` returns false immediately on
   `players.size() == 0`, and `activeLevels` is built from `PlayerTracker.getPlayers()`. A
   player-independent work source is required. `DistanceGraph` needs no change — `findWork`,
   `countMissingInRange` and `collectCompletedInRange` already take a bare `ChunkPos`. The
   player-coupling lives entirely in `ChunkGenerationManager`.
3. **The chunk ticket.** Its job is to raise the `ChunkHolder`'s ticket level so `updateFutures`
   promotes to FULL and generation starts — not to keep the dimension alive. Use the existing
   `ChunkPosCompat.addForcedTicket` / `removeForcedTicket` (`TicketType.FORCED`).

**Do not register a custom TicketType yet.** It is the tidier design (flags 14 —
`FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE`, no `FLAG_PERSIST`), but
`BuiltInRegistries.TICKET_TYPE` is frozen by `bootStrap()`, and whether Fabric's registry-sync
unfreezes it at 26.2 is unverified. Failure there is a hard crash at mod init, not a fallback.
Verify before adopting.

**Ticket persistence is a boot hazard.** `TicketType.FORCED` has `FLAG_PERSIST` (flags=15), so
tickets are written into the world's `chunk_tickets` SavedData and reactivated by
`MinecraftServer.prepareLevels()`, whose `do { ... } while (pendingChunks() > 0)` blocks boot until
every one reaches FULL. A `kill -9` mid-run would leave the next boot hanging on "Loading initial
chunks". The anchor MUST release deterministically on `SERVER_STOPPING` via the existing
`releaseAllTickets`, and disabling `spawnPregenEnabled` must also release.

**Reading spawn without a player.** `MinecraftServer.getRespawnData()` returns
`LevelData$RespawnData` (`dimension()`, `pos()`, `globalPos()`, `yaw()`, `pitch()`). Convert with
`ChunkPos.containing(BlockPos)`. 26.2 has no spawn chunks, no `spawnChunkRadius` gamerule and no
`TicketType.START`, so nothing is loaded at spawn by default.

**Ordering trap.** `setupLevel(ServerLevel)` is reachable only from `checkPlayerMovement()`, which
returns early on an empty player list. It is the sole caller of `ChunkPersistence.load` and the only
place `state.loaded = true` is set, and `shutdown()` persists only states where `loaded` is true. An
anchor that bypasses `checkPlayerMovement` will re-generate everything on every restart *and*
silently discard its progress at shutdown. The anchor must call `setupLevel` (or an extracted
equivalent) for its dimension.

**Main-thread safety.** Non-negotiable, and the cause of a previous 60-second watchdog kill:
`ServerChunkCache.getChunk(x, z, load)` is never safe on the main thread, not even with
`load=false` — the `false` only skips adding a ticket, and the call still `managedBlock`s on the
chunk's FULL future. Only `getChunkNow` is safe. Any new call site added by this feature uses
`getChunkNow`.

When grepping for violations, do **not** filter with `grep -v getChunkSource`: the call is
written `level.getChunkSource().getChunk(...)`, so that filter hides exactly the lines being
looked for.

**Throttling.** The anchor is rate-limited through the existing `maxChunksPerSecond` cap and
always yields to live players, so a pre-generation run cannot starve someone playing. At radius
128 (~205k chunks) on 4 physical cores without C2ME, a full fill is a multi-hour to multi-day
background process; it is designed to be left running, not waited on.

### 5. Deployment

Target `xps@192.168.1.23` — Ubuntu 24.04.3, i7-7700HQ (4 cores / 8 threads), **8 GB RAM**,
468 GB disk, 4 GB swap.

- **Java 25.** Required by MC 26.2; Ubuntu 24.04 repos stop at 21 and `sudo` needs a password
  there, so a Temurin 25 x64 tarball is unpacked into `~/jdk25`. Nothing system-wide is touched.
- **Heap 5 GB.** Not the requested 16 GB: the machine has 8 GB physically installed
  (`MemTotal: 7978960 kB`; `lsmem` reports 8G online, 0 offline; no `mem=` cap on the kernel
  cmdline). The XPS 15 9560 has two SODIMM slots and supports 32 GB, so this is fixable with
  hardware, but 5 GB is the safe ceiling today — paging a JVM heap onto swap destroys tick times.
- **Server mods** (server-relevant subset of the pack, by `fabric.mod.json` environment and
  entrypoints): `fabric-api`, `voxyworldgenv2` (this build), `lithium`, `ferritecore`,
  `modernfix`, `ScalableLux`, `zfastnoise`, `obe`.
- **Excluded, client-only:** `sodium`, `iris`, `voxy`, `entityculling`, `immediatelyfast`,
  `reeses-sodium-options`, `modmenu`, `dynamic-fps`, `badoptimizations`, `cull-fewer-leaves`,
  `particle_core`, and the config libs only they need (`cloth-config`, `fzzy_config`,
  `fabric-language-kotlin`). `placeholder-api` is dropped: nothing in the pack depends on it.

  `voxy` itself declares `environment: "*"` but hard-depends on `sodium`, so it cannot load
  server-side. The renderer stays a client concern; `voxyworldgenv2` is the half with a server role.
- **C2ME excluded** by request. It is server-side only with no networking, so client behavior is
  unaffected and the client keeps its own copy for singleplayer. This also removes the failure
  class behind the previous watchdog kill, which depended on a C2ME holder sitting at FULL
  ticket level with a future nothing would drive. The cost is materially slower generation.
- **World:** fresh, vanilla, random seed. No datapacks, no content mods.
- **Process management:** `screen` session plus a watchdog, mirroring the existing `.29` box.

### 5b. Known regression to fix in passing

`upstream/unified`'s `ChunkGenerationManager.java:692` carries the comment *"old emptyTicks reset is
gone, that field isn't here and the pause check covers it"* and drops the reset. That comment was
copied from the 1.21.1 branches, where it is true. On 26.2 it is false: `MinecraftServer` does have
`private int emptyTicks`, the `MinecraftServerAccess` `@Accessor` is still compiled and still listed
in `voxyworldgenv2.mixins.json`, and nothing calls it. `origin/port/26.2` does it correctly at
line 706. This is directly load-bearing for spawn pre-generation.

### 5c. Client GUI port surface (26.2 render model)

The four unported client files hit a changed render model, not just renames. Verified by `javap`
against `minecraft-clientonly-deobf-26.2.jar`:

- `GuiGraphics` no longer exists — it is `GuiGraphicsExtractor`. `Screen.render(GuiGraphics,int,int,float)`
  is **gone**; the hierarchy is state-extraction now (`extractRenderState(GuiGraphicsExtractor,int,int,float)`).
  Any port assuming draw-order side effects will silently misbehave.
- `drawString` → `text`, `drawCenteredString` → `centeredText`. `fill` is unchanged.
- **Alpha is mandatory.** `text(Font, FormattedCharSequence, int, int, int, boolean)` opens with
  `ARGB.alpha(color); ifne 9; return` — a zero-alpha color draws nothing, with no `|= 0xFF000000`
  fixup. The settings screen's `0xFFFFFF` / `0xA0A0A0` / `0x707070` must all become `0xFF`-prefixed.
- `Minecraft.setScreen` and `Minecraft.screen` are gone: use `mc.gui.screen()` / `mc.gui.setScreen(...)`.
- `Gui.renderTabList` / `getTabList()` are gone; the gate moved to `Hud.extractTabList`. The
  `@Redirect` on `Minecraft.isLocalServer()Z` still applies — retarget to `@Mixin(Hud.class)`.
- `PlayerTabOverlayAccessor` ports unchanged — `PlayerTabOverlay` still has `private Component footer`.
- Widget APIs are unchanged: `Button.builder/bounds/build`, `AbstractSliderButton`'s ctor,
  `protected double value`, `updateMessage()`, `applyValue()`. The `LabeledSlider` inner class ports verbatim.

### 6. Testing

Unified has **no working test harness**: its only test is
`fabric/src/test/java/.../network/SmokeTest.java`, and omitting `test { useJUnitPlatform() }` is a
hard failure on Gradle 9.5.1 (*"test sources present ... did not discover any tests"*), not a silent
skip. Standing that up is the first task of the first plan.

Twelve pure-JVM tests come across from the fork: `ClientStorageReporterTest`, `ConfigPerPlayerTest`,
`ConfigSingleplayerTest`, `PlayerHistoryTest`, `SettingsApplierTest`, `SyncedChunkStoreTest`,
`HandshakePayloadTest`, `LODDataPayloadTest`, `RegionBitmaskTest`, `SendDistanceTest`,
`SettingsPayloadTest`, `StorageReportPayloadTest`.

Constraints that govern them:

- **Never call `Config.load()`/`save()` from a test.** Both reach `Services.PLATFORM.getConfigDir()`,
  which throws without loader ServiceLoader bindings. The fork's `@BeforeEach { Config.DATA = new
  Config.ConfigData(); }` pattern is why its tests run headless — preserve it. Relatedly, unified's
  `CONFIG_PATH` is a **static final** resolved at class-init, so merely touching `Config` triggers
  `Services.load()`; the fork's lazy `getConfigPath()` with a `Path.of("config", ...)` fallback must
  come across or every Config test dies with `ExceptionInInitializerError`.
- **Protocol gates are floors (`>=`), never equality.** `supportsKnownChunks()` is `serverProtocol >= 2`,
  `supportsStorageReport()` is `>= 3`. Write new assertions as `>= 5`, never `assertEquals(5, ...)`.
- **`NetworkState` is global mutable static state.** Tests reset it in `try/finally`; porting
  `setServerConnected` without zeroing `serverProtocol` in the false branch leaks state between tests.
- `HandshakePayloadTest` will not compile as-is (it references `NetworkHandler.PROTOCOL_VERSION`,
  which unified moved to `VoxyWorldGenV2`), and will still fail until the fork's tolerant reader
  `buf.isReadable() ? buf.readVarInt() : 1` lands.
- New unit coverage for the spawn anchor: radius math, and that `spawnPregenEnabled=false`
  releases the ticket.
- Build gate: `./gradlew :fabric:build` with `JAVA_HOME=/opt/homebrew/opt/openjdk@25` (arm64
  JDK 25.0.3). The Intel Homebrew JDK 25 at `/usr/local/Cellar` is x86_64 and must not be used.
- Runtime gate: the server boots, a client from the updated pack connects, and LOD streams.

### 7. Verified baseline

Before any porting, `upstream/unified` @ `d1c6372` was confirmed to build in this worktree:

```
chmod +x gradlew   # the exec bit does not survive `git worktree add`
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build
# BUILD SUCCESSFUL in 28s -> fabric/build/libs/Voxy World Gen V2-fabric-26.2-2.4.3.jar
```

`:fabric:build` did not resolve the neoforge toolchain, confirming the non-goal holds in practice.

## Risks

| Risk | Mitigation |
|---|---|
| Spawn ticket keeps the overworld hot forever, costing idle CPU/RAM on an 8 GB box | Rate-limit via `maxChunksPerSecond`; release the ticket when disabled or when the radius is filled |
| 1.21.1 → 26.2 GUI/mixin drift in the settings screen and tab-list mixins | Port these last, after the server half builds and runs; they are the least load-bearing pieces |
| A new main-thread `getChunk` sneaks in and reintroduces the watchdog kill | `getChunkNow` only; grep audit without the `getChunkSource` filter before merging |
| Protocol 5 silently mismatches the pack | Client and server jars are built from this one tree and deployed together |
| 8 GB is simply too little for radius 128 | Radius is config-driven; lower it, or add a SODIMM |

## Plan decomposition

This spec is executed as five sequential plans, each producing working, testable software:

1. **Foundation** — test harness, Config merge, and the pure-JVM classes (`SyncedChunkStore`,
   `RegionBitmask`, `PlayerHistory`, `SettingsApplier`) with their tests green.
2. **Network reconciliation** — merge the two `NetworkHandler`s, protocol 5, `LodSendQueue`, the
   modded gate vs the join gate.
3. **Commands and client GUI** — `command/` suite, settings screen, tab HUD, `LodMemory`.
4. **Spawn pre-generation** — the three blockers in Section 4.
5. **Deployment** — Section 5.

## Existing deployments are safe

`ChunkPersistence.java` and `QueuedChunk.java` are **byte-identical** between `origin/port/26.2` and
`upstream/unified` (verified by `diff`). The on-disk `voxy_gen_<dim>.bin` format — including the
filename-with-spaces quirk from `getDimensionId` — is compatible across both lineages, so any
existing cache survives the merge untouched. Do not "fix" the spaces in that filename: it would
orphan every cache file on the deployment box.

## Open items

- Whether to also refresh the modpack's other mods; out of scope here, the pack is left as-is
  apart from the voxyworldgenv2 jar.
- `upstream/unified` has no `HANDOFF.md`, which Alex's standing rule requires for every project.
  Plan 1 creates one. Whether to carry `CONTRIBUTING.txt` across is open — its three prose rules are
  the repo's only style gate (there is no CI, checkstyle, spotless or git hook anywhere).
- `Config.refreshPermissionLevel` is an int 0-4 with no direct 26.2 analogue; 26.2 uses
  `Permissions.COMMANDS_MODERATOR/GAMEMASTER/ADMIN/OWNER`. A mapping decision is needed in plan 3.
