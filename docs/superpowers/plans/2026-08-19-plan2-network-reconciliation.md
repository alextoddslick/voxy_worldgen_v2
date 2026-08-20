# Plan 2: Network Reconciliation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the two `NetworkHandler` lineages into one protocol-5 Fabric wire format that keeps the fork's compression, wire-byte accounting and join gate, and unified's config-push, partial-section updates and client drain queue.

**Architecture:** The merged Fabric handler carries **nine** payloads: unified's `ServerConfigPayload` / `ServerConfigPushPayload`, the fork's `LODDataPayload` (deflated body), `KnownChunksPayload`, `StorageReportPayload`, `SettingsSnapshotPayload`, `SettingsUpdatePayload`, plus a reshaped `HandshakeAckPayload` and a tolerant `HandshakePayload`. `PROTOCOL_VERSION` moves out of `common/` onto the Fabric handler at **5**; `:neoforge` keeps its own at 1 and is not updated.

**Tech Stack:** Java 25, MC 26.2 (unobfuscated), Fabric API 0.153.0+26.2, fabric-networking-api-v1 6.3.3, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-19-unified-port-and-spawn-pregen-design.md` — this plan executes §3b. If it deviates, update §3b in the same commit.

## Global Constraints

- `JAVA_HOME=/opt/homebrew/opt/openjdk@25` — **literally**. `$(/usr/libexec/java_home -v 25)` gives the x86_64 JDK.
- `:fabric:` targets only. `./gradlew :fabric:test`, `./gradlew :fabric:build`.
- `$FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2`. Worktree is the CWD for every command.
- **`common/` must stay free of loader-specific imports.** Verify after any `common/` edit with `grep -rn "net.fabricmc" common/src/main/java/` — must return nothing. `:neoforge` `srcDir`s the same directory.
- **`MAX_PACKET_BYTES` value stays `32_768`.** `RegionBitmask.MAX_REGIONS_PER_PACKET = 180` is derived from it in javadoc; changing the value breaks that derivation silently while tests keep passing. Its *visibility* does change (private → package-private).
- **Protocol gates are floors (`>=`), never equality.**
- Baseline: 7 test classes / 55 tests green. This plan adds 6 test classes, ending at **13 classes**.
- On 26.2 the palette codec is **registry-free** — `PalettedContainerRO.write(FriendlyByteBuf)` and `PalettedContainer.read(FriendlyByteBuf)` take a plain buf; `RegistryAccess` moved into `PalettedContainerFactory.create(RegistryAccess)`. Every `RegistryFriendlyByteBuf` wrapper in the send/receive path is ceremony and should be dropped, not preserved.
- 26.2 renames that every file copied from a 1.21.1 branch will hit: `ResourceLocation` → `net.minecraft.resources.Identifier`; `ResourceKey.location()` → `identifier()`; `pos.x`/`pos.z` fields → `pos.x()`/`pos.z()`; `ChunkPos.asLong`/`toLong()`/`new ChunkPos(long)` → `ChunkPos.pack(int,int)`/`pos.pack()`/`ChunkPos.unpack(long)`; `getMinSection()` → `getMinSectionY()`; `ServerPlayer.hasPermissions(int)` → `player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`; `PayloadTypeRegistry.playC2S()`/`playS2C()` → `serverboundPlay()`/`clientboundPlay()`.

---

### Task 1: Relocate PROTOCOL_VERSION and make the handshake tolerant

Today there is exactly one constant, `common/.../VoxyWorldGenV2.java:14`, `PROTOCOL_VERSION = 1`, read at 8 decision points across both loader modules. It must move onto the Fabric handler, because the merged Fabric wire format is a superset of neither lineage while `:neoforge`'s format is not being updated — a shared constant would make `:neoforge` announce a version it does not speak.

**Files:**
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2.java` (delete the constant)
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkState.java`
- Modify: `fabric/src/client/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java`
- Modify: `neoforge/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java` (add its own `PROTOCOL_VERSION = 1`)
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/network/HandshakePayloadTest.java`

**Interfaces:**
- Produces: `NetworkHandler.PROTOCOL_VERSION == 5`; `NetworkState.setServerProtocol(int)`, `NetworkState.supportsKnownChunks()`, `NetworkState.supportsStorageReport()`. Tasks 4, 5 and 6 all gate on these.

- [ ] **Step 1: Copy the test**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git -C $FORK show origin/port/26.2:src/test/java/com/ethan/voxyworldgenv2/network/HandshakePayloadTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/network/HandshakePayloadTest.java
```

Take it from `port/26.2`, **not** `per-player-limits` — the 26.2 branch's copy is already API-correct.

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL. Two independent causes: `cannot find symbol: variable PROTOCOL_VERSION / location: class NetworkHandler`, and missing `NetworkState.setServerProtocol` / `supportsKnownChunks`.

- [ ] **Step 3: Move the constant**

In `common/.../VoxyWorldGenV2.java`, delete:

```java
    // bump on any wire format change, a mismatched peer is treated as unmodded
    public static final int PROTOCOL_VERSION = 1;
```

In `fabric/.../network/NetworkHandler.java`, add near the top of the class:

```java
    /**
     * The merged Fabric wire format: unified's config push plus the fork's compressed LOD, known
     * chunks, storage report and settings payloads. It is a superset of neither lineage, so it gets
     * its own number rather than continuing either sequence. Peers announce this in the handshake
     * and gate features on floors (>=), never equality.
     */
    public static final int PROTOCOL_VERSION = 5;
```

In `neoforge/.../network/NetworkHandler.java`, add `public static final int PROTOCOL_VERSION = 1;` and repoint its three references. NeoForge's format is unchanged.

Replace every remaining `VoxyWorldGenV2.PROTOCOL_VERSION` in the fabric module with `NetworkHandler.PROTOCOL_VERSION`.

- [ ] **Step 4: Make the handshake reader tolerant**

In `fabric/.../network/NetworkHandler.java`, change `HandshakePayload`'s buf constructor from:

```java
        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readVarInt());
        }
```

to the fork's tolerant form (copy it and its javadoc from `origin/port/26.2`):

```java
        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.isReadable() ? buf.readVarInt() : 1);
        }
```

This is the change with the worst failure mode if skipped. A protocol-1 server writes only the boolean; an unconditional `readVarInt()` throws **inside the netty decoder**, dropping the connection with an opaque "Internal Exception" before the player reaches the world. `legacyOneBytePayloadReadsAsProtocolOne` exists solely to pin it.

- [ ] **Step 5: Add protocol tracking to NetworkState**

Port `setServerProtocol(int)`, `supportsKnownChunks()` and `supportsStorageReport()` from `origin/port/26.2:src/main/java/com/ethan/voxyworldgenv2/network/NetworkState.java`. Keep the floor form exactly:

```java
    public static boolean supportsKnownChunks()  { return serverConnected && serverProtocol >= 2; }
    public static boolean supportsStorageReport() { return serverConnected && serverProtocol >= 3; }
```

`setServerConnected(false)` **must also zero `serverProtocol`**. Both `HandshakePayloadTest` and `StorageReportPayloadTest` reset state in `try/finally`; without the zeroing, protocol state leaks between tests and they fail non-deterministically depending on JUnit's method order.

- [ ] **Step 6: Run to verify it passes**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
grep -rn "net.fabricmc" common/src/main/java/ || echo "common/ still loader-clean"
```

Expected: PASS, 8 test classes.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: move PROTOCOL_VERSION to the fabric handler at 5, tolerate legacy handshakes"
```

---

### Task 2: Reshape HandshakeAckPayload and delete the modded gate

Spec §3b discards unified's modded gate in favour of the fork's join gate. That decision forces the ack's shape: `SettingsPayloadTest` calls `new NetworkHandler.HandshakeAckPayload(4)` and reads `read.clientProtocol()`, so the fork's one-component record wins over unified's `(boolean clientHasMod, int protocolVersion)`.

**Files:**
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`
- Modify: `fabric/src/client/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java`
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java`
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/event/ServerEventHandler.java`
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/core/ChunkUpdateTracker.java`
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java`

**Interfaces:**
- Produces: `HandshakeAckPayload(int clientProtocol)`; `PlayerTracker.setClientProtocol(UUID,int)` / `getClientProtocol(UUID)`. Task 6 gates `SettingsSnapshotPayload` on `getClientProtocol(uuid) >= 4`.

- [ ] **Step 1: Reshape the record**

Replace unified's `HandshakeAckPayload` with the fork's shape from `origin/feature/per-player-limits` (line 267), keeping 26.2 types:

```java
    public record HandshakeAckPayload(int clientProtocol) implements CustomPacketPayload {
        public static final Type<HandshakeAckPayload> TYPE = new Type<>(HANDSHAKE_ACK_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakeAckPayload> CODEC =
                CustomPacketPayload.codec(HandshakeAckPayload::write, HandshakeAckPayload::new);

        public HandshakeAckPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.clientProtocol);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
```

- [ ] **Step 2: Delete the two equality checks**

These must be **deleted, not relaxed to `>=`**:

- `fabric/.../network/NetworkHandler.java:228` — `payload.protocolVersion() == VoxyWorldGenV2.PROTOCOL_VERSION` in the ack receiver
- `fabric/.../network/NetworkClientHandler.java:38` — the same comparison client-side

Leaving them beside floor-based gates creates a live contradiction: a protocol-4 client on a protocol-5 server passes `supportsStorageReport()` (4 >= 3) while being marked `!isModded` by the equality check, so it sends a storage report the server accepts but receives no LOD data.

In the ack receiver, replace the equality check with `PlayerTracker.setClientProtocol(player.getUUID(), payload.clientProtocol())`.

- [ ] **Step 3: Remove the modded gate**

Delete `PlayerTracker.setModded` / `isModded` / `anyModded` and fix every caller:

- `ChunkGenerationManager.moddedPlayers()` (~:757) — becomes "all tracked players"; the join gate now decides who receives data, not who causes generation.
- `ServerEventHandler.onChunkLoad` (~:48 `anyModded`, ~:52 `isModded`) — drop both guards.
- `NetworkHandler.sendServerConfig` — drop the `isModded` early return.
- `ChunkUpdateTracker.markDirty` — drop the `anyModded` fast path.

**Ordering constraint that must survive:** `sendServerConfig(player)` is called *inside* the ack receiver, and it previously early-returned on `!isModded`. With that gate gone it can be called unconditionally, but it must still run **after** `setClientProtocol`, because task 6 gates the settings snapshot on the recorded protocol.

- [ ] **Step 4: Verify**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build --console=plain
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
grep -rn "isModded\|anyModded\|setModded" common/ fabric/ || echo "modded gate fully removed"
```

Expected: BUILD SUCCESSFUL, 8 classes green, no `isModded` references remain.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: adopt the fork's handshake ack; remove the modded generation gate"
```

---

### Task 3: Reshape LODDataPayload to the compressed form

Unified's payload is `(dimension, pos, minY, List<SectionData>)` on a `StreamCodec<RegistryFriendlyByteBuf, ...>`, uncompressed. The fork's protocol-4 payload is `(dimension, pos, minY, int plainLength, byte[] body)` on a plain `FriendlyByteBuf`, deflated by a static `of(...)` factory, with `wireSize()` and `decodeSections()`.

Per §3b the fork's shape wins. Three reasons, and only the first was in the spec: per-player Mbps caps are only honest when metered on post-deflate bytes; a `RegistryFriendlyByteBuf` codec cannot be unit-tested without bootstrapping the game, which is why `LODDataPayloadTest` assumes the plain form; and on 26.2 the palette codec is registry-free anyway, so the registry buf was always ceremony.

**Files:**
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`
- Modify: `fabric/src/client/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/network/LODDataPayloadTest.java`

**Interfaces:**
- Produces: `LODDataPayload.of(ResourceKey<Level>, ChunkPos, int minY, List<SectionData>)`, `wireSize()`, `decodeSections()`. Task 4's `LodSendQueue` calls `of(...)`; task 7's `DebugRenderer` reads `wireSize()`.

- [ ] **Step 1: Copy the test**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/network/LODDataPayloadTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/network/LODDataPayloadTest.java
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — `cannot find symbol: method of(...)`, `wireSize()`, `decodeSections()`.

- [ ] **Step 3: Port the record**

Take `LODDataPayload` from `origin/feature/per-player-limits:src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java` (line 92 onward, through `decodeSections`). Apply these 26.2 conversions:

- `dimension.location()` → `dimension.identifier()`
- `pos.x` / `pos.z` field reads → `pos.x()` / `pos.z()`
- `ResourceLocation` → `Identifier`; `ResourceKey.create(Registries.DIMENSION, id)` takes an `Identifier`
- Codec type parameter stays `FriendlyByteBuf` — do **not** reintroduce `RegistryFriendlyByteBuf`

Keep `RAW_SECTION_BYTES.addAndGet(plain.length)` and `WIRE_SECTION_BYTES.addAndGet(body.length)` exactly as written (per-player-limits lines 147-148). These are the counters the rate limiter and F3 overlay read.

- [ ] **Step 4: Update the client receive path**

In `NetworkClientHandler`, replace the section-list read with `payload.decodeSections()`. Rebuild sections using the 26.2 factory form — the old `new LevelChunkSection(Registry<Biome>)` constructor is gone:

```java
        PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        LevelChunkSection section = new LevelChunkSection(factory);
        ((PalettedContainer<BlockState>) section.getStates()).read(buf);
        ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(buf);
```

`getStates()` returns `PalettedContainer<BlockState>`; `getBiomes()` returns `PalettedContainerRO<Holder<Biome>>`, hence the cast before `read`. Delete any `registryAccess().registryOrThrow(Registries.BIOME)` lookup — `registryOrThrow` is renamed to `lookupOrThrow` on 26.2, and with the factory the lookup disappears entirely.

**Keep unified's drain queue.** `drainIngestQueue` and its 96-sections/tick nearest-first budget stay exactly as they are; only the decode step changes. This is the half of §3b that protects client FPS.

- [ ] **Step 5: Run to verify it passes**

Expected: PASS, 9 test classes.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: adopt the fork's deflated LODDataPayload on a plain byte buf"
```

---

### Task 4: Adopt LodSendQueue, snapshotSections and the wire counters

The fork serialises on one dedicated thread with a bounded queue; unified uses a pool with `CallerRunsPolicy`. §3b keeps the fork's. Critically, **the fork's design is already thread-safe and must be copied whole**: `snapshotSections()` calls `section.getStates().copy()` on the **main thread** and the sender serialises that private copy, because a live container is guarded by a `ThreadingDetector`. `PalettedContainer.copy()` exists on 26.2 — verified by javap.

**Files:**
- Create: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/LodSendQueue.java`
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`

**Interfaces:**
- Produces: `LodSendQueue.getInstance()`, `enqueue(...) → boolean`, `NetworkHandler.RAW_SECTION_BYTES` / `WIRE_SECTION_BYTES` (`public static final AtomicLong`). Plan 3's `/voxygen`, `SettingsBook` and `TabHud` all read these.

- [ ] **Step 1: Copy LodSendQueue and snapshotSections**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git -C $FORK show origin/port/26.2:src/main/java/com/ethan/voxyworldgenv2/network/LodSendQueue.java \
  > fabric/src/main/java/com/ethan/voxyworldgenv2/network/LodSendQueue.java
```

Take `snapshotSections()` and the two `AtomicLong` counters from `origin/port/26.2`'s `NetworkHandler` (counters at lines 71 and 73) into the fabric handler.

Drop the `RegistryAccess` parameter threaded through `serialiseStates` / `Job` / `PendingSection`. On 26.2 `PalettedContainerRO.write(FriendlyByteBuf)` takes a plain buf, so that parameter is dead weight.

- [ ] **Step 2: Widen MAX_PACKET_BYTES visibility**

`fabric/.../NetworkHandler.java:36` is `private static final int MAX_PACKET_BYTES = 32_768;`. Change `private` to package-private (no modifier) so `LodSendQueue` and `KnownChunksPayload` can read it. **Do not change the value** — `RegionBitmask.MAX_REGIONS_PER_PACKET = 180` is derived from 32,768 in its javadoc, and a changed value breaks that derivation while its 13 tests keep passing.

- [ ] **Step 3: Replace the SEND_POOL, keeping unified's two improvements**

Delete `SEND_POOL` and route sends through `LodSendQueue`. Two unified-only behaviours must survive, because the fork lacks both and a wholesale swap **regresses** them:

- `broadcastLODData(LevelChunk, IntSet onlySectionYs)` — the partial-section path for block edits. The fork's `ChunkUpdateTracker.markDirty(LevelChunk)` has no `blockY` and resends the **full column** on every block edit. Keep unified's `IntSet` overload and its caller.
- The `isAllZero` block-light skip in section building.

Keep unified's `syncRadiusSq()` (derived from `generationRadius`, already exposed via `INetworkBridge`) over the fork's hardcoded `4096.0 * 4096.0`. The fork's constant is independent of configured radius and would silently change which players receive which chunks once the radius is tuned.

- [ ] **Step 4: Verify**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build --console=plain
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: BUILD SUCCESSFUL, 9 classes still green.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: adopt LodSendQueue and wire-byte accounting, keeping partial-section updates"
```

---

### Task 5: Add KnownChunksPayload and the join gate

This is what makes `LodMemory` worth having: the client uploads what it already holds, and the server skips re-streaming it.

**Files:**
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java`

**Interfaces:**
- Produces: `KnownChunksPayload(ResourceKey<Level> dimension, boolean last, byte[] body)`; `PlayerTracker.isGated(UUID, String dimensionId)`. Plan 3's `LodMemory` sends this payload.

- [ ] **Step 1: Port the payload**

Copy `KnownChunksPayload` from `origin/port/26.2`'s `NetworkHandler` (line 196) — already 26.2-correct. It caps its read with `buf.readByteArray(MAX_PACKET_BYTES)`, which is why task 4 widened that constant's visibility.

- [ ] **Step 2: Port the join gate**

Port `PlayerTracker.isGated(UUID, String)` and its timeout bookkeeping from `origin/port/26.2`. Semantics that must be preserved exactly:

- The gate withholds LOD data while waiting for the client's known-chunks upload.
- It **expires open** after `Config.DATA.knownChunksTimeoutSeconds` (default 10), so a vanilla client that never uploads behaves exactly as it did before the feature existed.
- It is keyed **per dimension**, not per player.
- `rememberSentChunks = false` must be a true rollback: the server ignores uploaded sets and re-streams everything.

- [ ] **Step 3: Wire the receiver**

Register the serverbound receiver and apply uploaded region bitmasks into `SyncedChunkStore.applyRegions(...)` (already present and tested from plan 1).

- [ ] **Step 4: Verify**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build --console=plain
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add KnownChunksPayload and the time-boxed join gate"
```

---

### Task 6: Add the storage report and settings payloads

Protocol 3 adds `StorageReportPayload`; protocol 4 adds `SettingsSnapshotPayload` and `SettingsUpdatePayload`. Both come from `origin/feature/per-player-limits` (MC 1.21.1) and need the rename pass.

**Files:**
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java`
- Test: `SettingsPayloadTest.java`, `StorageReportPayloadTest.java`, `SendDistanceTest.java`

**Interfaces:**
- Consumes: `SettingsApplier.apply(List<Op>, boolean)` from plan 1; `Config.getMaxMbpsForPlayer` / `getSendDistanceForPlayer`.
- Produces: the three payloads. Plan 3's settings screen and storage reporter send/receive them.

- [ ] **Step 1: Copy the three tests**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
for t in SettingsPayloadTest StorageReportPayloadTest SendDistanceTest; do
  git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/network/$t.java \
    > fabric/src/test/java/com/ethan/voxyworldgenv2/network/$t.java
done
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — missing `StorageReportPayload`, `SettingsSnapshotPayload`, `SettingsUpdatePayload`.

- [ ] **Step 3: Port the payloads**

From `origin/feature/per-player-limits`'s `NetworkHandler`: `StorageReportPayload` (:243), `SettingsSnapshotPayload` (:292), `SettingsUpdatePayload` (:372). Apply the rename pass, in particular:

- `player.hasPermissions(2)` → `player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)` in both `receiveSettingsUpdate` and `sendSettingsSnapshot`.
- `ResourceLocation` → `Identifier` for the new `*_ID` constants.

Gate the clientbound `SettingsSnapshotPayload` on `PlayerTracker.getClientProtocol(uuid) >= 4` (task 2). An older client still gets terrain, just no settings screen.

- [ ] **Step 4: Register all nine payloads**

In `init()`, using 26.2 API names — `PayloadTypeRegistry.serverboundPlay()` and `clientboundPlay()`, **not** `playC2S()`/`playS2C()`:

| Payload | Direction |
|---|---|
| `HandshakePayload` | both (keep the existing dual registration) |
| `HandshakeAckPayload` | serverbound |
| `LODDataPayload` | clientbound |
| `ServerConfigPayload` | clientbound |
| `ServerConfigPushPayload` | serverbound |
| `KnownChunksPayload` | serverbound |
| `StorageReportPayload` | serverbound |
| `SettingsSnapshotPayload` | clientbound |
| `SettingsUpdatePayload` | serverbound |

`init()` must run in the **`main`** entrypoint — registration has to complete before any connection, and moving it into `client` breaks dedicated servers entirely. Keep `HandshakePayload`'s dual registration: Fabric throws at registration time if a payload is sent on an unregistered direction.

- [ ] **Step 5: Run to verify they pass**

Expected: PASS, **12 test classes**. Note `SettingsPayloadTest` asserts `PROTOCOL_VERSION >= 4` and `StorageReportPayloadTest` asserts `>= 3` — both are floors and hold at 5.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add storage report and settings payloads, completing protocol 5"
```

---

### Task 7: Widen rawIngest, extend the bridge, and fix the F3 counters

Three loose ends that the earlier tasks create.

**Files:**
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/integration/VoxyIntegration.java`
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/platform/INetworkBridge.java`
- Modify: `fabric/src/main/java/com/ethan/voxyworldgenv2/platform/FabricNetworkBridge.java`
- Modify: `neoforge/src/main/java/com/ethan/voxyworldgenv2/platform/NeoForgeNetworkBridge.java`
- Modify: `fabric/src/client/java/com/ethan/voxyworldgenv2/client/DebugRenderer.java`
- Modify: `HANDOFF.md`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/client/ClientStorageReporterTest.java`

- [ ] **Step 1: Widen rawIngest from void to boolean**

`common/.../VoxyIntegration.java` has two `void rawIngest` overloads (7-arg at :148, 6-arg at :162). Both must return `boolean`. Return `false` for **both** a failed invoke and an unresolvable reflected handle — `LodMemory`'s correctness argument is `allIngested &= rawIngest(...)`, and the record must mean "Voxy has this", not "a packet arrived". If it records chunks Voxy never received, the server permanently skips them and the player sees holes.

This is source-compatible: both existing callers (fabric client, neoforge client) discard the result, so **no call sites change**. `origin/port/26.2` already carries the boolean version plus `warnRawIngestUnavailableOnce` — copy from there.

Verify `common/` stays loader-clean afterwards.

- [ ] **Step 2: Extend INetworkBridge**

Adopting `LodSendQueue` puts a `fabric/` class on the far side of a module boundary from `common/` callers. Add to `INetworkBridge`:

```java
    void startSendQueue();
    boolean isSendQueueSaturated();
    java.util.Map<java.util.UUID, Long> perPlayerWireBytes();
```

Implement in `FabricNetworkBridge` against `LodSendQueue`. **Stub in `NeoForgeNetworkBridge`** — no-op start, always-unsaturated, empty map — since NeoForge keeps the `SEND_POOL` design. Missing this second implementation breaks the `:neoforge` compile.

- [ ] **Step 3: Fix the F3 overlay**

`DebugRenderer` is the **only** reader of `NetworkState`'s counters in the fabric module (:56, :68, :69, :73, :74). Task 4 changes `incrementReceived` from summing decompressed array lengths to `payload.wireSize()`, so the "bandwidth" and "received" lines silently start meaning wire bytes — roughly a 10× apparent drop with no visible change at the display site. Relabel them explicitly.

Also honour `Config.DATA.hudShowCompressed` / `hudShowSavings` / `hudShowClientDisk`. These already exist in `Config` and are already writable via `SettingsApplier`, but unified has **no** `command/` package, so on this branch they currently control nothing. `DebugRenderer` is the only place that can honour them until plan 3 lands `TabHud`.

- [ ] **Step 4: Add the sixth test**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
mkdir -p fabric/src/test/java/com/ethan/voxyworldgenv2/client
git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/client/ClientStorageReporterTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/client/ClientStorageReporterTest.java
```

This test pins `StorageReportPayload`'s only sender. It needs `ClientStorageReporter`, which is a plan-3 file — **if it cannot compile yet, delete it again and note it as plan 3's first task.** Do not stub `ClientStorageReporter` to make it pass.

Three things not to "clean up" when it does land: `directorySize` is package-private on purpose (the test must live in the same package); `@TempDir` is declared twice, as a field and as a parameter, deliberately; and `missingDirectoryIsNotAValue` asserts **-1, not 0**, because "no store" must stay distinguishable from "empty store".

- [ ] **Step 5: Full verification**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build --console=plain
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --rerun-tasks --console=plain
grep -rn "net.fabricmc" common/src/main/java/ || echo "common/ loader-clean"
```

- [ ] **Step 6: Update HANDOFF.md and commit**

Mark plan 2 done; record the protocol-5 constant's new home, the nine payloads, and that the modded gate is gone.

```bash
git add -A && git commit -m "feat: widen rawIngest, extend the network bridge, fix the F3 counters"
```

---

## Done when

`:fabric:build` and `:fabric:test` both pass with **12 test classes** (13 if `ClientStorageReporterTest` lands here rather than plan 3). `PROTOCOL_VERSION` is 5 on the Fabric handler, 1 on NeoForge's, and absent from `common/`. `grep -rn "isModded" common/ fabric/` returns nothing.

## Explicitly not in this plan

The `command/` package, `SettingsBook`, `TabHud`, `LodMemory`, `ClientStorageReporter`, `VoxyWorldGenSettingsScreen`, the tab-list mixins, the spawn anchor, and deployment.

## Risks

| Risk | Mitigation |
|---|---|
| Deleting the modded gate makes the server generate for vanilla players it previously ignored | Intended per §3b; the join gate bounds the cost and expires open |
| Wholesale `LodSendQueue` adoption regresses block-edit updates to full-column resends | Task 4 step 3 explicitly keeps unified's `IntSet` partial-section path |
| `MAX_PACKET_BYTES` value drifts, silently invalidating `RegionBitmask`'s 180 | Value is frozen; only visibility changes |
| A protocol-4 client half-connects to a protocol-5 server | The two equality checks are deleted and replaced with floors plus a recorded client protocol |
| `:neoforge` breaks on the `INetworkBridge` additions | Task 7 step 2 stubs all three methods there |
