# PeerLink

[English](README.en.md) | [简体中文](README.md)

PeerLink is a lightweight P2P multiplayer mod that lets you and your friends join each other's worlds using short room codes — no port forwarding, no dedicated server, and no third-party VPN.

> Repository slug: `peerlink-mc`
> This repository holds two source sets: `src/` at the root is the **1.12.2 Forge** decompiled archive; `voxlink-26.3/` is the upstream-aligned **Minecraft 26.3 Fabric** (`1.2.2-26.3`) clean source.
> Status: the 1.12.2 tree is a decompiled reconstructed archive + fix records; the 26.3 tree is an upstream Gradle project source sync (see "26.3 Fabric sources" below).

## Features

- **Room-code matchmaking**: host a world and get a short 6-character code (e.g. `CUBN6Z`, charset `A-HJ-NP-Z2-9`); share it with friends, who paste it in PeerLink to connect.
- **NAT traversal**: automatic UDP hole punching with STUN. When direct P2P isn't possible (symmetric NAT / CGNAT), it falls back to relay.
- **Room lobby**: browse public rooms by category — Survival, Creative, Redstone, PvP, RPG, Custom — with player count and NAT type at a glance.
- **LAN detection**: if you and the host are on the same local network, the connection goes direct over LAN.
- **Mod sync**: the host publishes its mod list; guests receive a diff with real names, icons and direct downloads before joining.
- **Controls**: press **V** in-game to open the PeerLink menu; buttons are also available on the title screen and pause menu.
- **Multilingual**: 13 language packs, the same set as the upstream mod.

## How to use

1. Install the mod and launch Minecraft.
2. The host opens their world to LAN, or uses the **Create Room** button.
3. The host copies the room code from the PeerLink menu.
4. Friends press **V**, select **Join Room**, paste the code, and connect.

## Signaling server

- Default signaling server: `https://link.voxdev.top`.
- Legacy `wuhui.icu` addresses are rewritten to the current domain automatically.

## Status & limitations (read first)

The Java sources in this repository were recovered by decompiling a released 1.12.2 build with CFR 0.152 and then applying the verified fixes. This is **not a clean project written from scratch**:

- The sources still carry the 1.12.2 **SRG obfuscated names** (e.g. `func_71410_x()`, `field_146294_l`, `func_135052_a`) and a few CFR decompilation artifacts.
- It **may not compile out of the box** against a fresh ForgeGradle MDK: MCP mapping names usually need to be aligned first, and a handful of incompletely decompiled methods need manual cleanup. Known issues are listed in [docs/BUILD.en.md](docs/BUILD.en.md).
- The repository is a **publicly auditable source archive and fix record**, used as the source-code link for the Modrinth project. Runnable mod jars are published on the Modrinth version page.
- The third-party protocol-library bytecode bundled in the original jar is **not** included here (see [NOTICE.md](NOTICE.md)).

## Layout

```
peerlink-mc/
├── build.gradle / settings.gradle / gradle.properties   # ForgeGradle 2.3 scaffold (reference)
├── src/main/java/icu/wuhui/voxlink/                     # Mod's own logic (55 classes)
│   ├── config/        # Configuration, log-upload toggle
│   ├── network/       # Signaling, STUN, UDP hole punching, P2P/relay bridge
│   ├── room/          # Room state and connection management
│   ├── ui/            # Screens and chat compatibility layer
│   ├── uuid/          # Nickname / UUID policy
│   └── hook/          # Bootstrap and ViaForge hook
├── src/main/resources/
│   ├── mcmod.info
│   └── assets/voxlink/lang/        # en_US / zh_CN lang files, icon
├── voxlink-26.3/                                       # Upstream Fabric 26.3 source sync (1.2.2-26.3)
│   ├── LICENSE                                         # LGPL-3.0 full text (from upstream)
│   ├── core/src/main/java/icu/wuhui/voxlink/           # Shared engine, platform abstraction, Terracotta, relay
│   ├── core/src/main/resources/assets/voxlink/        # 13 lang files, icon
│   └── fabric/26.3/                                    # Fabric 26.3 module: build.gradle, entrypoints, mixins, screens
├── docs/
│   ├── BUILD.md       # Build recovery notes, decompile limitations, known leftovers
│   └── FIXES.md       # Fix list for this version vs the original build
├── LICENSE            # LGPL-3.0 (original-work attribution preserved)
└── NOTICE.md          # Attribution and third-party component notice
```

## 26.3 Fabric sources

`voxlink-26.3/` follows the upstream multi-module layout and corresponds to the released build `voxlink-1.2.2-26.3-fabric`:

- `core/`: cross-loader shared engine, platform abstraction, Terracotta and relay code, plus Android stubs (`core/src/stubs`, compile-only, not packaged into the jar).
- `fabric/26.3/`: the Fabric 26.3 module; its `build.gradle` references `../../core` via relative paths. Version and version ranges in `fabric.mod.json` are expanded from Gradle properties at build time.
- Verified against the uploaded jar: 127 own outer classes match one-to-one; the 13 lang files and icon match by md5; `voxlink.mixins.json` matches in content (indentation only differs). `e4mc/**` is excluded from packaging by the upstream `build.gradle` and is absent from the jar.
- This module is an upstream clean Java project; building it requires Java 25, Fabric Loom and Fabric API dependencies. No full Gradle build verification was performed in this environment.

## Attribution & compliance

- Original VoxLink by **AUGUHDAR** — <https://github.com/AUGUHDAR/VoxLink>.
- This project is derived from the original VoxLink work and is distributed under the upstream **GNU Lesser General Public License v3.0 (LGPL-3.0)**, preserving the copyright notice and source. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
- This repository is an **unofficial reconstructed / forked build**. There is no association, endorsement, or affiliation with the original author or any existing project of a similar name.

## Documentation

- [docs/FIXES.en.md](docs/FIXES.en.md) — Fix records for crashes, field references, language namespaces, etc.
- [docs/BUILD.en.md](docs/BUILD.en.md) — How to restore a compilable project, known leftovers, and the release self-check list.

## Links

- Source repository: <https://github.com/xiaopingzai/peerlink-mc>
- Wiki: <https://github.com/xiaopingzai/peerlink-mc/wiki>
- Modrinth profile: <https://modrinth.com/user/xiaopingzai>
