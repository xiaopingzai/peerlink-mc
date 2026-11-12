# 修复记录（1.12.2 最终版）

本仓库源码对应已发布的 1.12.2 Forge 客户端构建 `voxlink-cv-1.1.8-1-1.12.2-final`。
检查方式：CFR 0.152 反编译 + javap 字节码核验 + ASM 引用一致性检查 + 真实 JVM（`-Xverify:all`）加载验证。

## 已修复的问题

### 1. 主界面按钮崩溃（P0，`ui/VoxLinkScreen`）

原始最终版是「新类 + 旧版编译的 Screen」混装产物，字节码引用了不存在的字段：

- `VoxLinkMod.logger` —— 实际字段名是 `LOGGER`；
- `VoxLinkClient.config` —— 该类没有这个字段，应走 `VoxLinkMod.getConfig()`。

点击主界面 11~14 号按钮（Download Terracotta / Simple Config / Upload Logs / P2P Relay）时会抛 `NoSuchFieldError`。

修复后：

- 按钮 11/12 改用 `VoxLinkMod.LOGGER`；
- 按钮 13/14 改用 `VoxLinkMod.getConfig()`，消除 `NoSuchFieldError`；
- 按钮 13（Upload Logs）改为切换日志上传开关 `LogUploadState.setLogUploadEnabled(...)`，并输出日志反馈（原实现错接到更新检查开关，与文案和 `LogUploadState` 机制不符）。

### 2. 日志名 / 版本查询命名空间不一致（P2，`VoxLinkMod`）

modid 注解与 `mcmod.info` 均为 `voxlink`，但日志名与版本查询仍用 `voxlinkcv`，导致版本查询恒为 null、`getModVersionString()` 永远返回兜底值。

修复后：日志名与版本查询常量统一为 `"voxlink"`，与 modid 对齐；`VERSION` / `MOD_VERSION` 为 `1.1.8-1`。

### 3. 服务器域名迁移（P1，`VoxLinkConfig` / `VoxLinkScreen`）

自 2026 年 11 月 11 日起旧域名 `p2p.wuhui.icu` 停用（低于 1.2.1 的旧客户端需改配置或升级才能连接）。对齐上游 1.20.1 配置逻辑：

- 默认 `serverUrl` 由 `https://p2p.wuhui.icu` 改为 `https://link.voxdev.top`（常量与字段默认值同步）；
- `validate()` 增加旧域名迁移：落盘配置里的 `serverUrl` 只要包含 `wuhui.icu`，加载时自动改写为新默认并打 warn 日志；
- 主界面陶瓦下载日志链接由旧域名 `/terracotta` 改为新官网根地址。

> 注：本仓库为反编译恢复的源码存档，本次改动是源码级调整，未在本环境重新编译打包；现存已发布 jar 仍需按公告在 `voxlink.json` 中手动改 `serverUrl`，或用重新构建的产物替换。

## 验证证据（对应已交付 jar）

- 真实 JVM `-Xverify:all`：模组类连同桩外部类全部加载通过，0 失败；
- ASM 遍历内部类引用并沿继承链解析：`VoxLinkMod.logger` / `VoxLinkClient.config` 残留引用为 0（原最终版为 8 处）；
- javap 抽查 Screen 按钮处理、`VoxLinkMod` 常量、`LogUploadManager` 方法字节码符合预期；`lambda$notifyUploadedInChat$2` 异常表覆盖正确；
- 交付 jar `unzip -t` 完整性通过。

## 已知遗留（本次未改动）

| # | 位置 | 现状 | 影响 / 建议 |
|---|------|------|------------|
| 1 | `network/LogUploadManager.notifyUploadedInChat` | 聊天框直接输出原始语言键 `voxlink.log_upload.uploaded`，未做 I18n 包装 | 该类保留原始有效字节码；如需改为 `I18n.func_135052_a(...)`，必须用 `ClassWriter.COMPUTE_FRAMES` 重算栈帧，不能手写异常处理器帧类型 |
| 2 | `VoxLinkConstants.MOD_VERSION` | 仍为 `"1.1.6"`，与 `VoxLinkMod` 的 `1.1.8-1` 漂移 | 仅展示/上报用，无功能影响；建议统一为单一版本来源 |
| 3 | `LogUploadManager` 的 `thenAccept` 链 | 未观察 future 异常，异常被静默吞掉 | 建议补 `exceptionally` 日志 |
| 4 | 信令/打洞默认服务器端点 | 默认 `serverUrl` 已迁移至 `https://link.voxdev.top`；STUN/中继地址由信令下发 | 自托管时需按自己的服务端地址替换 |

## 不属于本仓库范围的历史问题

- 旧 CV 1.1.7 / 1.1.8 / 1.1.8-1-new 构建的语言命名空间错误（modid `voxlinkcv` 却把语言文件放在 `assets/voxlink/lang/`），已在对应 langfix jar 中单独修复；本仓库源码对应的最终版 modid 为 `voxlink`，资源命名空间本就匹配。
- 1.20.1 Forge 版是另一套独立代码，未纳入本仓库。
- ViaForge 进服 NPE、OptiFine/暮色森林贴图告警、Patchouli 中文加载失败等与本模组逻辑无关，未改动。
