/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  net.minecraft.client.Minecraft
 */
package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import icu.wuhui.voxlink.VoxLinkMod;
import java.io.File;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import net.minecraft.client.Minecraft;

public class StunCache {
    private static final long CACHE_TTL_MS = 300000L;
    private static final String CACHE_FILE = "stun_cache.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path getCachePath() {
        File file = Minecraft.getMinecraft().gameDir;
        File file2 = new File(file, "voxlink");
        if (!file2.exists()) {
            file2.mkdirs();
        }
        return new File(file2, CACHE_FILE).toPath();
    }

    private static String buildNetworkFingerprint() {
        try {
            StringBuilder stringBuilder = new StringBuilder();
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                byte[] byArray;
                NetworkInterface networkInterface = enumeration.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp() || (byArray = networkInterface.getHardwareAddress()) == null) continue;
                stringBuilder.append(networkInterface.getName());
                for (byte by : byArray) {
                    stringBuilder.append(String.format("%02x", by));
                }
            }
            return stringBuilder.length() > 0 ? stringBuilder.toString() : "unknown";
        }
        catch (Exception exception) {
            return "unknown";
        }
    }

    public static Entry load() {
        try {
            Path path = StunCache.getCachePath();
            if (!Files.exists(path, new LinkOption[0])) {
                return null;
            }
            String string = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Entry entry = (Entry)GSON.fromJson(string, Entry.class);
            if (entry != null && !entry.isExpired()) {
                String string2 = StunCache.buildNetworkFingerprint();
                if (!string2.equals(entry.networkFingerprint)) {
                    VoxLinkMod.LOGGER.info("[StunCache] Network changed, cache invalidated");
                    return null;
                }
                VoxLinkMod.LOGGER.info("[StunCache] Hit: NAT={}, mapped={}:{}, age={}h", new Object[]{entry.natType, entry.mappedIp, entry.mappedPort, (System.currentTimeMillis() - entry.timestamp) / 3600000L});
                if (entry.mappedIp != null && !entry.mappedIp.isEmpty() && entry.mappedPort > 0) {
                    return entry;
                }
                VoxLinkMod.LOGGER.info("[StunCache] mapped address empty, invalidate for re-probe");
                return null;
            }
            VoxLinkMod.LOGGER.info("[StunCache] Cache expired or invalid");
            return null;
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[StunCache] Read failed: {}", (Object)exception.getMessage());
            return null;
        }
    }

    public static void clear() {
        try {
            Path path = StunCache.getCachePath();
            Files.deleteIfExists(path);
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[StunCache] Clear failed: {}", (Object)exception.getMessage());
        }
    }

    public static void save(String string, String string2, int n, List<String> list) {
        if (string2 != null && !string2.isEmpty() && n > 0) {
            try {
                Entry entry = new Entry(string, string2, n, list, StunCache.buildNetworkFingerprint());
                Path path = StunCache.getCachePath();
                Files.write(path, GSON.toJson((Object)entry).getBytes(StandardCharsets.UTF_8), new OpenOption[0]);
                VoxLinkMod.LOGGER.info("[StunCache] Saved: NAT={}, mapped={}:{}", new Object[]{string, string2, n});
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.debug("[StunCache] Save failed: {}", (Object)exception.getMessage());
            }
        } else {
            VoxLinkMod.LOGGER.info("[StunCache] Skip save: mapped address empty");
        }
    }

    public static class Entry {
        public String natType;
        public String mappedIp;
        public int mappedPort;
        public List<String> stunUrls;
        public long timestamp;
        public String networkFingerprint;

        public Entry() {
        }

        public Entry(String string, String string2, int n, List<String> list, String string3) {
            this.natType = string;
            this.mappedIp = string2;
            this.mappedPort = n;
            this.stunUrls = list;
            this.timestamp = System.currentTimeMillis();
            this.networkFingerprint = string3;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - this.timestamp > 300000L;
        }
    }
}

