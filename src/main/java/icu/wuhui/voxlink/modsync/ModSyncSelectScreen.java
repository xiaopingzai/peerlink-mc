package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkColors;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

/**
 * 必装模组弹窗：两键式（下载模组 / 直接加入）。内容统一为虚拟条目列表分页渲染，
 * 行数按窗口高度自适应；下载态切换为结构化进度面板（总进度条/已完成/当前文件/
 * 剩余数量/预计剩余时间），全部文案取自语言文件。
 * Forge 1.12.2 适配：GuiScreen/buttonList/fontRenderer/I18n/Gui.drawRect 替代
 * Fabric Screen/Button/Component/GuiGraphicsExtractor。
 */
public class ModSyncSelectScreen extends VoxLinkScreenBase {
    private static final int ROW_H = 12;
    private static final int MIN_ROWS = 3;
    /** 模组条离线图标色板：按名称哈希取色，低饱和 MC 风格。 */
    private static final int[] CARD_COLORS = {
        0xFF4A6FA5, 0xFF5B8C5A, 0xFFB07D3F, 0xFF8A5BA5, 0xFFA55B5B, 0xFF3F8C8C
    };

    private static final int ID_DOWNLOAD = 1;
    private static final int ID_JOIN = 2;
    private static final int ID_BACK = 3;
    private static final int ID_PREV = 4;
    private static final int ID_NEXT = 5;
    private static final int ID_CANCEL = 6;
    private static final int ID_COPY_BASE = 100;

    private final String roomCode;
    private final String scope;
    private final List<ModSyncEntry> downloadable;
    private final List<ModSyncEntry> manualEntries;
    private final List<String> unresolvable;
    private final List<String> versionDiff;
    private final List<String> skipKeys;
    private final List<ModSyncEntry> installed;
    /** 只读回看模式：无下载/直接加入按钮，不触发在线解析，仅提供关闭返回。 */
    private final boolean readOnly;
    private final Runnable onProceed;
    private final Runnable onCancel;

    /** 虚拟条目：kind 0=区块头 1=普通行 2=模组条名行(携带entry) 3=模组条小字行。 */
    private final List<Object[]> lines = new ArrayList<Object[]>();
    private int page = 0;
    private int rowsPerPage = MIN_ROWS;
    /** 当前列表区底端（最后一行下缘），状态文字画在它之下。 */
    private int listBottomY = 50;

    private GuiButton downloadBtn;
    private GuiButton joinBtn;
    private GuiButton backBtn;
    private GuiButton cancelBtn;
    private GuiButton prevBtn;
    private GuiButton nextBtn;
    private final List<GuiButton> copyButtons = new ArrayList<GuiButton>();
    private final List<String> copyTexts = new ArrayList<String>();
    private int copiedResetTick = 0;
    private GuiButton copiedBtn = null;
    private boolean resolveStarted = false;
    private volatile boolean resolving = false;
    /** MR 有项目但无本端兼容版本的条目（版本不匹配组）。 */
    private final List<ModSyncEntry> incompatibleEntries = new ArrayList<ModSyncEntry>();
    /** 下载期间到达的回流结果 {原条目, 解析条目, bucket}：回到 SELECTING 时补入。 */
    private final List<Object[]> pendingResolved = new ArrayList<Object[]>();

    private enum State { SELECTING, DOWNLOADING }

    private volatile State state = State.SELECTING;
    private volatile boolean downloadAbort = false;
    private volatile int doneFiles = 0;
    private volatile int totalFiles = 0;
    private volatile long totalBytes = 0L;
    private volatile long bytesDone = 0L;
    private volatile long startMs = 0L;
    private volatile int overallPct = 0;
    private volatile long etaSec = -1L;
    private volatile String currentName = "";
    private String statusText = "";
    private int statusColor = VoxLinkColors.MUTED;
    private final java.util.Set<Integer> succeededIdx = new java.util.HashSet<Integer>();

    public ModSyncSelectScreen(
        String roomCode,
        String scope,
        List<ModSyncEntry> downloadable,
        List<ModSyncEntry> manualEntries,
        List<String> unresolvable,
        List<String> versionDiff,
        List<String> skipKeys,
        List<ModSyncEntry> installed,
        boolean readOnly,
        Runnable onProceed,
        Runnable onCancel
    ) {
        super(ModSyncManifestService.SCOPE_ALL.equals(scope) ? "voxlink.modsync.title_all" : "voxlink.modsync.title");
        this.roomCode = roomCode;
        this.scope = scope == null ? "" : scope;
        this.downloadable = downloadable;
        this.manualEntries = manualEntries;
        this.unresolvable = unresolvable;
        this.versionDiff = versionDiff;
        this.skipKeys = skipKeys == null ? new ArrayList<String>() : skipKeys;
        this.installed = installed == null ? new ArrayList<ModSyncEntry>() : installed;
        this.readOnly = readOnly;
        this.onProceed = onProceed;
        this.onCancel = onCancel;
        // 手动条目按已知项目拆组：清单已知项目=版本不匹配组，其余=找不到组
        for (int i = this.manualEntries.size() - 1; i >= 0; i--) {
            ModSyncEntry me = this.manualEntries.get(i);
            if (!me.projectId.isEmpty() || !me.downloadUrl.isEmpty()) {
                this.manualEntries.remove(i);
                this.incompatibleEntries.add(me);
            }
        }

        this.buildLines();
    }

    /** 只读回看构造：从缓存 diff 还原各分组，readOnly=true，关闭按钮回到 parent。 */
    public ModSyncSelectScreen(String roomCode, String scope, ModSyncGuestService.DiffResult diff, Runnable onClose) {
        this(
            roomCode,
            scope,
            diff != null ? diff.downloadable : new ArrayList<ModSyncEntry>(),
            diff != null ? diff.manualEntries : new ArrayList<ModSyncEntry>(),
            diff != null ? diff.unresolvable : new ArrayList<String>(),
            diff != null ? diff.versionDiff : new ArrayList<String>(),
            diff != null ? diff.skipKeys : new ArrayList<String>(),
            diff != null ? diff.installed : new ArrayList<ModSyncEntry>(),
            true,
            onClose,
            onClose
        );
    }

    /** 三类信息拍平成虚拟行流分页渲染；模组条占两行(kind2/3)。 */
    private void buildLines() {
        this.lines.clear();
        boolean allScope = ModSyncManifestService.SCOPE_ALL.equals(this.scope);
        if (!this.installed.isEmpty()) {
            this.lines.add(new Object[]{0, I18n.format("voxlink.modsync.installed_header", this.installed.size())});
            for (ModSyncEntry e : this.installed) {
                this.addEntryCard(e, true);
            }
        }

        if (!this.downloadable.isEmpty()) {
            this.lines.add(new Object[]{0, I18n.format(
                allScope ? "voxlink.modsync.missing_header_all" : "voxlink.modsync.missing_header",
                this.downloadable.size())});
            for (ModSyncEntry e : this.downloadable) {
                this.addEntryCard(e, false);
            }
        }

        if (!this.incompatibleEntries.isEmpty()) {
            this.lines.add(new Object[]{0, I18n.format("voxlink.modsync.incompatible_header", this.incompatibleEntries.size())});
            for (ModSyncEntry e : this.incompatibleEntries) {
                this.addEntryCard(e, false);
            }
        }

        if (!this.manualEntries.isEmpty()) {
            this.lines.add(new Object[]{0, I18n.format("voxlink.modsync.notfound_header", this.manualEntries.size())});
            for (ModSyncEntry e : this.manualEntries) {
                this.addEntryCard(e, false);
            }
        }

        if (!this.unresolvable.isEmpty() || !this.versionDiff.isEmpty()) {
            this.lines.add(new Object[]{0, I18n.format("voxlink.modsync.notes_header")});
            for (String u : this.unresolvable) {
                this.lines.add(new Object[]{1, cleanText(u)});
            }

            for (String d : this.versionDiff) {
                this.lines.add(new Object[]{1, cleanText(d)});
            }
        }
    }

    /** 模组条 = 名行(图标/名称/复制按钮) + 小字行(jar 原名·版本·大小)。 */
    private void addEntryCard(ModSyncEntry e, boolean installed) {
        String sub = cleanText(e.fileName);
        if (!e.versionNumber.isEmpty()) {
            sub = e.versionNumber + " · " + sub;
        }

        if (e.size > 0L) {
            sub = sub + " · " + humanSize(e.size);
        }

        this.lines.add(new Object[]{2, cleanText(e.title), e});
        this.lines.add(new Object[]{3, sub});
    }

    /** 清洗待绘制文本：剥掉 § 样式代码与控制字符，避免整行染色/花屏。 */
    static String cleanText(String s) {
        return s == null ? "" : s.replaceAll("§.", "").replaceAll("\\p{Cntrl}", "").trim();
    }

    @Override
    public void initGui() {
        super.initGui();
        this.copyButtons.clear();
        this.copyTexts.clear();
        this.copiedBtn = null;
        int centerX = this.width / 2;
        int listTop = 50;
        // 状态文字（下载失败原因等）占列表底部之下的专属一行，计入预算防止压住最后一行
        int statusReserve = this.statusText.isEmpty() ? 0 : 13;
        int footerReserve = (this.state == State.SELECTING ? 78 : 46) + statusReserve;
        this.rowsPerPage = Math.max(MIN_ROWS, (this.height - listTop - footerReserve) / ROW_H);
        this.listBottomY = listTop + this.rowsPerPage * ROW_H;
        int pages = this.pageCount();
        if (this.page >= pages) {
            this.page = Math.max(0, pages - 1);
        }

        boolean paging = pages > 1 && this.state == State.SELECTING;
        if (paging) {
            int py = this.height - footerReserve + 2;
            this.prevBtn = new GuiButton(ID_PREV, centerX - 90, py, 40, 16, I18n.format("voxlink.modsync.prev_page"));
            this.buttonList.add(this.prevBtn);
            this.nextBtn = new GuiButton(ID_NEXT, centerX + 50, py, 40, 16, I18n.format("voxlink.modsync.next_page"));
            this.buttonList.add(this.nextBtn);
        }

        if (this.state == State.SELECTING) {
            this.buildCopyButtons();
            if (!this.readOnly && !this.resolveStarted && (!this.manualEntries.isEmpty() || !this.incompatibleEntries.isEmpty())) {
                this.resolveStarted = true;
                this.startResolve();
            }

            int by = this.height - 54;
            boolean hasDownloads = !this.readOnly && !this.downloadable.isEmpty();
            if (hasDownloads) {
                long selBytes = 0L;
                for (ModSyncEntry e : this.downloadable) {
                    selBytes += e.size;
                }

                this.downloadBtn = new GuiButton(
                    ID_DOWNLOAD, centerX - 130, by, 128, 20,
                    I18n.format("voxlink.modsync.download_mods", this.downloadable.size(), humanSize(selBytes))
                );
                this.buttonList.add(this.downloadBtn);
            }

            if (this.readOnly) {
                this.joinBtn = new GuiButton(ID_JOIN, centerX - 65, by, 130, 20, I18n.format("voxlink.modsync.back"));
                this.buttonList.add(this.joinBtn);
                this.backBtn = null;
            } else {
                this.joinBtn = new GuiButton(
                    ID_JOIN,
                    hasDownloads ? centerX + 2 : centerX - 65,
                    by,
                    hasDownloads ? 128 : 130,
                    20,
                    I18n.format(this.versionDiff.isEmpty() ? "voxlink.modsync.join_directly" : "voxlink.modsync.join_anyway")
                );
                this.buttonList.add(this.joinBtn);
                this.backBtn = new GuiButton(ID_BACK, centerX - 65, by + 24, 130, 20, I18n.format("voxlink.modsync.back"));
                this.buttonList.add(this.backBtn);
            }
        } else {
            this.cancelBtn = new GuiButton(ID_CANCEL, centerX - 65, this.height - 30, 130, 20, I18n.format("voxlink.modsync.cancel_download"));
            this.buttonList.add(this.cancelBtn);
        }
    }

    private int pageCount() {
        return Math.max(1, (this.lines.size() + this.rowsPerPage - 1) / this.rowsPerPage);
    }

    private void flipPage(int delta) {
        int pages = this.pageCount();
        this.page = Math.floorMod(this.page + delta, pages);
        this.rebuildInPlace();
    }

    private void rebuildInPlace() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == this) {
            this.buttonList.clear();
            this.initGui();
        }
    }

    private void startDownload() {
        if (this.state == State.DOWNLOADING) {
            return;
        }

        this.state = State.DOWNLOADING;
        this.downloadAbort = false;
        this.totalFiles = this.downloadable.size();
        this.doneFiles = 0;
        this.bytesDone = 0L;
        this.startMs = System.currentTimeMillis();
        long tb = 0L;
        for (ModSyncEntry e : this.downloadable) {
            tb += e.size;
        }

        this.totalBytes = tb;
        this.currentName = "";
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                downloadLoop();
            }
        }, "VoxLink-ModSync-DL");
        worker.setDaemon(true);
        worker.start();
        this.rebuildInPlace();
    }

    private void downloadLoop() {
        Path modsDir = ModSyncEnv.getModsDir();
        try {
            java.nio.file.Files.createDirectories(modsDir);
        } catch (Exception e) {
            this.failThrough(I18n.format("voxlink.modsync.download_failed", "mods dir"));
            return;
        }

        String lastError = null;
        Path modsDirNorm = modsDir.normalize();
        for (int i = 0; i < this.downloadable.size(); i++) {
            if (this.downloadAbort) {
                this.abortThrough();
                return;
            }

            ModSyncEntry e = this.downloadable.get(i);
            this.currentName = e.title;

            // H6：fileName 消毒——拒绝 ../、绝对路径、路径分隔符、冒号、控制字符
            if (!isSafeFileName(e.fileName, modsDirNorm)) {
                ModSyncLog.warn("skip unsafe fileName entry: {} (title={})", new Object[]{e.fileName, e.title});
                this.unresolvable.add(e.fileName);
                lastError = e.title;
                this.overallPct = this.totalBytes > 0 ? (int) Math.min(100L, this.bytesDone * 100L / this.totalBytes) : 0;
                continue;
            }

            try {
                long bytes = ModrinthClient.downloadVerified(
                    e.downloadUrl,
                    e.sha512,
                    (e.sha1 == null || e.sha1.isEmpty()) ? null : e.sha1,
                    e.fileName,
                    modsDir,
                    new java.util.function.BooleanSupplier() {
                        @Override
                        public boolean getAsBoolean() {
                            return downloadAbort;
                        }
                    }
                );
                synchronized (this.succeededIdx) {
                    this.succeededIdx.add(i);
                }

                this.doneFiles++;
                this.bytesDone += bytes;
                long elapsed = Math.max(1L, System.currentTimeMillis() - this.startMs);
                double speed = this.bytesDone / (elapsed / 1000.0);
                long remainB = Math.max(0L, this.totalBytes - this.bytesDone);
                this.etaSec = speed > 0 ? (long) (remainB / speed) : -1L;
            } catch (ModrinthClient.AbortedException aborted) {
                ModSyncLog.info("download {} cancelled by user", e.fileName);
                this.abortThrough();
                return;
            } catch (Exception ex) {
                ModSyncLog.warn("download {} failed: {}", new Object[]{e.fileName, ex.toString()});
                this.unresolvable.add(e.fileName);
                lastError = e.title;
            }

            this.overallPct = this.totalBytes > 0 ? (int) Math.min(100L, this.bytesDone * 100L / this.totalBytes) : 0;
        }

        if (this.downloadAbort) {
            this.abortThrough();
            return;
        }

        final int okCount = this.succeededIdx.size();
        if (okCount > 0 && lastError == null) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    Minecraft.getMinecraft().displayGuiScreen(new ModSyncRestartScreen(okCount));
                }
            });
        } else {
            final String err = lastError;
            this.failThrough(I18n.format("voxlink.modsync.download_failed", err == null ? "?" : clip(err, 40)));
        }
    }

    private void abortThrough() {
        this.state = State.SELECTING;
        this.applyPendingResolved();
        this.statusText = I18n.format("voxlink.modsync.download_cancelled");
        this.statusColor = VoxLinkColors.MUTED;
        this.downloadAbort = false;
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                rebuildInPlace();
            }
        });
    }

    private void failThrough(String msg) {
        this.state = State.SELECTING;
        this.applyPendingResolved();
        String hint = I18n.format("voxlink.modsync.retry_hint");
        this.statusText = hint.isEmpty() ? msg : (msg + " " + hint);
        this.statusColor = VoxLinkColors.ERROR;
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                rebuildInPlace();
            }
        });
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        this.drawCenteredString(this.fontRenderer, this.t(this.titleKey), centerX, 10, VoxLinkColors.WHITE);
        this.drawCenteredString(
            this.fontRenderer,
            I18n.format(ModSyncManifestService.SCOPE_ALL.equals(this.scope) ? "voxlink.modsync.subtitle_all" : "voxlink.modsync.subtitle", this.roomCode),
            centerX, 24, VoxLinkColors.MUTED
        );

        if (this.state == State.SELECTING) {
            this.renderList(centerX);
            int pages = this.pageCount();
            if (pages > 1) {
                this.drawCenteredString(
                    this.fontRenderer,
                    I18n.format("voxlink.modsync.page_ind", this.page + 1, pages),
                    centerX, this.height - 74, VoxLinkColors.MUTED
                );
            }

            if (!this.statusText.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, this.statusText, centerX, Math.min(this.listBottomY + 2, this.height - 86), this.statusColor);
            } else if (this.resolving) {
                this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.resolving"), centerX, Math.min(this.listBottomY + 2, this.height - 86), VoxLinkColors.MUTED);
            }
        } else {
            this.renderProgress(centerX);
        }
    }

    private void renderList(int centerX) {
        int listTop = 50;
        int[] range = this.pageRange();
        int iconX = 10;
        int textX = iconX + 20;
        int copyW = 36;
        int y = listTop;
        for (int i = range[0]; i < range[1]; i++) {
            Object[] line = this.lines.get(i);
            int kind = (Integer) line[0];
            String text = String.valueOf(line[1]);
            if (kind == 0) {
                this.drawCenteredString(this.fontRenderer, text, centerX, y, VoxLinkColors.WARNING);
            } else if (kind == 1) {
                this.drawCenteredString(this.fontRenderer, text, centerX, y, VoxLinkColors.WHITE);
            } else if (kind == 2) {
                ModSyncEntry e = (ModSyncEntry) line[2];
                String title = text.isEmpty() ? cleanText(e.fileName) : text;
                int[][] iconRuns = ModSyncIconCache.runs(e.iconUrl);
                if (iconRuns == null && !e.iconUrl.isEmpty()) {
                    ModSyncIconCache.prefetch(e.iconUrl);
                }

                if (iconRuns != null) {
                    for (int[] run : iconRuns) {
                        Gui.drawRect(iconX + run[0], y + 3 + run[1], iconX + run[0] + run[2], y + 4 + run[1], run[3]);
                    }
                } else {
                    int hue = Math.floorMod(title.hashCode(), CARD_COLORS.length);
                    Gui.drawRect(iconX, y + 3, iconX + 16, y + 19, CARD_COLORS[hue]);
                    String initial = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                    this.fontRenderer.drawString(initial, iconX + 8 - this.fontRenderer.getStringWidth(initial) / 2, y + 7, 0xFFFFFFFF);
                }
                int titleColor = this.installed.contains(e) ? VoxLinkColors.SUCCESS : 0xFFFFFFFF;
                this.fontRenderer.drawString(clipToWidth(title, this.width - 8 - copyW - textX - 6), textX, y + 2, titleColor);
            } else if (kind == 3) {
                this.fontRenderer.drawString(clipToWidth(text, this.width - 8 - textX), textX, y + 2, VoxLinkColors.MUTED);
            }

            y += ROW_H;
        }
    }

    /** 当前页的行区间：模组条占两行(kind2/3)，切割必须落在条边界上。 */
    private int[] pageRange() {
        int from = this.page * this.rowsPerPage;
        int to = Math.min(this.lines.size(), from + this.rowsPerPage);
        if (to < this.lines.size() && this.kindAt(to - 1) == 3) {
            to--;
        }

        if (from > 0 && from < this.lines.size() && this.kindAt(from) == 3) {
            from++;
        }

        if (from >= to && from >= 2) {
            from -= 2;
        }

        return new int[]{from, Math.max(from, to)};
    }

    private int kindAt(int idx) {
        return idx >= 0 && idx < this.lines.size() ? (Integer) this.lines.get(idx)[0] : -1;
    }

    /** 为当前页的每个模组条生成复制按钮（按钮只占自己的条行，绝不压底部按钮区）。 */
    private void buildCopyButtons() {
        this.copyButtons.clear();
        this.copyTexts.clear();
        this.copiedResetTick = 0;
        this.copiedBtn = null;
        int[] range = this.pageRange();
        int copyW = 36;
        int copyX = this.width - 8 - copyW;
        int y = 50;
        int copyIdx = 0;
        for (int i = range[0]; i < range[1]; i++) {
            Object[] line = this.lines.get(i);
            if ((Integer) line[0] == 2) {
                ModSyncEntry e = (ModSyncEntry) line[2];
                String copyText = String.valueOf(line[1]).isEmpty() ? e.fileName : String.valueOf(line[1]);
                GuiButton b = new GuiButton(ID_COPY_BASE + copyIdx, copyX, y + 1, copyW, 18, I18n.format("voxlink.modsync.copy"));
                this.copyButtons.add(b);
                this.copyTexts.add(copyText);
                this.buttonList.add(b);
                copyIdx++;
            }

            y += ROW_H;
        }
    }

    /** 后台 MR 兜底解析手动条目：真名/真图标原地更新，可下载者回流，按钮随之出现。 */
    private void startResolve() {
        this.resolving = true;
        List<ModSyncEntry> snapshot = new ArrayList<ModSyncEntry>(this.manualEntries);
        List<ModSyncEntry> incompatible = new ArrayList<ModSyncEntry>(this.incompatibleEntries);
        List<ModSyncEntry> known = new ArrayList<ModSyncEntry>(this.downloadable);
        ModSyncResolver.resolveAsync(snapshot, incompatible, known, new ModSyncResolver.Callback() {
            @Override
            public void onResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        applyResolved(original, resolved, bucket);
                    }
                });
            }

            @Override
            public void onFinished(int resolvedCount) {
                ModSyncLog.info("resolve finished, {} became downloadable", resolvedCount);
                ModSyncSelectScreen.this.resolving = false;
            }
        });
    }

    private void applyResolved(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
        if (this.state != State.SELECTING) {
            if (bucket == ModSyncResolver.BUCKET_DOWNLOADABLE || resolved != null) {
                this.pendingResolved.add(new Object[]{original, resolved, bucket});
            }

            return;
        }

        this.applyOne(original, resolved, bucket);
        this.buildLines();
        this.rebuildInPlace();
    }

    private void applyOne(ModSyncEntry original, ModSyncEntry resolved, int bucket) {
        if (bucket == ModSyncResolver.BUCKET_DOWNLOADABLE && resolved != null) {
            if (original != null) {
                this.manualEntries.remove(original);
                this.incompatibleEntries.remove(original);
            }

            this.downloadable.add(resolved);
        } else if (bucket == ModSyncResolver.BUCKET_INCOMPATIBLE && original != null) {
            this.manualEntries.remove(original);
            if (!this.incompatibleEntries.contains(original)) {
                this.incompatibleEntries.add(original);
            }
        }
    }

    private void applyPendingResolved() {
        if (this.pendingResolved.isEmpty()) {
            return;
        }

        for (Object[] p : this.pendingResolved) {
            this.applyOne((ModSyncEntry) p[0], (ModSyncEntry) p[1], (Integer) p[2]);
        }

        this.pendingResolved.clear();
        this.buildLines();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.copiedResetTick > 0 && --this.copiedResetTick == 0 && this.copiedBtn != null) {
            this.copiedBtn.displayString = I18n.format("voxlink.modsync.copy");
            this.copiedBtn.enabled = true;
            this.copiedBtn = null;
        }
    }

    /** 左对齐按像素宽截断（超宽补省略号）。 */
    static String clipToWidth(String s, int maxW) {
        if (s == null || s.isEmpty()) {
            return "";
        }

        if (this_font().getStringWidth(s) <= maxW) {
            return s;
        }

        String ellipsis = "…";
        while (!s.isEmpty() && this_font().getStringWidth(s + ellipsis) > maxW) {
            s = s.substring(0, s.length() - 1);
        }

        return s + ellipsis;
    }

    private static net.minecraft.client.gui.FontRenderer this_font() {
        return Minecraft.getMinecraft().fontRenderer;
    }

    private void renderProgress(int centerX) {
        int top = Math.max(60, this.height / 2 - 52);
        this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.progress_title"), centerX, top, VoxLinkColors.WHITE);
        int barW = Math.min(220, this.width - 40);
        int barY = top + 18;
        Gui.drawRect(centerX - barW / 2, barY, centerX + barW / 2, barY + 6, 0xFF2B2B31);
        int fillW = (int) ((long) barW * Math.max(0, Math.min(100, this.overallPct)) / 100L);
        if (fillW > 0) {
            Gui.drawRect(centerX - barW / 2, barY, centerX - barW / 2 + fillW, barY + 6, VoxLinkColors.SUCCESS);
        }

        int y = barY + 14;
        this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.progress_overall", this.overallPct + "%"), centerX, y, VoxLinkColors.MUTED);
        y += ROW_H;
        int okNow;
        synchronized (this.succeededIdx) {
            okNow = this.succeededIdx.size();
        }

        this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.progress_completed", okNow, this.totalFiles), centerX, y, VoxLinkColors.WHITE);
        y += ROW_H;
        this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.progress_current", cleanText(clip(this.currentName, 36))), centerX, y, VoxLinkColors.WARNING);
        y += ROW_H;
        int remaining = Math.max(0, this.totalFiles - okNow);
        this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.progress_remaining", remaining), centerX, y, VoxLinkColors.MUTED);
        y += ROW_H;
        this.drawCenteredString(this.fontRenderer, I18n.format("voxlink.modsync.progress_eta", fmtEta(this.etaSec)), centerX, y, VoxLinkColors.MUTED);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_DOWNLOAD) {
            this.startDownload();
            return;
        }
        if (button.id == ID_PREV) {
            this.flipPage(-1);
            return;
        }
        if (button.id == ID_NEXT) {
            this.flipPage(1);
            return;
        }
        if (button.id == ID_BACK) {
            this.onCancel.run();
            return;
        }
        if (button.id == ID_JOIN) {
            ModSyncGuestService.markSkipped(this.skipKeys);
            this.onProceed.run();
            return;
        }
        if (button.id == ID_CANCEL) {
            if (this.downloadAbort) {
                return;
            }
            this.downloadAbort = true;
            button.displayString = I18n.format("voxlink.modsync.cancelling");
            button.enabled = false;
            this.statusText = I18n.format("voxlink.modsync.cancelling");
            this.statusColor = VoxLinkColors.MUTED;
            return;
        }
        if (button.id >= ID_COPY_BASE) {
            int idx = button.id - ID_COPY_BASE;
            if (idx >= 0 && idx < this.copyTexts.size()) {
                GuiScreenSetClipboard(this.copyTexts.get(idx));
                button.displayString = I18n.format("voxlink.modsync.copied");
                button.enabled = false;
                this.copiedBtn = button;
                this.copiedResetTick = 60;
            }
        }
    }

    private static void GuiScreenSetClipboard(String text) {
        net.minecraft.client.gui.GuiScreen.setClipboardString(text == null ? "" : text);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) {
            // 吞掉关闭的同时给出反馈：下载中提示先取消；选择中提示用按钮返回。
            String key = this.state == State.DOWNLOADING
                ? "voxlink.modsync.esc_block_downloading"
                : "voxlink.modsync.esc_block_selecting";
            this.statusText = I18n.format(key);
            this.statusColor = VoxLinkColors.WARNING;
            this.rebuildInPlace();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }

        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String humanSize(long bytes) {
        if (bytes <= 0L) {
            return "?";
        }

        if (bytes < 1024L * 1024L) {
            return bytes / 1024L + "KB";
        }

        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }

    static String fmtEta(long sec) {
        if (sec < 0L) {
            return "--:--";
        }

        long m = sec / 60L;
        if (m > 99L) {
            return "--:--";
        }

        long s = sec % 60L;
        return m + ":" + (s < 10L ? "0" : "") + s;
    }

    /** H6：校验 fileName 是否会落在 modsDir 之内。 */
    static boolean isSafeFileName(String fileName, Path modsDirNorm) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }

        if (fileName.contains("..")) {
            return false;
        }

        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            return false;
        }

        if (fileName.indexOf(':') >= 0) {
            return false;
        }

        for (int i = 0; i < fileName.length(); i++) {
            if (Character.isISOControl(fileName.charAt(i))) {
                return false;
            }
        }

        try {
            Path resolved = modsDirNorm.resolve(fileName).normalize();
            return resolved.startsWith(modsDirNorm);
        } catch (Exception ex) {
            return false;
        }
    }
}
