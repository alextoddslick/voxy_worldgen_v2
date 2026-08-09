# Voxy World Gen V2

![Logo](src/main/resources/logo.png)

This is a rewrite of my old Voxy World Gen mod, this mod is NOT a fork of the passive chunk generator mod and instead is a entirely different mod.

## About this branch (`port/1.21.11`)

This branch is a **port of Voxy World Gen V2 to Minecraft 1.21.11 (Fabric)**, maintained on a fork of
[the original repository by iSeeEthan](https://github.com/iSeeEthan/voxy_worldgen_v2). Sibling
`port/*` branches on this fork cover the other Minecraft versions (1.21.11, 26.1.2, 26.2).

It pairs with the [Voxy](https://modrinth.com/mod/voxy) client mod for LOD rendering — see
**Requirements & Downloads** below for the exact versions this branch is tested against.

Beyond the version port, this branch adds server-operations features on top of upstream:
`/voxygen` admin commands (status, traffic, rate limiting, refresh, live reload), an off-thread LOD send
queue with backpressure and per-player bandwidth caps, whole-batch zlib compression of LOD
payloads, periodic generation progress logging, a virtual-book settings UI, and a tab-list HUD.

## Features

- Generates chunks very fast in the background and auto-ingest them with voxy.
- Configurable generation speed and queue size.
- Tellus integration. https://github.com/Yucareux/Tellus
- Server-side support

## Requirements & Downloads

This branch targets **Minecraft 1.21.11 (Fabric)** on **Java 25+** with **Fabric Loader ≥0.18.4**
([server launcher](https://fabricmc.net/use/server/)).

### Server (required)

| Mod | Tested version | Download |
|-----|----------------|----------|
| Fabric API | `0.141.6+1.21.11` | [Modrinth](https://modrinth.com/mod/fabric-api) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) |

### Client (required for LODs to render)

| Mod | Tested version | Notes | Download |
|-----|----------------|-------|----------|
| Voxy | `0.2.16-beta+1.21.11` | required on the client for LOD rendering | [Modrinth](https://modrinth.com/mod/voxy) (not on CurseForge) |
| Sodium | `0.8.7+mc1.21.11` | use exactly 0.8.7 — the Iris build below pins it | [Modrinth](https://modrinth.com/mod/sodium) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/sodium) |
| Fabric API | `0.141.6+1.21.11` | | [Modrinth](https://modrinth.com/mod/fabric-api) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) |

### Client (optional)

| Mod | Tested version | Notes | Download |
|-----|----------------|-------|----------|
| Iris | `1.10.7+mc1.21.11` | shader support | [Modrinth](https://modrinth.com/mod/iris) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/irisshaders) |
| Mod Menu | `17.0.1-beta.1` | config screen entry | [Modrinth](https://modrinth.com/mod/modmenu) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/modmenu) |
| Cloth Config | `21.11.153` | config UI | [Modrinth](https://modrinth.com/mod/cloth-config) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/cloth-config) |

### Voxy-compatible shader packs (optional, need Iris)

Shader packs must explicitly support Voxy's LODs:

- [BSL Shaders](https://modrinth.com/shader/bsl-shaders) (≥10.1p1) — [CurseForge](https://www.curseforge.com/minecraft/shaders/bsl-shaders)
- [Complementary Reimagined](https://modrinth.com/shader/complementary-reimagined) (≥r5.8) — [CurseForge](https://www.curseforge.com/minecraft/shaders/complementary-reimagined)
- [Solas Shader](https://modrinth.com/shader/solas-shader) — [CurseForge](https://www.curseforge.com/minecraft/shaders/solas-shader)
- [Photon](https://modrinth.com/shader/photon-shader) — use a [GitHub `main`](https://github.com/sixthsurge/photon) build: releases up to v1.3b lack the Nether (`world-1`) Voxy programs and crash shaders on Nether entry

## Building

This project requires Java 25.

```bash
# Clone the repo
git clone https://github.com/iSeeEthan/voxy_worldgen_v2.git

# Build
./gradlew build
```

Artifacts are output to `build/libs/`.

## Configuration

Config files are located in `config/voxyworldgenv2.json`.

## License

CUSTOM, refer to LICENSE file for more information.
