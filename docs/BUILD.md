# 构建恢复与发布说明

## 1. 环境要求

- JDK 8（MC 1.12.2 / ForgeGradle 2.3 工具链）
- Gradle 由 ForgeGradle 2.3-SNAPSHOT 拉取，依赖 Minecraft 1.12.2 MDK 与 MCP 映射
- 网络可访问 MinecraftForge Maven 与 SpongePowered Maven

> 注意：反编译恢复的源码**没有在本仓库环境内做过完整 Gradle 编译验证**（当前环境未安装 ForgeGradle / MCP 映射）。下方步骤是把源码恢复成可编译工程的指引，不保证首次 `gradlew build` 一次通过。

## 2. 已知反编译局限

源码由 CFR 0.152 从发布 jar 反编译而来，恢复为可编译工程前需要处理：

1. **SRG 命名未映射**：源码内 Minecraft/Forge 引用仍是 SRG 名，例如
   - `Minecraft.func_71410_x()`（玩家实例）
   - `GuiScreen.field_146294_l` / `field_146295_m`（宽/高）
   - `I18n.func_135052_a(...)`（翻译）
   标准 MDK 开发环境使用 MCP 名，需按 `stable_39`（或选用的映射表）逐一比对对齐；也可改用保留 SRG 名的开发配置。
2. **反编译不完整的方法**：CFR 对个别方法无法完整结构化（已知 `network/StunProbe#discoverMappedAddress`）。编译报错时优先核对这些方法的控制流，必要时按字节码逻辑人工补回。
3. **缺少第三方依赖**：原始 jar 捆绑 ViaForge / ViaLegacy / ViaRewind / SpongePowered Mixin / fastutil / gson / slf4j 等，本仓库不含这些字节码。恢复构建时需从各自官方仓库以依赖形式引入，`build.gradle` 的 `dependencies {}` 留了注释位。
4. **内部类内联**：CFR 把嵌套类（`$1`、`$RoomState` 等）展开在外层 `.java` 中，编译前确认嵌套类型/匿名类结构完整。

## 3. 恢复步骤（参考）

1. 安装 JDK 8，用 `gradle wrapper --gradle-version 4.9`（ForgeGradle 2.3 适配版本）生成 wrapper。
2. 检查 `build.gradle` 中的 `minecraft.version`、`mappings` 与本机 Forge 版本一致。
3. 按第 2 节补齐第三方依赖坐标，再对齐 SRG→MCP 方法/字段名。
4. `gradlew genIntellijRuns`（或 `genEclipseRuns`）后在 IDE 中逐个打开报错类修复。
5. 产物核对：`mcmod.info`、`assets/voxlink/lang/`、`assets/voxlink/icon.png` 位于 `src/main/resources/` 下。

## 4. 品牌 / 重命名注意

审核要求项目不得与原作者或现有项目混淆。公开发布前建议：

- 对外项目名使用 **PeerLink**，仓库 URL slug 为 `peerlink-mc`，不要在标题/描述/图标中让人误以为是原 VoxLink 官方项目。
- `VoxLinkMod` 注解里的 `name="VoxLink CV"`、`mcmod.info` 中的 `name` / `authors` 是代码内显示名；如要彻底区分，可在自行发布的构建中改为新名（改 modid 会连带改资源命名空间与语言路径，需同步调整）。
- `assets/voxlink/icon.png` 取自原始 jar，属原作品品牌素材；**发布前建议替换为自己的图标**，避免品牌混淆。
- 描述中可以引用原作品，但必须写明这是非官方重建/衍生，不暗示原作者关联或认可；保留 MIT 版权署名（见 `LICENSE` / `NOTICE.md`）。

## 5. Modrinth 上线自检清单

- [ ] 源代码链接指向你自己拥有的本仓库（不再指向原作者仓库）
- [ ] 项目名 / slug / 图标已改为新品牌，无混淆
- [ ] 描述注明非官方衍生、与原作者无关联
- [ ] `LICENSE` 保留原作品 MIT 归属声明
- [ ] 第三方协议库未作为本项目源码提交，各自身份在 `NOTICE.md` 列明
- [ ] 版本号、`mcmod.info`、语言命名空间与实际 modid 一致

## 6. 推送本仓库到 GitHub

```bash
git init
git add .
git commit -m "PeerLink 1.12.2-1: recovered source + fixes"
git branch -M main
git remote add origin https://github.com/<你的用户名>/peerlink-mc.git
git push -u origin main
```

推送后，在 Modrinth 项目页「相关链接 → 源代码」填写本仓库地址；「编辑名称」处把显示名与 slug 改为 PeerLink / `peerlink-mc`。
