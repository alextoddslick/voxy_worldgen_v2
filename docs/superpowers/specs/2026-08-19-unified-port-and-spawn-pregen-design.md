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

| Destination | Files | Origin |
|---|---|---|
| `common/src/main/java` | `core/SyncedChunkStore`, `core/PlayerHistory`, `core/SettingsApplier`, `network/RegionBitmask`, `command/VoxyGenCommand`, `command/SettingsBook`, `command/TabHud` | `port/26.2` (already 26.2) except `PlayerHistory`/`SettingsApplier` (from 1.21.1) |
| `fabric/src/main/java` | `network/LodSendQueue`, NetworkHandler payload additions, command registration | `port/26.2` + merge |
| `fabric/src/client/java` | `client/LodMemory`, `client/ClientStorageReporter`, `client/VoxyWorldGenSettingsScreen`, `mixin/GuiTabListMixin`, `mixin/PlayerTabOverlayAccessor` | `feature/per-player-limits` (1.21.1 → 26.2) |

Anything touching Fabric's `CustomPacketPayload` / `ServerPlayNetworking` stays in `fabric/`,
matching how upstream already places `network/NetworkHandler` there and abstracts the rest
behind `platform/INetworkBridge`. Brigadier is vanilla, so `command/` can live in `common/`
with only its registration entrypoint in `fabric/`.

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

**The hard part.** An anchor alone generates nothing when the server is empty: as `HANDOFF.md`
records, *a dimension with no players is parked by the chunk system*, so in-flight work never
advances. The anchor must therefore hold a chunk ticket at the spawn position to keep the
overworld ticking. This ticket is the actual engineering content of the feature, and it must be
released when `spawnPregenEnabled` is false so that disabling the feature is a true rollback.

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

### 6. Testing

- The existing `HandshakePayloadTest` covers protocol round-tripping; it is extended to the
  protocol-5 payload set. TDD applies to new logic.
- `RegionBitmask` has an existing codec test including the understated-`Inflater`-`rawLength`
  case; it must keep passing after re-homing.
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

## Open items

- Whether to also refresh the modpack's other mods; out of scope here, the pack is left as-is
  apart from the voxyworldgenv2 jar.
