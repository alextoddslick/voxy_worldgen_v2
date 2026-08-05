# Voxy World Gen V2

![Logo](src/main/resources/logo.png)

This is a rewrite of my old Voxy World Gen mod, this mod is NOT a fork of the passive chunk generator mod and instead is a entirely different mod.

## About this branch (`backport/1.21.1`)

This branch is a **backport of Voxy World Gen V2 to Minecraft 1.21.1**, maintained on a fork of
[the original repository by iSeeEthan](https://github.com/iSeeEthan/voxy_worldgen_v2) (upstream
targets MC 1.21.6–1.21.11). Ports to further Minecraft versions are planned and will land as
additional `backport/*` branches on this fork.

It is built to pair with the **community 1.21.1 backport of Voxy** itself:
[m3t4f1v3/voxy, branch `mc_1211-sodium0.8.12`](https://github.com/m3t4f1v3/voxy/tree/mc_1211-sodium0.8.12)
— not the original upstream Voxy, but a backport maintained by community contributors. You need
that Voxy build (or a compatible one) on the client for LODs to render.

Beyond the version downgrade, this branch adds server-operations features on top of upstream:
`/voxygen` admin commands (status, traffic, rate limiting, live reload), an off-thread LOD send
queue with backpressure and per-player bandwidth caps, whole-batch zlib compression of LOD
payloads, periodic generation progress logging, a virtual-book settings UI, and a tab-list HUD.

## Features

- Generates chunks very fast in the background and auto-ingest them with voxy.
- Configurable generation speed and queue size.
- Tellus integration. https://github.com/Yucareux/Tellus
- Server-side support

## Dependencies

- **Minecraft**: 1.21.6 - 1.21.11 (Tested on 1.21.11, anything less is considered unstable and may not work)
- **Fabric Loader**: >= 0.16.0
- **Java**: 21 (Required)
- **Fabric API**
- **Cloth Config**: >= 15.0.127

## Building

This project requires Java 21.

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
