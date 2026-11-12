# PeerLink

[English](README.en.md) | [简体中文](README.md)

A Forge client mod for **Minecraft Java Edition 1.12.2**, providing room-code based P2P multiplayer: get friends online quickly or find players in the lobby. It connects directly via hole punching / relay — no port forwarding, no dedicated server, and no third-party VPN.

> Repository slug: `peerlink-mc`
> Compatibility: Minecraft Java 1.12.2 (Forge client, `clientSideOnly`)
> Status: decompiled source archive + fix records (see "Status & limitations" below)

## Status & limitations (read first)

The Java sources in this repository were recovered by decompiling a released 1.12.2 build with CFR 0.152 and then applying the verified fixes. This is **not a clean project written from scratch**:

- The sources still carry the 1.12.2 **SRG obfuscated names** (e.g. `func_71410_x()`, `field_146294_l`, `func_135052_a`) and a few CFR decompilation artifacts.
- It **may not compile out of the box** against a fresh ForgeGradle MDK: MCP mapping names usually need to be aligned first, and a handful of incompletely decompiled methods need manual cleanup. Known issues are listed in [docs/BUILD.md](docs/BUILD.md).
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
├── docs/
│   ├── BUILD.md       # Build recovery notes, decompile limitations, known leftovers
│   └── FIXES.md       # Fix list for this version vs the original build
├── LICENSE            # MIT (original-work attribution preserved)
└── NOTICE.md          # Attribution and third-party component notice
```

## Attribution & compliance

- This project is derived from the original VoxLink work (upstream: <https://github.com/AUGUHDAR/VoxLink>, MIT).
- This repository is an **unofficial reconstructed / forked build**. There is no association, endorsement, or affiliation with the original author or any existing project of a similar name.
- It is released under the MIT license, preserving the original copyright notice. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

## Documentation

- [docs/FIXES.md](docs/FIXES.md) — Fix records for crashes, field references, language namespaces, etc.
- [docs/BUILD.md](docs/BUILD.md) — How to restore a compilable project, known leftovers, and the release self-check list.

## Pushing this repository to GitHub

```bash
git init
git add .
git commit -m "PeerLink 1.12.2-1: recovered source + fixes"
git branch -M main
git remote add origin https://github.com/<your-username>/peerlink-mc.git
git push -u origin main
```
