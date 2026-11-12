# PeerLink

[English](README.en.md) | [简体中文](README.md)

基于房间代码的 P2P 多人联机模组：快速让朋友上线，或在大厅找玩伴；通过打洞/中继直连，无需端口转发、无需专用服务器，也无需第三方 VPN。

> 仓库 slug：`peerlink-mc`
> 本仓库含两套源码：根目录 `src/` 为 **1.12.2 Forge** 反编译存档；`voxlink-26.3/` 为对齐上游的 **Minecraft 26.3 Fabric**（`1.2.2-26.3`）干净源码。
> 状态：1.12.2 为反编译重建的源码存档 + 修复记录；26.3 为上游 Gradle 工程源码同步（见下方「26.3 Fabric 源码」）。

## 现状与限制（请先阅读）

本仓库的 Java 源码是从一份已发布的 1.12.2 构建用 CFR 0.152 反编译恢复、再补入已验证修复得到的，**不是从零编写的干净工程**：

- 源码仍保留 1.12.2 的 **SRG 混淆名**（如 `func_71410_x()`、`field_146294_l`、`func_135052_a`），并带有少量 CFR 反编译痕迹。
- 直接套用全新 ForgeGradle MDK **不一定能开箱编译**：通常需要先对齐 MCP 映射名、人工整理个别反编译不完整的方法。具体已知问题见 [docs/BUILD.md](docs/BUILD.md)。
- 仓库定位是**可公开审计的源码存档与修复记录**，供 Modrinth 项目填写源代码链接使用；可运行的模组 jar 在 Modrinth 版本页发布。
- 仓库不包含原始 jar 中捆绑的第三方协议库字节码（见 [NOTICE.md](NOTICE.md)）。

## 目录结构

```
peerlink-mc/
├── build.gradle / settings.gradle / gradle.properties   # ForgeGradle 2.3 脚手架（参考）
├── src/main/java/icu/wuhui/voxlink/                     # 模组自有逻辑（55 个类）
│   ├── config/        # 配置、日志上传开关
│   ├── network/       # 信令、STUN、UDP 打洞、P2P/中继桥
│   ├── room/          # 房间状态与连接管理
│   ├── ui/            # 界面与聊天兼容层
│   ├── uuid/          # 昵称/UUID 策略
│   └── hook/          # 引导与 ViaForge 挂钩
├── src/main/resources/
│   ├── mcmod.info
│   └── assets/voxlink/lang/        # en_US / zh_CN 语言文件、图标
├── voxlink-26.3/                                       # 上游 Fabric 26.3 源码同步（1.2.2-26.3）
│   ├── LICENSE                                         # LGPL-3.0 全文（取自上游仓库）
│   ├── core/src/main/java/icu/wuhui/voxlink/           # 共享引擎、平台抽象、陶瓦、中继等
│   ├── core/src/main/resources/assets/voxlink/        # 13 种语言文件、图标
│   └── fabric/26.3/                                    # Fabric 26.3 模块：build.gradle、入口、mixin、界面
├── docs/
│   ├── BUILD.md       # 构建恢复说明、反编译局限、已知遗留
│   └── FIXES.md       # 本版本相对原始构建修复的问题清单
├── LICENSE            # LGPL-3.0（保留原作品归属）
└── NOTICE.md          # 归属声明与第三方组件说明
```

## 26.3 Fabric 源码

`voxlink-26.3/` 按上游仓库的多模块布局同步，对应已发布构建 `voxlink-1.2.2-26.3-fabric`：

- `core/`：跨加载器共享的引擎、平台抽象、陶瓦与中继代码，以及 Android 桩（`core/src/stubs`，仅编译用、不打包进 jar）。
- `fabric/26.3/`：Fabric 26.3 模块，`build.gradle` 通过相对路径引用 `../../core`；`fabric.mod.json` 中的版本/区间由 Gradle 属性在构建期替换。
- 与上传 jar 已核对：自有外层类 127 个一一对应，13 个语言文件与图标 md5 一致，`voxlink.mixins.json` 内容一致（仅缩进差异）；`e4mc/**` 按上游 `build.gradle` 配置在打包时排除，jar 中不含这些类。
- 该模块为上游干净 Java 工程，构建需要 Java 25、Fabric Loom 与 Fabric API 依赖；本环境未做完整 Gradle 编译验证。

## 归属与合规

- 本项目衍生自原作者的 VoxLink 作品（上游：<https://github.com/AUGUHDAR/VoxLink>，**LGPL-3.0**）。
- 本仓库为**非官方重建/分支**，与原作者、现有同名项目之间不存在关联、代言或背书关系。
- 按上游 **LGPL-3.0** 许可证保留版权声明与源码；详见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

## 文档

- [docs/FIXES.md](docs/FIXES.md) — 崩溃、字段引用、语言命名空间等修复记录
- [docs/BUILD.md](docs/BUILD.md) — 如何尝试恢复可编译工程、已知遗留与上线自检清单

## 相关链接

- 源码仓库：<https://github.com/xiaopingzai/peerlink-mc>
- Wiki：<https://github.com/xiaopingzai/peerlink-mc/wiki>
- Modrinth 主页：<https://modrinth.com/user/xiaopingzai>
