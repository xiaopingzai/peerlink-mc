# Attribution & Third-Party Notice

## Original work

This project is derived from the original **VoxLink** Minecraft mod.

- Upstream project: <https://github.com/AUGUHDAR/VoxLink>
- Original license: **LGPL-3.0** (upstream `LICENSE`; the 26.3 Fabric jar's `fabric.mod.json` also declares `LGPL-3.0`).
- This repository preserves the upstream copyright notice and provides the corresponding source, as required by LGPL-3.0.

The repository contains two source sets:

- `src/main/java/` — recovered by decompiling a released 1.12.2 Forge build (see "Recovered-source disclaimer" below).
- `voxlink-26.3/` — clean upstream Gradle source synced for the Minecraft 26.3 Fabric build `1.2.2-26.3`; its own `LICENSE` is the upstream LGPL-3.0 full text.

This repository is an **unofficial reconstructed / forked build**. It is not
maintained by, affiliated with, endorsed by, or associated with the original
author. When describing this project on Modrinth or elsewhere, references to
the original work must not imply official association or approval.

## Third-party components (NOT bundled in this source tree)

The original released jar bundled the following libraries; their bytecode is
excluded from this repository and each component retains its own upstream
license. When restoring a buildable project, pull them from their official
repositories instead of copying them from the jar:

| Component | Upstream | License (check upstream for current terms) |
|---|---|---|
| ViaVersion / ViaLegacy / ViaRewind | RaphiMC / ViaVersion | GPL-3.0 / upstream terms |
| ViaForge | ViaVersion team | upstream terms |
| SpongePowered Mixin | SpongePowered | MIT |
| fastutil | FastUtil | Apache-2.0 |
| Gson | Google | Apache-2.0 |
| SLF4J | QOS.ch | MIT-style BSD |

## Recovered-source disclaimer

The Java sources in `src/main/java/` were recovered by decompiling a released
1.12.2 Forge build with CFR 0.152 and then applying the fixes listed in
`docs/FIXES.md`. Some methods could not be fully reconstructed by the
decompiler; such spots are marked in the code and are recorded in
`docs/BUILD.md`. Treat the tree as a source archive and repair record, not as
a clean from-scratch build.
