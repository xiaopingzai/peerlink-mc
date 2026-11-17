package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.ui.UiLogBus;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * 房客侧门控：打洞前从信令服务器拉取房主必装清单 → 与本地 mods 离线 diff
 * （sha1 集合对比，客户端全程零 Modrinth API 调用）→ 有缺失时弹选择界面，
 * 下载走清单内 CDN 直链，完成后进入强制重启屏。
 * 开关关闭、旧房主（不支持 modSyncV1）、空清单时直通；直通原因一律落日志。
 * Forge 1.12.2 适配：Minecraft.getMinecraft/addScheduledTask/currentScreen/displayGuiScreen，
 * 文案走 I18n，聊天提示走 VoxLinkChat。
 */
public final class ModSyncGuestService {
    private static final Set<String> GATED_THIS_LAUNCH = ConcurrentHashMap.newKeySet();
    /** 本次启动内玩家已选择跳过检查的房间：在途清单回调静默丢弃，绝不弹窗打断加入。 */
    private static final Set<String> BYPASSED_THIS_LAUNCH = ConcurrentHashMap.newKeySet();
    /** 本次启动内弹窗已展示过且用户明确选择触发的房间（放行依据）。 */
    private static final Set<String> USER_CHOSE_THIS_LAUNCH = ConcurrentHashMap.newKeySet();
    /** 本次会话内用户已选择"跳过"的版本冲突键（projectId@versionNumber），不再重复打扰。 */
    private static final Set<String> SKIPPED_THIS_SESSION = ConcurrentHashMap.newKeySet();
    /** 最近一次成功拉取并 diff 的房主清单（供加入后只读回看）；按房码隔离，离开/换房即失效。 */
    private static volatile String cachedRoomCode = null;
    private static volatile String cachedScope = null;
    private static volatile DiffResult cachedDiff = null;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxLink-ModSync-Guest");
        t.setDaemon(true);
        return t;
    });
    /** supported 但未 ready 的最长等待：5 次 × 2s（房主 MR 解析通常数秒内完成）。 */
    private static final int READY_RETRIES = 5;
    /** 门控直通时的待播报提示：连接启动后由加入屏取出播报，避开 startDualP2P 的面板重置。 */
    private static volatile int pendingNoteLevel = -1;
    private static volatile String pendingNoteKey;

    private ModSyncGuestService() {
    }

    /* 清该房门控会话状态（在途标记+bypass）：新一次加入必须重新弹获取模组页。 */
    public static void clearGateFor(String roomCode) {
        if (roomCode != null) {
            GATED_THIS_LAUNCH.remove(roomCode);
            BYPASSED_THIS_LAUNCH.remove(roomCode);
        }
        clearViewerCache(roomCode);
    }

    /** 加入屏在连接启动后调用：播报并清掉门控直通提示（无提示时为空操作）。 */
    public static void pushPendingGateNote() {
        String key = pendingNoteKey;
        int level = pendingNoteLevel;
        pendingNoteKey = null;
        pendingNoteLevel = -1;
        if (key != null) {
            UiLogBus.push(level, key);
        }
    }

    /** 玩家在弹窗点了"直接加入"：本会话记住这些版本冲突，不再重复提示。 */
    public static void markSkipped(List<String> keys) {
        if (keys != null) {
            SKIPPED_THIS_SESSION.addAll(keys);
        }
    }

    /* 获取页"直接进入"/取消：仅作废本次在途清单回调，不跨加入记忆。 */
    public static void bypass(String roomCode) {
        if (roomCode != null) {
            BYPASSED_THIS_LAUNCH.add(roomCode);
            ModSyncLog.info("gate: player bypassed, room={}", roomCode);
        }
    }

    /* 门控跳过仅存在于单次加入流程内——每次加入都重新拉清单重新判定。 */
    public static boolean shouldSkipGate(String roomCode) {
        return roomCode != null && BYPASSED_THIS_LAUNCH.contains(roomCode);
    }

    /** 快照缓存最近一次 diff 供加入后只读回看；列表拷贝避免后续门控改写影响已缓存视图。 */
    private static void cacheForViewer(String roomCode, String scope, DiffResult diff) {
        if (roomCode == null || diff == null) {
            return;
        }

        DiffResult snap = new DiffResult();
        snap.downloadable.addAll(diff.downloadable);
        snap.manualEntries.addAll(diff.manualEntries);
        snap.installed.addAll(diff.installed);
        snap.unresolvable.addAll(diff.unresolvable);
        snap.versionDiff.addAll(diff.versionDiff);
        snap.skipKeys.addAll(diff.skipKeys);
        cachedRoomCode = roomCode;
        cachedScope = scope == null ? ModSyncManifestService.SCOPE_REQUIRED : scope;
        cachedDiff = snap;
    }

    /** 取指定房间的已缓存 diff；房码不符或无缓存返回 null。 */
    public static DiffResult getCachedDiff(String roomCode) {
        return roomCode != null && roomCode.equals(cachedRoomCode) ? cachedDiff : null;
    }

    /** 取指定房间缓存清单的范围（required/all）；无缓存回退 required。 */
    public static String getCachedScope(String roomCode) {
        return roomCode != null && roomCode.equals(cachedRoomCode) && cachedScope != null
            ? cachedScope : ModSyncManifestService.SCOPE_REQUIRED;
    }

    /** 离开/换房时清掉回看缓存，避免把上一房主清单展示给下一房。 */
    public static void clearViewerCache(String roomCode) {
        if (roomCode == null || roomCode.equals(cachedRoomCode)) {
            cachedRoomCode = null;
            cachedScope = null;
            cachedDiff = null;
        }
    }

    public static boolean isEnabled() {
        return VoxLinkMod.getConfig().isJoinRequiredModsCheck();
    }

    /**
     * 加入流程入口门控（在 join_room / 打洞之前调用）。
     * scope=required（仅必装）| all（房主全部可识别模组）。
     * onProceed/onCancel 都会被调度回主线程执行。
     */
    public static void gate(String roomCode, String scope, Runnable onProceed, Runnable onCancel) {
        if (!isEnabled() || !isVoxLinkCode(roomCode)) {
            onProceed.run();
            return;
        }

        // 玩家已选择跳过检查：直接放行，不再拉清单
        if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            onProceed.run();
            return;
        }

        // 单飞：同房在途重复 gate 直接忽略
        if (!GATED_THIS_LAUNCH.add(roomCode)) {
            ModSyncLog.info("gate: duplicate while in-flight, room={}", roomCode);
            return;
        }

        Runnable proceed = () -> {
            GATED_THIS_LAUNCH.remove(roomCode);
            onProceed.run();
        };
        Runnable cancel = () -> {
            GATED_THIS_LAUNCH.remove(roomCode);
            onCancel.run();
        };

        final String fetchScope = ModSyncManifestService.SCOPE_ALL.equals(scope)
            ? ModSyncManifestService.SCOPE_ALL : ModSyncManifestService.SCOPE_REQUIRED;
        pendingNoteKey = null;
        pendingNoteLevel = -1;
        ModSyncLog.info("gate: room={} scope={}", roomCode, fetchScope);
        EXECUTOR.execute(() -> {
            FetchResult fetched = fetchManifestWithRetry(roomCode, fetchScope);
            if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (fetched.outcome != FetchOutcome.MANIFEST) {
                ModSyncLog.info("gate skip: {}", fetched.outcome);
                if (fetched.outcome == FetchOutcome.UNSUPPORTED) {
                    pendingNoteLevel = 0;
                    pendingNoteKey = "voxlink.logui.modsync_unsupported";
                } else if (fetched.outcome == FetchOutcome.NOT_READY) {
                    pendingNoteLevel = 2;
                    pendingNoteKey = "voxlink.logui.modsync_fetch_failed";
                } else if (fetched.outcome == FetchOutcome.EMPTY) {
                    pendingNoteLevel = 0;
                    pendingNoteKey = "voxlink.logui.modsync_empty";
                }

                mc.addScheduledTask(proceed);
                return;
            }

            JsonObject manifest = fetched.manifest;

            try {
                DiffResult diff = computeDiff(manifest);
                cacheForViewer(roomCode, fetchScope, diff);
                // MC 版本检测：房主与房客的 MC 不同时，清单只能参考
                String hostMc = manifest.has("mcVersion") && manifest.get("mcVersion").isJsonPrimitive()
                    ? manifest.get("mcVersion").getAsString() : "";
                if (!hostMc.isEmpty() && !hostMc.equals(ModSyncEnv.GAME_VERSION)) {
                    ModSyncLog.warn("MC version mismatch: host={} guest={}", new Object[]{hostMc, ModSyncEnv.GAME_VERSION});
                    diff.unresolvable.add(0,
                        I18n.format("voxlink.modsync.mc_mismatch", hostMc, ModSyncEnv.GAME_VERSION));
                }
                // 加载器检测：房主与房客的 loader 不同时，清单里的 mod 文件本端无法直接运行
                String hostLoader = manifest.has("loader") && manifest.get("loader").isJsonPrimitive()
                    ? manifest.get("loader").getAsString() : "";
                if (!hostLoader.isEmpty() && !hostLoader.equalsIgnoreCase(ModSyncEnv.LOADER)) {
                    ModSyncLog.warn("Loader mismatch: host={} guest={}", new Object[]{hostLoader, ModSyncEnv.LOADER});
                    diff.unresolvable.add(0,
                        I18n.format("voxlink.modsync.loader_mismatch", hostLoader, ModSyncEnv.LOADER));
                }

                boolean diffOnlyInstalled = diff.downloadable.isEmpty() && diff.manualEntries.isEmpty()
                    && diff.unresolvable.isEmpty() && diff.versionDiff.isEmpty();
                ModSyncLog.info(
                    "guest diff: downloadable={} versionDiff={} manual={} unresolvable={} installed={} titles={}",
                    new Object[]{diff.downloadable.size(), diff.versionDiff.size(), diff.manualEntries.size(), diff.unresolvable.size(),
                        diff.installed.size(), ModSyncSelectScreen.clip(joinTitles(diff), 200)}
                );
                if (diffOnlyInstalled && (ModSyncManifestService.SCOPE_REQUIRED.equals(fetchScope) || diff.installed.isEmpty())) {
                    mc.addScheduledTask(proceed);
                    return;
                }

                mc.addScheduledTask(() -> {
                    if (mc.currentScreen != null) {
                        mc.displayGuiScreen(
                            new ModSyncSelectScreen(
                                roomCode,
                                fetchScope,
                                diff.downloadable,
                                diff.manualEntries,
                                diff.unresolvable,
                                diff.versionDiff,
                                diff.skipKeys,
                                diff.installed,
                                false,
                                proceed,
                                cancel
                            )
                        );
                    } else {
                        cancel.run();
                    }
                });
            } catch (Throwable t) {
                ModSyncLog.warn("guest diff failed, proceed without gate: {}", t.toString());
                mc.addScheduledTask(() -> {
                    try {
                        if (mc.player != null) {
                            VoxLinkChat.print(new TextComponentString(
                                TextFormatting.YELLOW + I18n.format("voxlink.modsync.gate_failed_warn")));
                        }
                    } catch (Exception ignored) {
                    }

                    proceed.run();
                });
            }
        });
    }

    /** 房码校验：legacy 无 Terracotta/互通码路由，非空即视为本端房码。 */
    private static boolean isVoxLinkCode(String code) {
        return code != null && !code.trim().isEmpty();
    }

    /** 拆粒度可中断睡眠：100ms 一查 BYPASSED_THIS_LAUNCH/线程中断。 */
    private static boolean cancellableSleep(String roomCode, long millis) {
        long end = System.currentTimeMillis() + millis;
        while (millis > 0) {
            if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
                return false;
            }

            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }

            long step = Math.min(100L, millis);
            try {
                Thread.sleep(step);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }

            millis -= step;
        }

        return !BYPASSED_THIS_LAUNCH.contains(roomCode);
    }

    /** 清单拉取结果原因：区分静默直通的具体路径。 */
    private enum FetchOutcome { MANIFEST, UNSUPPORTED, EMPTY, NOT_READY, BYPASSED }

    private static final class FetchResult {
        final FetchOutcome outcome;
        final JsonObject manifest;

        FetchResult(FetchOutcome outcome, JsonObject manifest) {
            this.outcome = outcome;
            this.manifest = manifest;
        }
    }

    /** 拉取结果：MANIFEST=拿到清单；其余为直通原因。 */
    private static FetchResult fetchManifestWithRetry(String roomCode, String scope) {
        boolean sawNotReady = false;
        boolean sawTransient = false;
        int maxAttempts = 30;
        long deadline = System.currentTimeMillis() + 45000L;
        for (int attempt = 0; attempt < maxAttempts && System.currentTimeMillis() < deadline; attempt++) {
            if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
                return new FetchResult(FetchOutcome.BYPASSED, null);
            }

            try {
                SignalingClient.ApiResponse resp = VoxLinkMod.getSignalingClient()
                    .requestRoomMods(roomCode, scope)
                    .get(8L, TimeUnit.SECONDS);
                if (!resp.success || resp.data == null) {
                    String err = resp.error != null ? resp.error.toUpperCase(java.util.Locale.ROOT) : "";
                    boolean authoritative = err.contains("ROOM_NOT_FOUND") || err.contains("ROOM_EXPIRED")
                        || err.contains("INVALID_TOKEN") || err.contains("ROOM_CLOSED") || err.contains("ROOM_EVICTED")
                        || err.contains("UNKNOWN_ENDPOINT") || err.contains("INVALID_ENDPOINT");
                    if (authoritative) {
                        ModSyncLog.warn("getRoomMods failed (authoritative): {} {}", resp.error, resp.message);
                        return new FetchResult(FetchOutcome.NOT_READY, null);
                    }

                    sawTransient = true;
                    ModSyncLog.warn("getRoomMods transient ({}/{}): {}", new Object[]{attempt + 1, maxAttempts, resp.error});
                    if (!cancellableSleep(roomCode, 1500L)) {
                        return new FetchResult(FetchOutcome.BYPASSED, null);
                    }

                    continue;
                }

                boolean supported = resp.data.has("supported") && resp.data.get("supported").getAsBoolean();
                if (!supported) {
                    return new FetchResult(FetchOutcome.UNSUPPORTED, null);
                }

                boolean ready = resp.data.has("ready") && resp.data.get("ready").getAsBoolean();
                if (ready) {
                    JsonObject m = new JsonObject();
                    m.addProperty("loader", resp.data.has("loader") ? resp.data.get("loader").getAsString() : "unknown");
                    m.addProperty("mcVersion", resp.data.has("mcVersion") ? resp.data.get("mcVersion").getAsString() : "");
                    m.add("mods", resp.data.has("mods") && resp.data.get("mods").isJsonArray()
                        ? resp.data.getAsJsonArray("mods")
                        : new com.google.gson.JsonArray());
                    m.add("unknownMods", resp.data.has("unknownMods") && resp.data.get("unknownMods").isJsonArray()
                        ? resp.data.getAsJsonArray("unknownMods")
                        : new com.google.gson.JsonArray());
                    if (m.getAsJsonArray("mods").size() == 0 && m.getAsJsonArray("unknownMods").size() == 0) {
                        return new FetchResult(FetchOutcome.EMPTY, null);
                    }

                    return new FetchResult(FetchOutcome.MANIFEST, m);
                }

                sawNotReady = true;
                if (!cancellableSleep(roomCode, 2000L)) {
                    return new FetchResult(FetchOutcome.BYPASSED, null);
                }
            } catch (java.util.concurrent.TimeoutException te) {
                sawTransient = true;
                ModSyncLog.warn("getRoomMods timeout ({}/{})", new Object[]{attempt + 1, maxAttempts});
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new FetchResult(FetchOutcome.NOT_READY, null);
            } catch (Exception e) {
                sawTransient = true;
                ModSyncLog.warn("manifest fetch error: {}", e.toString());
            }
        }

        if (sawNotReady || sawTransient) {
            ModSyncLog.warn("manifest never became ready, giving up gate");
            GATED_THIS_LAUNCH.remove(roomCode);
        }

        return new FetchResult(FetchOutcome.NOT_READY, null);
    }

    private static String joinTitles(DiffResult diff) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (ModSyncEntry e : diff.downloadable) {
            if (n++ > 0) {
                sb.append(", ");
            }

            sb.append(e.title).append('@').append(e.versionNumber);
        }

        for (ModSyncEntry e : diff.manualEntries) {
            if (n++ > 0) {
                sb.append(", ");
            }

            sb.append('?').append(e.title);
        }

        for (String s : diff.versionDiff) {
            if (n++ > 0) {
                sb.append(", ");
            }

            sb.append('~').append(s);
        }

        for (String s : diff.unresolvable) {
            if (n++ > 0) {
                sb.append(", ");
            }

            sb.append('?').append(s);
        }

        return sb.append(']').toString();
    }

    public static final class DiffResult {
        public final List<ModSyncEntry> downloadable = new ArrayList<ModSyncEntry>();
        /** 需手动安装的条目（MR 未收录/兼容性不符）：模组条展示+复制。 */
        public final List<ModSyncEntry> manualEntries = new ArrayList<ModSyncEntry>();
        /** 与本地已装文件 sha1/文件名命中的条目：全量视图分组展示（已安装）。 */
        public final List<ModSyncEntry> installed = new ArrayList<ModSyncEntry>();
        public final List<String> unresolvable = new ArrayList<String>();
        /** 同类模组但版本与房主不同：仅强提示，绝不移动/删除用户已装文件。 */
        public final List<String> versionDiff = new ArrayList<String>();
        /** 与 versionDiff 对应的会话跳过键（直接加入时记入会话记忆）。 */
        public final List<String> skipKeys = new ArrayList<String>();
    }

    /**
     * 离线 diff：清单条目自带房主文件的 sha1，与本地 mods 目录 sha1 集合直接对比——
     * 客户端全程零 Modrinth API 调用，仅按清单里的 CDN 直链下载（sha512 校验）。
     * 旧清单无 sha1 时退化为精确文件名匹配。
     */
    private static DiffResult computeDiff(JsonObject manifest) throws Exception {
        DiffResult out = new DiffResult();

        ModSyncFileHasher.ListModJarsResult scan = ModSyncFileHasher.listModJarsWithDisabled();
        List<java.nio.file.Path> localJars = scan.jars;
        Set<String> disabledFileNames = scan.disabled;
        Set<String> localSha1s = new HashSet<String>();
        Set<String> localFileNames = new HashSet<String>();
        for (java.nio.file.Path jar : localJars) {
            try {
                localSha1s.add(ModSyncFileHasher.sha1(jar));
            } catch (Exception ignored) {
            }

            localFileNames.add(jar.getFileName().toString());
        }

        // .jar.disabled 也参与 sha1 匹配（文件内容就是 jar，只是被禁用）
        Set<String> disabledSha1s = new HashSet<String>();
        if (!disabledFileNames.isEmpty()) {
            java.nio.file.Path modsDir = ModSyncEnv.getModsDir();
            for (String disabledName : disabledFileNames) {
                java.nio.file.Path p = modsDir.resolve(disabledName + ".disabled");
                try {
                    if (java.nio.file.Files.isRegularFile(p)) {
                        disabledSha1s.add(ModSyncFileHasher.sha1(p));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 本地 jar 归一化基名 → 原始文件名；用于识别"装了但版本不同"
        Map<String, String> normBaseToLocal = new HashMap<String, String>();
        Map<String, Long> normSizeByBase = new HashMap<String, Long>();
        for (java.nio.file.Path jar : localJars) {
            String nb = normalizeFileName(jar.getFileName().toString());
            if (!nb.isEmpty()) {
                normBaseToLocal.putIfAbsent(nb, jar.getFileName().toString());
                if (!normSizeByBase.containsKey(nb)) {
                    try {
                        normSizeByBase.put(nb, java.nio.file.Files.size(jar));
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 未知模组：房主侧在 MR 查不到的 jar，无自动下载渠道 → 结构化条目
        if (manifest.has("unknownMods") && manifest.get("unknownMods").isJsonArray()) {
            for (JsonElement el : manifest.getAsJsonArray("unknownMods")) {
                if (el.isJsonPrimitive()) {
                    out.manualEntries.add(ModSyncEntry.fromFileName(el.getAsString()));
                }
            }
        }

        for (JsonElement el : manifest.getAsJsonArray("mods")) {
            if (!el.isJsonObject()) {
                continue;
            }

            ModSyncEntry entry = ModSyncEntry.fromJson(el.getAsJsonObject());
            if (entry.projectId.isEmpty()) {
                continue;
            }

            boolean installed = false;
            boolean installedButDisabled = false;
            if (!entry.sha1.isEmpty()) {
                if (localSha1s.contains(entry.sha1)) {
                    installed = true;
                } else if (disabledSha1s.contains(entry.sha1)) {
                    installed = true;
                    installedButDisabled = true;
                }
            } else if (!entry.fileName.isEmpty() && localFileNames.contains(entry.fileName)) {
                installed = true;
            } else if (disabledFileNames.contains(entry.fileName)) {
                installed = true;
                installedButDisabled = true;
            }
            if (!installed) {
                // 旧清单兼容兜底：归一化名相同且文件大小一致 ⇒ 视为已安装
                String nb2 = normalizeFileName(entry.fileName);
                Long localSize = nb2.isEmpty() ? null : normSizeByBase.get(nb2);
                if (localSize != null && localSize.longValue() == entry.size) {
                    installed = true;
                }
            }
            if (installed) {
                if (installedButDisabled) {
                    out.unresolvable.add(
                        I18n.format("voxlink.modsync.disabled_hint", entry.title, entry.fileName)
                    );
                } else {
                    out.installed.add(entry);
                }

                continue;
            }

            String normBase = normalizeFileName(entry.fileName);
            if (!normBase.isEmpty() && normBaseToLocal.containsKey(normBase)) {
                String skipKey = entry.projectId + "@" + entry.versionNumber;
                if (SKIPPED_THIS_SESSION.add(skipKey)) {
                    out.versionDiff.add(
                        I18n.format("voxlink.modsync.diff_line", entry.title)
                    );
                    out.skipKeys.add(skipKey);
                }
                continue;
            }

            boolean hostFileCompatible = entry.loaders.contains(ModSyncEnv.LOADER)
                && entry.gameVersions.contains(ModSyncEnv.GAME_VERSION)
                && !entry.downloadUrl.isEmpty();
            if (hostFileCompatible) {
                out.downloadable.add(entry);
            } else {
                out.manualEntries.add(entry);
            }
        }

        return out;
    }

    /** 归一化文件名基名：小写、去 .jar、只留字母。用于粗判"同一 mod 的不同版本"。 */
    static String normalizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }

        String s = fileName.toLowerCase(java.util.Locale.ROOT);
        if (s.endsWith(".jar")) {
            s = s.substring(0, s.length() - 4);
        }

        List<String> stop = java.util.Arrays.asList(
            "fabric", "forge", "neoforge", "quilt", "fml", "mc", "all", "for", "the", "with");
        StringBuilder sb = new StringBuilder(s.length());
        for (String tok : s.split("[^a-z]+")) {
            if (tok.isEmpty() || stop.contains(tok)) {
                continue;
            }

            sb.append(tok);
        }

        return sb.toString();
    }
}
