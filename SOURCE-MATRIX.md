# Source Matrix — PeerLink 1.1.9

This source archive groups the source sets for every published Minecraft version.
All Forge legacy sets are **decompiled-recovered** reference source; only the 26.3
Fabric set is a clean Gradle project that builds out of the box.

| Directory | MC version | Loader | Source nature | Build status |
|---|---|---|---|---|
| `src/main/` | 1.12.2 | Forge 14.23.5 | CFR decompile of a released 1.12.2 build, partially hand-repaired | Does **not** compile yet; remaining javac errors are in `room/ConnectionManager.java` |
| `legacy-sources/1.8.9/` | 1.8.9 | Forge | CFR 0.152 decompile of upstream `voxlink-1.8.9-forge-1.1.8.jar` | Reference archive only; does not compile out of the box |
| `legacy-sources/1.11.2/` | 1.11.2 | Forge | CFR 0.152 decompile of upstream `voxlink-1.11.2-forge-1.1.8.jar` | Reference archive only; does not compile out of the box |
| `voxlink-26.3/` | 26.3 | Fabric | Clean upstream Gradle source synced for the 26.3 Fabric build | Builds with Fabric Loom; matching `-sources.jar` and runnable jar are attached to the `1.1.9` Release |

## Legacy source disclaimers

- The 1.8.9 / 1.11.2 / 1.12.2 Forge jars were never released as source by upstream
  AUGUHDAR/VoxLink. The trees here were reconstructed from released compiled jars and
  carry decompiler artifacts (cross-scope reused local slots, raw generics, `Object`
  receivers needing casts, SRG/MCP names).
- `legacy-sources/<version>/src/main/resources/` (`mcmod.info`, language packs, icon)
  is copied verbatim from the 1.1.8 jar and still shows the original `VoxLink` branding
  and the old `https://p2p.wuhui.icu` signaling domain; these were **not** repatched.
- Third-party libraries (ViaVersion / ViaLegacy / ViaRewind, Mixin, fastutil, gson,
  slf4j/log4j) are not bundled in any legacy tree; pull them from their official
  repositories when restoring a buildable project.
- The repatched PeerLink 1.1.9 legacy jars in the `legacy-1.1.9` Release are
  **binary patches** of the 1.1.8 bytecode; they were not built from this source.

## Attribution

Derived from upstream VoxLink by AUGUHDAR, **LGPL-3.0**. This repository is an
unofficial fork with no affiliation, endorsement, or warranty from the original author.
