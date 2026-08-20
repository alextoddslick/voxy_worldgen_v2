# Plans 4 & 5: Spawn Pre-generation and Deployment — as executed

These two were executed directly rather than written up first, at Alex's request. This is the
record of what landed, so the next session does not have to re-derive it.

## Plan 4 — spawn-anchored pre-generation

**The premise in the spec's first draft was wrong and was corrected before implementing.** An empty
dimension is *not* parked: `ServerChunkCache.tick(haveTime, true)` runs unconditionally in
`ServerLevel.tick`, before the `emptyTime` block, and that block only skips dragonFight,
entityTickList and blockEntities. Three separate things had to be fixed.

### 1. The whole-server pause

`MinecraftServer` stops ticking an empty dedicated server after `pause-when-empty-seconds`, which
also silences Fabric's server-tick events. No chunk ticket influences this. Fixed on both sides:

- `pause-when-empty-seconds=0` in `server.properties`.
- `cleanupTask` resets `emptyTicks` again. `upstream/unified` had dropped it behind a comment
  claiming the field does not exist on 26.2 — copied from the 1.21.1 branches, where it is true.
  On 26.2 `MinecraftServer.emptyTicks` exists, `MinecraftServerAccess` is its `@Accessor`, and
  nothing was calling it.

### 2. The player-anchored work source

`ChunkGenerationManager.workerLoop` slept whenever the player list was empty. It now falls through
to `dispatchSpawnPregen(idleBudget)`, which centres `DistanceGraph.findWork` on the world spawn.
`DistanceGraph` needed no change — it already takes a bare `ChunkPos`.

Spawn is read without a player via `ChunkPosCompat.spawnChunk(level)`:
`ChunkPos.containing(level.getRespawnData().pos())` on 26.2, `getSharedSpawnPos()` on 1.21.1.
26.2 has no spawn chunks, no `spawnChunkRadius` gamerule and no `TicketType.START`.

### 3. Tickets — and the `setupLevel` trap that cost a redeploy

Tickets reuse the existing `queueTicketAdd` / `releaseAllTickets` path, so nothing new was needed.
`TicketType.FORCED` carries `FLAG_PERSIST`: a leaked ticket is written into the world's
`chunk_tickets` SavedData and reactivated by `MinecraftServer.prepareLevels()`, which blocks boot
until every one reaches FULL.

**The bug that only showed up on the real server:** `getOrSetupState` is a `computeIfAbsent` and does
*not* prime the graph. `setupLevel` is the sole caller of `ChunkPersistence.load` and the only place
`state.loaded` is set, and `shutdown()` persists **only** loaded states. So the first deployment
generated 1764 chunks happily and would have thrown all of it away at shutdown. `dispatchSpawnPregen`
now schedules `setupLevel` onto the server thread and yields the pass. Verified on the box:
`loaded 2108 chunks from voxy generation cache` after restart, cumulative across three restarts.

### 4. The main-thread `getChunk` audit

All three inherited sites were converted to `getChunkNow` — the pattern behind the 17:03 watchdog
kill. `getChunk(x, z, false)` still `managedBlock`s on the chunk's FULL future; the `false` only
skips adding a ticket. A radius-128 empty-server run hits these far harder than one player ever did.
The dispatch site also lost its `hasChunk` + `getChunk` pair, which could park between the two calls.

Audit command (note: **never** filter with `grep -v getChunkSource`, it hides the exact lines):

```bash
grep -rn "getChunk(" common/src/main/java/ fabric/src/main/java/ | grep -v getChunkNow
```

## Plan 5 — deployment to `xps@192.168.1.23`

Live at `~/mc/voxy`, MC 26.2, Fabric loader 0.19.3.

- **Java**: Temurin 25.0.4 unpacked to `~/jdk25` via `wget` (there is no `curl` on the box, and
  `sudo` needs a password). Nothing system-wide was touched.
- **Server install**: transplanted with `rsync` from `~/temp/Github-NOTSYNCED/voxy-test-servers/mc26.2`
  (libraries + versions + `fabric-server-launch.jar`, ~70 MB) rather than running an installer.
- **Heap 5G** on an 8 GB box. Not 16 GB — the machine has 8 GB physically installed. Paging a
  Minecraft heap onto the 4 GB swapfile destroys tick times.
- **Mods (8)**: fabric-api, voxyworldgenv2 2.4.3, lithium, ferritecore, modernfix, ScalableLux,
  zfastnoise, obe. **No c2me** (by request; also removes the C2ME-specific deadlock class). **No
  voxy** — it hard-depends on sodium and cannot load server-side.
- **Control**: `~/mc-ctl {start|stop|status|console|log}` over a `screen` session named `voxy`, plus
  `watchdog.sh` on a `*/5` cron.
- **Sleep**: `sleep.target`/`suspend.target`/`hibernate.target`/`hybrid-sleep.target` masked and
  `HandleLidSwitch=ignore` — the box suspended mid-session before this was done.
- **26.x world layout**: region files live under `world/dimensions/minecraft/overworld/`, not
  `world/region/`. The voxy cache is `world/voxy_gen_minecraft_dimension _ minecraft_overworld.bin`
  — the spaces in that filename are a known quirk of `ChunkPersistence.getDimensionId` and must not
  be "fixed", or every existing cache file is orphaned.

### Tuning as deployed

`config/voxyworldgenv2.json`: `generationRadius 64`, `maxActiveTasks 12`, `maxChunksPerSecond 24`,
`spawnPregenEnabled true`, `spawnPregenRadius 128`. Radius 128 is ~205k chunks — a multi-hour to
multi-day background fill on 4 physical cores without c2me. It is designed to be left running.

### Not verified

Everything here was verified server-side with nobody connected. **No client has connected yet**, so
the protocol-5 handshake, the join gate, `LodMemory`'s upload, the settings screen and the tab HUD
are all unexercised end to end. The modpack jar was replaced with the same 2.4.3 build, so both
sides speak protocol 5 by construction.
