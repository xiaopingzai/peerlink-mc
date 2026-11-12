# PeerLink

[English](README.en.md) | [简体中文](README.md)

Minecraft Java 版 **1.12.2** Forge 客户端模组，基于房间代码的 P2P 多人联机：快速让朋友上线，或在大厅找玩伴；通过打洞/中继直连，无需端口转发、无需专用服务器，也无需第三方 VPN。

> 仓库 slug：`peerlink-mc`
> 兼容：Minecraft Java 1.12.2（Forge 客户端，`clientSideOnly`）
> 状态：反编译重建的源码存档 + 修复记录（见下方「现状与限制」）

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
├── docs/
│   ├── BUILD.md       # 构建恢复说明、反编译局限、已知遗留
│   └── FIXES.md       # 本版本相对原始构建修复的问题清单
├── LICENSE            # MIT（保留原作品归属）
└── NOTICE.md          # 归属声明与第三方组件说明
```

## 归属与合规

- 本项目衍生自原作者的 VoxLink 作品（上游：<https://github.com/AUGUHDAR/VoxLink>，MIT）。
- 本仓库为**非官方重建/分支**，与原作者、现有同名项目之间不存在关联、代言或背书关系。
- 沿用 MIT 许可证，保留原作者版权声明；详见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

## 文档

- [docs/FIXES.md](docs/FIXES.md) — 崩溃、字段引用、语言命名空间等修复记录
- [docs/BUILD.md](docs/BUILD.md) — 如何尝试恢复可编译工程、已知遗留与上线自检清单

## 从本仓库推送（自建 GitHub）

```bash
git init
git add .
git commit -m "PeerLink 1.12.2-1: recovered source + fixes"
git branch -M main
git remote add origin https://github.com/<你的用户名>/peerlink-mc.git
git push -u origin main
```
