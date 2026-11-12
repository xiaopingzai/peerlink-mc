# Build Recovery & Release Notes

[English](BUILD.en.md) | [简体中文](BUILD.md)

## 1. Environment requirements

- JDK 8 (MC 1.12.2 / ForgeGradle 2.3 toolchain)
- Gradle is pulled by ForgeGradle 2.3-SNAPSHOT and depends on the Minecraft 1.12.2 MDK and MCP mappings
- Network access to the MinecraftForge Maven and SpongePowered Maven

> Note: the recovered sources have **not been through a full Gradle build verification in this repository's environment** (ForgeGradle / MCP mappings are not installed here). The steps below are guidance for restoring a compilable project; a first `gradlew build` passing on the first try is not guaranteed.

## 2. Known decompilation limitations

The sources were decompiled from the released jar with CFR 0.152. Before restoring a compilable project, the following need to be addressed:

1. **Unmapped SRG names**: Minecraft/Forge references in the source still use SRG names, e.g.
   - `Minecraft.func_71410_x()` (player instance)
   - `GuiScreen.field_146294_l` / `field_146295_m` (width / height)
   - `I18n.func_135052_a(...)` (translation)
   A stock MDK dev environment uses MCP names, so align them one by one against `stable_39` (or whichever mapping set you choose); alternatively, switch to a dev configuration that keeps SRG names.
2. **Incompletely decompiled methods**: CFR cannot fully structure some methods (known: `network/StunProbe#discoverMappedAddress`). When the compiler reports errors there, check the control flow first and manually reconstruct the logic from the bytecode if needed.
3. **Missing third-party dependencies**: the original jar bundled ViaForge / ViaLegacy / ViaRewind / SpongePowered Mixin / fastutil / gson / slf4j, etc. This repository does not contain that bytecode. When restoring a build, pull them as dependencies from their official repositories; the `dependencies {}` block in `build.gradle` leaves commented slots.
4. **Inlined inner classes**: CFR expands nested classes (`$1`, `$RoomState`, etc.) inside the outer `.java` file. Before compiling, confirm the nested / anonymous class structures are intact.

## 3. Recovery steps (reference)

1. Install JDK 8 and generate the wrapper with `gradle wrapper --gradle-version 4.9` (the Gradle version compatible with ForgeGradle 2.3).
2. Check that `minecraft.version` and `mappings` in `build.gradle` match the Forge version on your machine.
3. Add the third-party dependency coordinates per section 2, then align SRG → MCP method/field names.
4. Run `gradlew genIntellijRuns` (or `genEclipseRuns`), then open each failing class in the IDE and fix it.
5. Artifact check: `mcmod.info`, `assets/voxlink/lang/`, and `assets/voxlink/icon.png` live under `src/main/resources/`.

## 4. Branding / renaming notes

Reviewers require the project not to be confused with the original author or existing projects. Before public release, we recommend:

- Use **PeerLink** as the public project name, with repository URL slug `peerlink-mc`; do not make the title, description, or icon look like the official original VoxLink project.
- The `name="VoxLink CV"` in the `VoxLinkMod` annotation and the `name` / `authors` in `mcmod.info` are in-code display names. To fully distinguish, you can change them in your own released build (changing the modid also changes the resource namespace and language paths, which must be adjusted together).
- `assets/voxlink/icon.png` comes from the original jar and is an original brand asset; **replace it with your own icon before release** to avoid brand confusion.
- The description may reference the original work, but must state clearly that this is an unofficial reconstruction/derivative and does not imply association with or endorsement by the original author; preserve the MIT copyright attribution (see `LICENSE` / `NOTICE.md`).

## 5. Modrinth release self-check list

- [ ] The source-code link points to the repository you own (no longer the original author's repo)
- [ ] Project name / slug / icon have been changed to the new brand, with no confusion
- [ ] The description states this is an unofficial derivative with no association to the original author
- [ ] `LICENSE` preserves the original-work MIT attribution notice
- [ ] Third-party protocol libraries are not committed as this project's source; their identities are listed in `NOTICE.md`
- [ ] Version number, `mcmod.info`, and language namespace match the actual modid

## 6. Pushing this repository to GitHub

```bash
git init
git add .
git commit -m "PeerLink 1.12.2-1: recovered source + fixes"
git branch -M main
git remote add origin https://github.com/<your-username>/peerlink-mc.git
git push -u origin main
```

After pushing, fill this repository's address in the Modrinth project page under "Related links → Source code"; in "Edit name", change the display name and slug to PeerLink / `peerlink-mc`.
