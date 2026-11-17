package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新检查（移植 26.3 UpdateChecker 到 Forge 1.11.2 / JDK8）。
 * 复用 legacy Http8 的 JDK8 HTTP 线程池；单飞 + 6 小时节流；语义版本比较；
 * 有新版本时调度回主线程在聊天栏播报版本号与发布页链接。
 */
public final class UpdateChecker {
    private static final String RELEASES_API = "https://api.github.com/repos/AUGUHDAR/VoxLink/releases/latest";
    private static final long REQUEST_TIMEOUT_MS = 15000L;
    private static final long THROTTLE_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long lastCheckMs = 0L;

    private UpdateChecker() {
    }

    public static void checkOnce() {
        if (!VoxLinkMod.getConfig().isUpdateCheckEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (IN_FLIGHT.get() || now - lastCheckMs < THROTTLE_INTERVAL_MS) {
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        lastCheckMs = now;

        Http8.getAsync(RELEASES_API, "VoxLink-UpdateChecker", REQUEST_TIMEOUT_MS)
            .thenAccept(resp -> {
                if (resp.statusCode != 200) {
                    VoxLinkMod.LOGGER.warn("Update check HTTP status: {}", resp.statusCode);
                    return;
                }
                try {
                    JsonObject json = new JsonParser().parse(resp.body).getAsJsonObject();
                    String tagName = json.get("tag_name").getAsString();
                    String latest = stripPrefix(tagName);
                    String current = stripMCVersion(VoxLinkMod.MOD_VERSION);
                    if (isNewer(latest, current)) {
                        String url = json.has("html_url") ? json.get("html_url").getAsString()
                            : "https://github.com/AUGUHDAR/VoxLink/releases";
                        notifyUpdate(latest, url);
                    }
                } catch (Exception e) {
                    VoxLinkMod.LOGGER.warn("Update check parse failed: {}", (Object)e.getMessage());
                }
            })
            .exceptionally(e -> {
                VoxLinkMod.LOGGER.warn("Update check failed: {}", (Object)e.getMessage());
                return null;
            })
            .whenComplete((r, e) -> IN_FLIGHT.set(false));
    }

    private static String stripPrefix(String tag) {
        if (tag == null) {
            return "";
        }
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    private static String stripMCVersion(String modVersion) {
        if (modVersion == null) {
            return "";
        }
        int dash = modVersion.indexOf(45);
        return dash > 0 ? modVersion.substring(0, dash) : modVersion;
    }

    private static boolean isNewer(String latest, String current) {
        int[] l = parseSemver(latest);
        int[] c = parseSemver(current);
        for (int i = 0; i < Math.max(l.length, c.length); ++i) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv > cv) {
                return true;
            }
            if (lv < cv) {
                return false;
            }
        }
        return false;
    }

    private static int[] parseSemver(String v) {
        if (v != null && !v.isEmpty()) {
            String[] parts = v.split("\\.");
            int[] arr = new int[parts.length];
            for (int i = 0; i < parts.length; ++i) {
                try {
                    arr[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    arr[i] = 0;
                }
            }
            return arr;
        }
        return new int[]{0};
    }

    private static void notifyUpdate(String latest, String url) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            String prefix = I18n.format("voxlink.chat.error_prefix", new Object[0]);
            VoxLinkChat.print(prefix + I18n.format("voxlink.update.available", new Object[]{latest}));
            VoxLinkChat.print(url);
        });
    }
}
