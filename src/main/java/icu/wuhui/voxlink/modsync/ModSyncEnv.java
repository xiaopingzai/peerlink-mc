package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.VoxLinkConstants;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Path;

/**
 * ModSync 加载器/目录相关的唯一收敛点（Forge 1.12.2 适配版）。
 * 与 26.3 Fabric 版对应：LOADER 字符串、mods 目录、本地缓存文件解析。
 */
public final class ModSyncEnv {
    /** 本端加载器标识（与服务端房间 loader 字段同一取值域）。 */
    public static final String LOADER = VoxLinkConstants.LOADER;
    /** 本端 Minecraft 版本（用于向 Modrinth 过滤兼容版本）。 */
    public static final String GAME_VERSION = VoxLinkConstants.getGameVersion();

    private ModSyncEnv() {
    }

    /** 当前实例的 mods 目录。 */
    public static Path getModsDir() {
        File gameDir = Minecraft.getMinecraft().gameDir;
        return new File(gameDir, "mods").toPath();
    }

    /** modsync 本地清单缓存文件（游戏目录/voxlink 下，不进 mods 目录）。 */
    public static Path getCacheFile() {
        File gameDir = Minecraft.getMinecraft().gameDir;
        return new File(gameDir, "voxlink").toPath().resolve("modsync_cache.json");
    }
}
