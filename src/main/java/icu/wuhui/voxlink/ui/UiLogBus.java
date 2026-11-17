package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.resources.I18n;

/**
 * 加入界面日志面板内容总线（Forge 1.12.2 适配版）。
 * 流程节点通过 push 注入 translatable 键；本环境暂不接入面板渲染，
 * 内容同时落 LOGGER 并保留环形缓冲，供后续加入屏读取。
 * level: 0=普通 1=成功 2=警告 3=失败。
 */
public final class UiLogBus {
    private static final int CAPACITY = 120;
    private static final Object LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<String>(CAPACITY);
    private static final Deque<Integer> LEVELS = new ArrayDeque<Integer>(CAPACITY);
    private static volatile long version = 0L;

    private UiLogBus() {
    }

    public static void push(int level, String key, Object... args) {
        String msg;
        try {
            msg = I18n.format(key, args);
            if (msg == null || msg.isEmpty()) {
                msg = key;
            }
        } catch (Throwable t) {
            msg = key;
        }
        boolean added;
        synchronized (LOCK) {
            added = LINES.isEmpty() || !LINES.peekLast().endsWith(msg);
            if (added) {
                LINES.addLast(msg);
                LEVELS.addLast(level);
                while (LINES.size() > CAPACITY) {
                    LINES.removeFirst();
                    LEVELS.removeFirst();
                }
                version++;
            }
        }
        if (added) {
            VoxLinkMod.LOGGER.info("[UiLogBus] {} (level={})", msg, level);
        }
    }

    public static List<String> snapshotLines() {
        synchronized (LOCK) {
            return new ArrayList<String>(LINES);
        }
    }

    public static List<Integer> snapshotLevels() {
        synchronized (LOCK) {
            return new ArrayList<Integer>(LEVELS);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            LEVELS.clear();
        }
    }

    public static List<String> unmodifiableLines() {
        return Collections.unmodifiableList(snapshotLines());
    }

    /** 进入界面时调用：不清空内容，避免界面重建时日志消失。 */
    public static void attach() {
    }

    /** 离开界面时调用。 */
    public static void detach() {
    }

    /** 新连接会话开始时调用：唯一合法的清空时机。 */
    public static void reset() {
        synchronized (LOCK) {
            LINES.clear();
            LEVELS.clear();
            version++;
        }
    }

    /** 内容版本号：UI 据此判断是否需要刷新快照。 */
    public static long version() {
        return version;
    }

    /** 最新一条正文；空总线返回 ""。 */
    public static String latestMessage() {
        synchronized (LOCK) {
            return LINES.isEmpty() ? "" : LINES.peekLast();
        }
    }

    /** 最新一条级别（0 普通 1 成功 2 警告 3 失败）；空总线返回 -1。 */
    public static int latestLevel() {
        synchronized (LOCK) {
            return LEVELS.isEmpty() ? -1 : LEVELS.peekLast();
        }
    }

    /** UI 快照：levels[i] 与 lines[i] 对应。 */
    public static void snapshot(List<String> lines, List<Integer> levels) {
        lines.clear();
        levels.clear();
        synchronized (LOCK) {
            lines.addAll(LINES);
            levels.addAll(LEVELS);
        }
    }
}
