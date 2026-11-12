# Fix Records (1.12.2 final build)

[English](FIXES.en.md) | [简体中文](FIXES.md)

The source in this repository corresponds to the released 1.12.2 Forge client build `voxlink-cv-1.1.8-1-1.12.2-final`.
Check method: CFR 0.152 decompilation + javap bytecode verification + ASM reference consistency check + real JVM (`-Xverify:all`) load validation.

## Fixed issues

### 1. Main screen button crash (P0, `ui/VoxLinkScreen`)

The original final build was a mixed artifact of "new classes + an old-compiled Screen", whose bytecode referenced non-existent fields:

- `VoxLinkMod.logger` — the actual field name is `LOGGER`;
- `VoxLinkClient.config` — this class has no such field; the correct path is `VoxLinkMod.getConfig()`.

Clicking main-screen buttons 11–14 (Download Terracotta / Simple Config / Upload Logs / P2P Relay) threw `NoSuchFieldError`.

After the fix:

- Buttons 11/12 now use `VoxLinkMod.LOGGER`;
- Buttons 13/14 now use `VoxLinkMod.getConfig()`, eliminating the `NoSuchFieldError`;
- Button 13 (Upload Logs) now toggles the log-upload switch via `LogUploadState.setLogUploadEnabled(...)` and logs feedback (the original implementation was wrongly wired to the update-check toggle, inconsistent with the label and the `LogUploadState` mechanism).

### 2. Logger name / version-query namespace mismatch (P2, `VoxLinkMod`)

The modid annotation and `mcmod.info` were both `voxlink`, but the logger name and version query still used `voxlinkcv`, so the version query always returned null and `getModVersionString()` always fell back to the default value.

After the fix: the logger name and version-query constants are unified to `"voxlink"`, aligned with the modid; `VERSION` / `MOD_VERSION` are `1.1.8-1`.

## Verification evidence (shipped jar)

- Real JVM `-Xverify:all`: all mod classes loaded together with stub external classes, 0 failures;
- ASM walk over inner-class references resolved along the inheritance chain: residual `VoxLinkMod.logger` / `VoxLinkClient.config` references = 0 (the original final build had 8);
- javap spot check of the Screen button handler, `VoxLinkMod` constants, and `LogUploadManager` method bytecode matched expectations; the `lambda$notifyUploadedInChat$2` exception table coverage is correct;
- Shipped jar passed `unzip -t` integrity check.

## Known leftovers (not changed this round)

| # | Location | Current state | Impact / suggestion |
|---|----------|---------------|----------------------|
| 1 | `network/LogUploadManager.notifyUploadedInChat` | The chat box prints the raw language key `voxlink.log_upload.uploaded` directly, without an I18n wrapper | The class keeps its original valid bytecode; to switch to `I18n.func_135052_a(...)`, stack frames must be recomputed with `ClassWriter.COMPUTE_FRAMES` — do not hand-write the exception-handler frame types |
| 2 | `VoxLinkConstants.MOD_VERSION` | Still `"1.1.6"`, drifting from `VoxLinkMod`'s `1.1.8-1` | Display/reporting only, no functional impact; recommend a single source of truth for the version |
| 3 | `thenAccept` chain in `LogUploadManager` | Future exceptions are not observed and are silently swallowed | Recommend adding an `exceptionally` log |
| 4 | Default signaling/hole-punching server endpoints | The upstream default STUN/signaling/relay addresses are kept in code | Replace them with your own server addresses when self-hosting |

## Historical issues out of scope for this repository

- The language-namespace bug in the old CV 1.1.7 / 1.1.8 / 1.1.8-1-new builds (modid `voxlinkcv` but lang files placed under `assets/voxlink/lang/`) was fixed separately in the corresponding langfix jars. The final build this repo corresponds to has modid `voxlink`, so its resource namespace already matches.
- The 1.20.1 Forge build is a separate codebase and is not included here.
- The ViaForge join-server NPE, OptiFine / Twilight Forest texture warnings, and Patchouli Chinese loading failure are unrelated to this mod's logic and were not changed.
