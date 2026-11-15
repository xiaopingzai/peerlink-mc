# Source Notes — Minecraft 1.8.9 Forge (VoxLink 1.1.8, decompiled)

## What this is

This directory contains the **decompiled-recovered** Java source for the upstream
released Forge build `voxlink-1.8.9-forge-1.1.8.jar`, reconstructed with
[CFR 0.152](https://github.com/leibnitz27/cfr). It is **not** source published by
the original author; upstream never released 1.8.9 / 1.11.2 / 1.12.2 Forge source.

- Upstream project: <https://github.com/AUGUHDAR/VoxLink>
- Original license: **LGPL-3.0**
- Package / modid are preserved as upstream: `icu.wuhui.voxlink`, modid `voxlink`.

## Build status — does NOT compile out of the box

The decompiled tree carries the usual CFR artifacts and is **not a buildable Gradle
project**:

- Cross-scope reused local slots (decompiler reuses one variable slot for several
  distinct locals across nested lambdas).
- Raw generics and `Object` receivers that need casts to gson / collection /
  `UdpHolePuncher` / `NatClass` / `PublicMappedAddress` types.
- SRG / MCP names and Forge 1.8.9 mappings that are not wired here.

Third-party libraries are **not** bundled in this tree; pull them from their official
repositories when restoring a build: ViaVersion / ViaLegacy / ViaRewind, Mixin,
fastutil, gson, slf4j/log4j.

## Resources are the original 1.1.8 assets

`src/main/resources/` (`mcmod.info`, `assets/voxlink/lang/*.lang`, `icon.png`) is
copied verbatim from the 1.1.8 jar. It therefore still carries the original branding
and defaults and has **not** been repatched:

- Display name / credits: `VoxLink`
- Signaling domain: `https://p2p.wuhui.icu`
- Mod version: `1.1.8`

The repatched PeerLink 1.1.9 1.8.9 jar in the `legacy-1.1.9` Release is a
**binary patch** of this 1.1.8 bytecode; it was not built from this source tree.

## Attribution

Derived from upstream VoxLink by AUGUHDAR, LGPL-3.0. This repository is an
unofficial fork with no affiliation, endorsement, or warranty from the original author.
