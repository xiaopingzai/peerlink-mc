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

## Source inventory (2026-11-17 复核)

- 54 个 `.java`（`config/ network/ room/ ui/ uuid/` 与根包），资源 4 个：`mcmod.info`、`icon.png`、`zh_CN.lang`、`en_US.lang`。
- 未搭建 ForgeGradle 工程：目录内无 `build.gradle`/`settings.gradle`/`gradle.properties`。
- 源码仍是 CFR 0.152 原始反编译面貌：SRG 运行名未回写（`func_71410_x`、`func_110432_I`、`func_111285_a` 等），缺类型强转与重复局部变量清理。
- 本目录**未**回灌 1.12.2/1.11.2 新增的反馈表单、工单客户端/列表/详情、配置屏、UiLogBus；上游 1.1.8 反编译树本就不含这些类。
- 旧品牌/域名/版本残留（`VoxLink`、`https://p2p.wuhui.icu`、`1.1.8`）按口径不在源码侧硬改：工具链恢复前改了也无法编译验证；现网 repatched 1.1.9 1.8.9 jar 走的是二进制补丁路线。

## Toolchain blocker (2026-11-17 复核)

1.8.9 工程仍**无法搭建/编译**，阻塞点（本轮逐项复核，证据如下）：

- 本地 Gradle 缓存 `~/.gradle/caches/modules-2/files-2.1/net.minecraftforge/forge/` 仅有
  `1.11.2-13.20.0.2308`、`1.12.2-14.23.5.2847`；无 1.8.9 Forge userdev/binpatched jar，也无 1.8.9 MCP mappings。
- 本机 `/home/user/tools/` 仅装 `gradle-4.9`，无 Gradle 2.x。1.8.9 对应 ForgeGradle 为 FG1.2
  （Gradle 2.0–2.14 时代），FG2.2/2.3 不覆盖 1.8.9。
- 公网探测：`maven.minecraftforge.net` 与 `files.minecraftforge.net/maven` 对 1.8.9 候选坐标
  （`1.8.9-11.15.1.2318` / `11.15.1.2316` / `11.15.0.1655` 的 userdev/universal）直链返回
  `000`（连接超时）或 `404`，`maven-metadata.xml` 取不到；本网络无法拉取 1.8.9 userdev 与映射。
- 恢复条件：取得可用的 1.8.9 Forge userdev 坐标 + 兼容 Gradle 2.x/FG1.2 工具链 + 1.8.9 MCP 映射后，
  再按 1.11.2 同款流程（SRG→MCP 回写、类型强转、重复局部变量清理）修复源码。在此之前不臆造编译通过。

## Attribution

Derived from upstream VoxLink by AUGUHDAR, LGPL-3.0. This repository is an
unofficial fork with no affiliation, endorsement, or warranty from the original author.
