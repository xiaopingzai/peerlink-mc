# Build Status — 1.11.2 Forge 工程（PeerLink 移植）

更新时间：2026-11-16。本环境无法运行 MC 客户端，以下仅为**编译级**结论，界面行为未实测。

## 工程骨架（本轮新建）

- `build.gradle`：ForgeGradle **2.2-SNAPSHOT**（FG 2.3 仅支持 MC ≥1.12，已实测拒绝 1.11.2）。
- `gradle.properties`：`mod_version=1.1.9`、`minecraft_version=1.11.2`、`forge_version=13.20.0.2308`、`mappings_version=snapshot_20170612`。
- `settings.gradle`：`rootProject.name=peerlink-mc-1.11.2`。
- 源码沿用既有 `src/main/java`（54 个反编译 .java）与 `src/main/resources`（mcmod.info / lang / icon.png）。

## 环境解析结果（已落地本地缓存）

- Forge userdev：`1.11.2-13.20.0.2308` 已下载并反编译入缓存。
- MCP mappings：`mcp_snapshot:20170612` 的 `1.11.2` srgs 已缓存。
- 工具链：JDK8 `/home/user/tools/jdk8u504-b01`，Gradle 4.9，FG 2.2 在该组合下可完成 setup（首次 16 个任务执行成功，进入 compileJava）。

编译命令（在本目录执行）：

```
JAVA_HOME=/home/user/tools/jdk8u504-b01 /home/user/tools/gradle-4.9/bin/gradle compileJava --console=plain
```

## 当前编译结果：BUILD SUCCESSFUL，compileJava exit 0（2026-11-17 复跑，0 error，107 个 .class；记录 `/tmp/legacy_1112i.txt`）

### 修复过程（2026-11-17）
1. **SRG→MCP 全量回写**：按本地 `mcp_snapshot/20170612` 的 `srg-mcp.srg` / `methods.csv` / `fields.csv`，把源码残留的 `func_*` / `field_*` SRG 名整词替换为 MCP 名（含大写尾字母 `func_110432_I/getSession`、`func_71401_C/getIntegratedServer`、`func_71387_A/isIntegratedServerRunning`、`func_71215_F/getServerPort`）。
2. **复用 1.12.2 已修复源码**：`RoomManager`、`SignalingClient`、`P2POverlayManager`、`ConnectionManager`、`UdpHolePuncher`、`ReliableUdpTransport`、`StunProbe`、`PeerServer`、`LogUploadManager`、`StunCache`、`RelayBridge`、`P2PBridge` 直接同步 1.12.2 修复版；原件备份在 `backup-before-sync/`。
3. **1.11.2 差异适配**：
   - `P2PBridge` 移除 1.12.2 专属 `hook.ViaForgeHook` 两处调用与导入（1.11.2 未移植该 hook）。
   - `RoomManager` 两处 `modsync.ModSyncManifestService` 调用改为注释跳过（modsync 包未移植到 1.11.2）。
   - 旧版 gson：`JsonArray.add((Number)...)` 改为 `add(new JsonPrimitive(...))`（ConnectionManager 4 处）。
   - `Minecraft.gameDir` → 1.11.2 MCP 名 `mcDataDir`。
   - 原生 `List` 遍历补泛型 `List<GuiButton>`。
   - `PortPredictor` 未初始化变量 `n` 按 1.12.2 改为 `int n4 = ...; int n = n4;`。
   - 1.11.2 `GuiScreen.keyTyped/mouseClicked` 均声明 `throws IOException`，相关覆写方法补 `throws IOException` 与 `java.io.IOException` 导入（CreateRoom/JoinRoom/RoomBrowser 三屏）。

### 2026-11-17 对齐切片（编译级 BUILD SUCCESSFUL，64 个 .java / 124 个 .class）

已从 1.12.2 回灌不依赖 modsync 的客户端与界面：
- `network/TicketClient.java`（本地 `voxlink_tickets.json`，poll/detail/reply/retract/viewed/delete；目录字段适配 `mcDataDir`；旧 gson 字符串入数组改 `add(new JsonPrimitive(...))`）。
- `ui/TicketListScreen.java`、`ui/TicketDetailScreen.java`、`ui/FeedbackScreen.java`、`ui/FeedbackFormScreen.java`、`ui/ConfigScreen.java`、`ui/UiLogBus.java`。
- `config/VoxLinkConfig.java` 补 `joinRequiredModsCheck` 字段/解析/getter/setter。
- `ui/VoxLinkScreen.java`：加反馈入口 `FEEDBACK_BTN=17`，`initGui()` 顶部 `TicketClient.pollOnceIfNeeded(null)`。
- zh/en 语言补 `voxlink.fb.*`、`voxlink.ticket.*`、`voxlink.simple_config`、`voxlink.terracotta.on/off`、`voxlink.modsync.toggle.join`、`voxlink.feedback`。

### 仍未移植（编译级不影响，运行功能缺口）
- modsync 全套（CurseForge/Modrinth 客户端、清单/门控/下载/选择屏、图标缓存、哈希等 13 个类）未回灌：依赖第三方 HTTP 客户端与清单解析，1.11.2 网络适配未验证，留后续切片。
- 运行期联机/打洞/工单联调/界面表现未在本环境实测（无 MC 客户端）。
- 版本/域名漂移：1.11.2 `MOD_VERSION=1.1.6`、默认 serverUrl 仍为旧域名 `p2p.wuhui.icu`，未在本轮统一。

### 历史失败记录
曾为 100 error：cannot find symbol 70、重复局部变量约 22、incompatible types 4；明细见 `/tmp/legacy_1112b.txt`、`/tmp/legacy_1112c.txt`。

| 文件 | error 数 |
| --- | --- |
| room/RoomManager.java | 55 |
| network/SignalingClient.java | 26 |
| VoxLinkClient.java | 18 |
| VoxLinkMod.java | 1 |

### 根因 A：SRG 运行名未回写 MCP 名（约 70 处 cannot find symbol）

反编译自生产 jar，字节码保留 Forge 运行期 SRG 名；dev 环境按 MCP 名编译，符号对不上。已确认的对照（与 1.12.2 已修复源码一致）：

| SRG | MCP（1.11.2/1.12.2 客户端通用） |
| --- | --- |
| `Minecraft.func_71410_x()` | `Minecraft.getMinecraft()` |
| `minecraft.field_71439_g` | `minecraft.player` |
| `minecraft.func_152344_a(Runnable)` | `minecraft.addScheduledTask(...)` |
| `I18n.func_135052_a(String,Object[])` | `I18n.format(...)` |
| `minecraft.func_147108_a(screen)` | `minecraft.displayGuiScreen(...)` |
| `openScreenKey.func_151468_f()` | `openScreenKey.isPressed()` |
| `minecraft.field_71462_r` | `minecraft.currentScreen` |
| `guiButton.field_146127_k` | `guiButton.id` |
| `server.func_71229_d(boolean)` | `server.setOnlineMode(...)`（待按 1.11.2 MCP 复核） |

源码内还存在大量 GUI/字体/玩家 SRG 符号（`field_146289_q`、`field_146294_l`、`func_78276_b`、`func_78256_a`、`func_73866_w` 等），需逐类对照 1.11.2 MCP 映射表回写，不能照搬 1.12.2 名称。

### 根因 B：CFR 原始 Object 接收者缺类型转换（4 处 incompatible types + 一批 Object 上取字段）

- `Object -> GuiButton`（VoxLinkClient:180，按钮列表原始 `List`）。
- `Object -> Response`（SignalingClient:478、533，HTTP 响应对象）。
- `Object -> Throwable`（SignalingClient 异常链）。
- 在 `Object` 接收者上直接取 `hostIpv6/hostIp/data/body/statusCode/success/natResult/apiResponse` 等字段，需先强转 gson `JsonObject` / 对应结果类型。

## 修复路径（未完成）

1. 先按根因 A 做 SRG→MCP 回写；客户端通用符号可参考已修复的 1.12.2 源码 `src/main/java/icu/wuhui/voxlink/`，GUI/字体/玩家类符号须以 1.11.2 MCP 映射为准复核。
2. 再按根因 B 补 gson / 集合 / 结果类型强转，参照 1.12.2 同类已修复写法。
3. 每片改完重跑上述 `compileJava`，取新鲜 exit 0 证据；本环境不跑客户端，GUI 行为留真机复核。

## 已知边界

- 第三方依赖（ViaVersion/ViaLegacy/ViaRewind/Mixin/fastutil/gson/slf4j）不在源码树，恢复可构建工程时需按官方仓库另行接入。
- 资源仍是 1.1.8 原始品牌/域名/版本，未做 PeerLink 1.1.9 二进制重打。
- 1.8.9 工程见同级 `../1.8.9/`，尚未搭建。
