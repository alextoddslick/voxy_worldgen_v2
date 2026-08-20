# Plan 1: Foundation — Test Harness, Config Merge, Pure-JVM Classes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the unified branch a working JUnit harness and land the fork's loader-independent classes (`Config`, `SyncedChunkStore`, `RegionBitmask`, `PlayerHistory`, `SettingsApplier`) with their six tests green.

**Architecture:** All new code goes in `fabric/src/main/java` — never `common/`, because `common/` is `srcDir`-merged into the `:neoforge` module too and compiled against MC 1.21.1 on Java 21. NeoForge is not a target; placing code in `fabric/` keeps it compiling for free. Every class in this plan is pure Java + Gson with no Minecraft imports, so nothing here is a 1.21.1→26.2 port — it is re-homing plus one Config merge.

**Tech Stack:** Java 25, Gradle 9.5.1, Fabric Loom 1.17-SNAPSHOT, JUnit 5, Gson.

**Spec:** `docs/superpowers/specs/2026-08-19-unified-port-and-spawn-pregen-design.md`

## Global Constraints

- `JAVA_HOME=/opt/homebrew/opt/openjdk@25` — write this **literally**. `$(/usr/libexec/java_home -v 25)` returns `/usr/local/Cellar/openjdk/25.0.2/...`, which is the **x86_64** JDK and must never be used. The arm64 JDK 25.0.3 is not registered with `java_home` at all.
- Build/test with `:fabric:` targets only: `./gradlew :fabric:build`, `./gradlew :fabric:test`. A bare `./gradlew build` pulls in `:neoforge:createMinecraftArtifacts`, which decompiles NeoForge 21.1.232 against Parchment for MC 1.21.1 — minutes of unrelated work.
- `org.gradle.daemon=false` is set, so every invocation forks a single-use daemon. A ~3-6s floor per command is normal, not a hang.
- `gradlew` shows as ` M gradlew` (mode 100644 → 100755) in `git status`. That change is **expected and required**; never `git checkout -- gradlew`.
- Source repo for all copied files: `/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2` (referred to below as `$FORK`). Worktree: `/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified`.
- House style (from the fork's `CONTRIBUTING.txt`): comments are clean and concise; every non-obvious test assertion carries a comment explaining the real-world failure it prevents. Preserve the fork's WHY-comments verbatim when copying.
- Never call `Config.load()` or `Config.save()` from a test — both reach `Services.PLATFORM.getConfigDir()` and throw without loader bindings.

---

### Task 1: Stand up the JUnit test harness

Unified has **zero** test files and **zero** test configuration — verified with `git ls-tree -r --name-only upstream/unified | grep -i test` (returns nothing) and by grepping both `build.gradle` files for `test`/`junit` (no matches). This task creates the harness and proves it runs.

**Files:**
- Modify: `fabric/build.gradle` (append after the existing `java { }` block)
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/HarnessTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a working `:fabric:test` task on JUnit 5. Every later task in every later plan depends on it.

- [ ] **Step 1: Write the failing test**

Create `fabric/src/test/java/com/ethan/voxyworldgenv2/HarnessTest.java`:

```java
package com.ethan.voxyworldgenv2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the JUnit 5 harness is wired up. Gradle 9.5.1 fails the build outright when test
 * sources exist but no engine discovers them, so a green run here is what distinguishes
 * "harness works" from "harness silently skipped everything".
 */
class HarnessTest {

    @Test
    void harnessRunsJUnit5Tests() {
        assertEquals(4, 2 + 2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL. Because no JUnit dependency and no `useJUnitPlatform()` exist yet, Gradle reports
`There are test sources present and no filters are applied, but the test task did not discover any tests to execute.`
This is a hard failure, not a skip — that is the point of running it now.

- [ ] **Step 3: Add the test source set and JUnit dependencies**

In `fabric/build.gradle`, add these two dependency lines inside the existing `dependencies { }` block:

```groovy
    testImplementation platform("org.junit:junit-bom:5.11.3")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
```

Then append this block at the end of the file:

```groovy
// Loom's splitEnvironmentSourceSets() creates a separate `client` source set. Tests need to see
// both it and main, otherwise client-only classes (ClientStorageReporter in plan 3) are invisible
// to their tests even though they share a package.
sourceSets {
    test {
        compileClasspath += sourceSets.main.output + sourceSets.client.output
        runtimeClasspath += sourceSets.main.output + sourceSets.client.output
    }
}

configurations {
    testImplementation.extendsFrom implementation
    testRuntimeOnly.extendsFrom runtimeOnly
}

test {
    useJUnitPlatform()
    testLogging {
        events "failed", "skipped"
        exceptionFormat "full"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 1 test executed, 0 failed.

- [ ] **Step 5: Commit**

```bash
git add fabric/build.gradle fabric/src/test/java/com/ethan/voxyworldgenv2/HarnessTest.java
git commit -m "build: add a JUnit 5 test source set to the fabric module"
```

---

### Task 2: Re-home SyncedChunkStore

`SyncedChunkStore` is **byte-identical** on `origin/port/26.2` and `origin/feature/per-player-limits` (159 lines, verified by `diff`), and has no Minecraft imports. This is a pure copy.

**Files:**
- Create: `fabric/src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java`

**Interfaces:**
- Consumes: the harness from Task 1.
- Produces: `SyncedChunkStore` with `markSynced`, `markDeferred`, `isSynced`, `isDeferred`, `clearDeferred`, `setFor`, `size`, `forgetWithin`. Plan 2's network gate and plan 4's anchor both read it.

- [ ] **Step 1: Copy the test first**

```bash
cd /Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
mkdir -p fabric/src/test/java/com/ethan/voxyworldgenv2/core
git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL — compilation error, `cannot find symbol: class SyncedChunkStore`.

- [ ] **Step 3: Copy the implementation**

```bash
mkdir -p fabric/src/main/java/com/ethan/voxyworldgenv2/core
git -C $FORK show origin/port/26.2:src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java \
  > fabric/src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java
```

Do **not** modify the file. Two things in it look wrong and are deliberate:

- `packChunk` reimplements `ChunkPos.asLong` as `((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32)` so the class carries no Minecraft types and stays unit-testable. Do not "simplify" it to `Services.CHUNK_POS.asLong` — that drags the ServiceLoader into the test path.
- `deferred` is a second, separate set from `synced`. Three tests pin this: `markDeferred` alone leaves `isSynced` false, `size()` 0, and `setFor()` not containing the pos. The semantic is "claimed but not delivered" — the worker skips synced chunks while `onChunkLoad` still sends deferred ones. Merging the sets breaks the catch-up loop's contract.

- [ ] **Step 4: Run to verify it passes**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: PASS. Note `forgetWithin` is a **circle, not a square** — the test seeds `(0,0)`, `(3,4)`, `(6,0)`, `(-5,0)` and asserts `forgetWithin(...,0,0,5)` returns 3 and spares `(6,0)`. A Chebyshev implementation returns 4 and fails here.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/com/ethan/voxyworldgenv2/core/SyncedChunkStore.java \
        fabric/src/test/java/com/ethan/voxyworldgenv2/core/SyncedChunkStoreTest.java
git commit -m "feat: re-home SyncedChunkStore and its tests into the fabric module"
```

---

### Task 3: Re-home RegionBitmask

Also byte-identical across both fork branches (242 lines) and Minecraft-free.

**Files:**
- Create: `fabric/src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java`

**Interfaces:**
- Consumes: the harness from Task 1.
- Produces: `RegionBitmask` with constants `MASK_BYTES = 128` and `MAX_REGIONS_PER_PACKET = 180`, plus its encode/decode pair. Plan 2's `KnownChunksPayload` depends on it.

- [ ] **Step 1: Copy the test first**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
mkdir -p fabric/src/test/java/com/ethan/voxyworldgenv2/network
git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL — `cannot find symbol: class RegionBitmask`.

- [ ] **Step 3: Copy the implementation**

```bash
mkdir -p fabric/src/main/java/com/ethan/voxyworldgenv2/network
git -C $FORK show origin/port/26.2:src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java \
  > fabric/src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java
```

Do not change `MASK_BYTES` or `MAX_REGIONS_PER_PACKET`. `decodeRejectsCorruptTruncatedAndDisagreeingRawLength` hand-builds `ByteBuffer.allocate(1 + 8 + RegionBitmask.MASK_BYTES + 500)` and asserts `payloadLength < rawLength` as a **setup precondition** — change either constant and that precondition fires before the real assertions run.

`regionKey` relies on arithmetic shift (`chunkX >> REGION_SHIFT`) being correct for negatives: chunk -1 lands in region -1 at bit index 1023, and region -1 covers chunks -32..-1. Do not substitute an unsigned shift.

- [ ] **Step 4: Run to verify it passes**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: PASS, including the understated-`Inflater`-`rawLength` case.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/com/ethan/voxyworldgenv2/network/RegionBitmask.java \
        fabric/src/test/java/com/ethan/voxyworldgenv2/network/RegionBitmaskTest.java
git commit -m "feat: re-home RegionBitmask and its codec tests into the fabric module"
```

---

### Task 4: Merge Config

This is the only genuine merge in this plan. The fork's `Config` on `origin/feature/per-player-limits` is a **strict superset** of `port/26.2`'s (verified by `diff`: it adds `getMaxMbpsForPlayer`, `getSendDistanceChunks`, `getSendDistanceForPlayer`, `lodSendDistanceChunks`, `playerRateLimits`, `playerSendDistances`, `hudShow*`). It has no Minecraft imports. Unified's `Config` contributes `showF3MenuStats` and the whole `ServerConfig` push feature.

Take the fork's file as the base and add unified's pieces to it — not the other way around.

**Files:**
- Modify: `common/src/main/java/com/ethan/voxyworldgenv2/core/Config.java` (replaced wholesale)
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/core/ConfigSingleplayerTest.java`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/core/ConfigPerPlayerTest.java`

`Config` stays in `common/` — it is plain Java and Gson, so it compiles under both Java 21 and Java 25, and both loaders already reference it.

**Interfaces:**
- Consumes: nothing.
- Produces: `Config.DATA` (type `Config.ConfigData`) carrying all fork fields; `Config.getGenerationRadius(boolean)`, `getMaxActiveTasks(boolean)`, `getMaxMbpsPerPlayer(boolean)`, `getMaxChunksPerSecond(boolean)`, `getDimensionChangePauseSeconds(boolean)`, `getMaxMbpsForPlayer(UUID, boolean)`, `getSendDistanceChunks(boolean)`, `getSendDistanceForPlayer(UUID, boolean)`; and unified's `Config.ServerConfig` record with `snapshot()` / `applyServerConfig(ServerConfig)`. Plans 2, 3 and 4 all read these.

- [ ] **Step 1: Copy both tests first**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
for t in ConfigSingleplayerTest ConfigPerPlayerTest; do
  git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/core/$t.java \
    > fabric/src/test/java/com/ethan/voxyworldgenv2/core/$t.java
done
```

- [ ] **Step 2: Run to verify they fail**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL — compilation errors such as `cannot find symbol: variable singleplayer`, `method getGenerationRadius(boolean)`, `variable playerRateLimits`. Unified's `ConfigData` has only six fields.

- [ ] **Step 3: Replace Config with the fork's, then re-add unified's pieces**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git -C $FORK show origin/feature/per-player-limits:src/main/java/com/ethan/voxyworldgenv2/core/Config.java \
  > common/src/main/java/com/ethan/voxyworldgenv2/core/Config.java
```

Then make exactly these three edits to that file.

**(a)** Add unified's client-local field to `ConfigData`, next to the other client-side fields:

```java
        // client side, local to each player
        public boolean showF3MenuStats = true;
```

**(b)** Add unified's server-config push feature. Insert before the closing brace of `Config`:

```java
    /** The subset of settings an operator can push from the client. */
    public record ServerConfig(boolean enabled, int generationRadius, int updateInterval,
                               int maxQueueSize, int maxActiveTasks) {
        public static ServerConfig snapshot() {
            return new ServerConfig(DATA.enabled, DATA.generationRadius, DATA.update_interval,
                    DATA.maxQueueSize, DATA.maxActiveTasks);
        }
    }

    public static void applyServerConfig(ServerConfig sc) {
        DATA.enabled = sc.enabled();
        DATA.generationRadius = clamp(sc.generationRadius(), 1, 512);
        DATA.update_interval = clamp(sc.updateInterval(), 1, 200);
        DATA.maxQueueSize = Math.max(0, sc.maxQueueSize());
        DATA.maxActiveTasks = clamp(sc.maxActiveTasks(), 1, 128);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
```

**(c)** Keep the fork's `private static Path getConfigPath()` exactly as it is. Do **not** replace it with unified's `private static final Path CONFIG_PATH = Services.PLATFORM.getConfigDir()...`. Unified's form is a static initializer that runs `Services.load()` the moment `Config` is touched, and `Services.load()` throws `IllegalStateException("No service implementation found for ...")` under a plain JUnit JVM. The fork's lazy method wraps `FabricLoader` in try/catch and falls back to `Path.of("config", "voxyworldgenv2.json")`, which is exactly why these tests can run headless.

Likewise keep the fork's two null guards in `load()` (`if (DATA == null)` for an empty file, `if (DATA.singleplayer == null)` for a hand-edited null). Unified has neither, so an empty `voxyworldgenv2.json` NPEs on the worker thread — and `workerLoop`'s `catch (Exception e)` swallows it into a 1-second retry loop, i.e. a silent permanent stall.

Keep `generationRadius = 64`. Unified defaults it to 128, but `ConfigSingleplayerTest` asserts `assertEquals(128, Config.getGenerationRadius(true))` precisely because the singleplayer auto rule is `max(128, base=64)`. Changing the base to 128 makes every arithmetic assertion in that file vacuous.

Keep `update_interval` snake_case. It is a deliberately preserved legacy JSON field name; renaming it breaks every existing `voxyworldgenv2.json` on disk.

- [ ] **Step 4: Run to verify they pass**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: PASS, all tests including `HarnessTest`, `SyncedChunkStoreTest`, `RegionBitmaskTest`.

- [ ] **Step 5: Verify the fabric module still compiles**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build --console=plain
```

Expected: `BUILD SUCCESSFUL`. If `ChunkGenerationManager` or the Cloth config screen fails to compile, it is referencing a unified-only Config member that step 3 dropped — re-add it rather than deleting the caller.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/com/ethan/voxyworldgenv2/core/Config.java \
        fabric/src/test/java/com/ethan/voxyworldgenv2/core/ConfigSingleplayerTest.java \
        fabric/src/test/java/com/ethan/voxyworldgenv2/core/ConfigPerPlayerTest.java
git commit -m "feat: merge the fork's Config into unified, keeping lazy path resolution"
```

---

### Task 5: Re-home PlayerHistory

`PlayerHistory` (121 lines) exists only on `origin/feature/per-player-limits`, but it has no Minecraft imports — only Gson and JDK. It resolves its path via a fully-qualified `net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()` at line 40 with a `Path.of("config", ...)` fallback at line 43. Because this file lands in `fabric/`, FabricLoader is on the classpath and **no change is needed**.

**Files:**
- Create: `fabric/src/main/java/com/ethan/voxyworldgenv2/core/PlayerHistory.java`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/core/PlayerHistoryTest.java`

**Interfaces:**
- Consumes: `Config` from Task 4.
- Produces: `PlayerHistory(Path file)` and `recordSession(...)`. Plan 3's tab HUD and settings screen read it.

- [ ] **Step 1: Copy the test first**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/core/PlayerHistoryTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/core/PlayerHistoryTest.java
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL — `cannot find symbol: class PlayerHistory`.

- [ ] **Step 3: Copy the implementation**

```bash
git -C $FORK show origin/feature/per-player-limits:src/main/java/com/ethan/voxyworldgenv2/core/PlayerHistory.java \
  > fabric/src/main/java/com/ethan/voxyworldgenv2/core/PlayerHistory.java
```

- [ ] **Step 4: Run to verify it passes**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: PASS. Two behaviours the tests pin, so do not "tidy" the class:
- `recordSession` is **delta-based within a run, absolute across runs**. Same-instance calls of 100 then 250 store 250, not 350; a new instance reading the same file then recording 40 stores 290. The baseline resets per instance, not per file.
- A `-1` `diskBytes` must never overwrite a stored value (`aMissingDiskReportNeverErasesAKnownOne`). `-1` means "no store", which is distinct from "empty store".

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/com/ethan/voxyworldgenv2/core/PlayerHistory.java \
        fabric/src/test/java/com/ethan/voxyworldgenv2/core/PlayerHistoryTest.java
git commit -m "feat: re-home PlayerHistory and its tests into the fabric module"
```

---

### Task 6: Re-home SettingsApplier, then write HANDOFF.md

`SettingsApplier` (112 lines) imports only `VoxyWorldGenV2`, `java.util.List` and `java.util.UUID` — no Minecraft. It depends on Task 4's Config.

**Files:**
- Create: `fabric/src/main/java/com/ethan/voxyworldgenv2/core/SettingsApplier.java`
- Create: `HANDOFF.md`
- Test: `fabric/src/test/java/com/ethan/voxyworldgenv2/core/SettingsApplierTest.java`

**Interfaces:**
- Consumes: `Config` from Task 4.
- Produces: `SettingsApplier.Op(String key, String value)` and `static int apply(List<Op> ops, boolean singleplayerActive)`. Plan 2's settings payload and plan 3's `/voxygen` command both call it.

- [ ] **Step 1: Copy the test first**

```bash
FORK=/Users/alextodd/temp/Github-NOTSYNCED/voxy_worldgen_v2
git -C $FORK show origin/feature/per-player-limits:src/test/java/com/ethan/voxyworldgenv2/core/SettingsApplierTest.java \
  > fabric/src/test/java/com/ethan/voxyworldgenv2/core/SettingsApplierTest.java
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: FAIL — `cannot find symbol: class SettingsApplier`.

- [ ] **Step 3: Copy the implementation**

```bash
git -C $FORK show origin/feature/per-player-limits:src/main/java/com/ethan/voxyworldgenv2/core/SettingsApplier.java \
  > fabric/src/main/java/com/ethan/voxyworldgenv2/core/SettingsApplier.java
```

- [ ] **Step 4: Run to verify it passes**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:test --console=plain
```

Expected: PASS. The clamps must match the `/voxygen` command ranges exactly — radius 1..512 (9999 → 512), tasks 1..128 (0 → 1), a negative ratelimit → 0.0 meaning unlimited. And the return value counts **only what actually applied**: a batch of `{unknown key, unparseable radius, non-UUID player key, valid tasks}` returns 1, not 4, and does not throw. An implementation that returns `ops.size()` passes nothing.

- [ ] **Step 5: Write HANDOFF.md**

`upstream/unified` has no `HANDOFF.md`; Alex's standing rule requires one in every project. Create it at the repo root covering: what this branch is (`port/unified-26.2`, upstream `unified` 2.4.3 plus the fork's feature line), the `fabric/`-not-`common/` placement rule and why, the literal `JAVA_HOME` requirement, `:fabric:` targets only, the ` M gradlew` expectation, the five-plan sequence with plan 1 marked done, and a pointer to the spec and this plan.

- [ ] **Step 6: Full build and commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric:build --console=plain
git add fabric/src/main/java/com/ethan/voxyworldgenv2/core/SettingsApplier.java \
        fabric/src/test/java/com/ethan/voxyworldgenv2/core/SettingsApplierTest.java HANDOFF.md
git commit -m "feat: re-home SettingsApplier; add HANDOFF.md for the merged branch"
```

---

## Done when

`./gradlew :fabric:build` and `./gradlew :fabric:test` both succeed with **seven test classes green**: `HarnessTest`, `SyncedChunkStoreTest`, `RegionBitmaskTest`, `ConfigSingleplayerTest`, `ConfigPerPlayerTest`, `PlayerHistoryTest`, `SettingsApplierTest`. The jar still builds as `Voxy World Gen V2-fabric-26.2-2.4.3.jar`.

## Explicitly not in this plan

`NetworkHandler` reconciliation, `LodSendQueue`, protocol 5, the `command/` package, any client GUI, `LodMemory`, the spawn anchor, and deployment. `HandshakePayloadTest`, `LODDataPayloadTest`, `SendDistanceTest`, `SettingsPayloadTest`, `StorageReportPayloadTest` and `ClientStorageReporterTest` all belong to plans 2 and 3 — they cannot compile until the network layer is merged.
