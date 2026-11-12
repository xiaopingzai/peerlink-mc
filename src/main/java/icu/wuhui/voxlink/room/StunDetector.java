/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionFallback;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public final class StunDetector {
    private static final List<List<String>> STUN_SERVER_GROUPS = StunDetector.unmodifiableGroups(Arrays.asList(Collections.singletonList("stun:stun.miwifi.com"), Collections.singletonList("stun:stun.hitv.com"), Collections.singletonList("stun:stun.chat.bilibili.com"), Collections.singletonList("stun:stun.l.google.com:19302"), Collections.singletonList("stun:stun1.l.google.com:19302"), Collections.singletonList("stun:stun2.l.google.com:19302"), Collections.singletonList("stun:stun3.l.google.com:19302"), Collections.singletonList("stun:stun4.l.google.com:19302"), Collections.singletonList("stun:stun.nextcloud.com"), Collections.singletonList("stun:stun.nfon.net"), Collections.singletonList("stun:stun.freeswitch.org"), Collections.singletonList("stun:stun.sipnet.com"), Collections.singletonList("stun:stun.voipbuster.com")));
    private static volatile String cachedLocalIp;
    private static volatile long localIpCacheTime;
    private static final long LOCAL_IP_CACHE_MS = 30000L;
    private static volatile Boolean cachedIpv6Reachable;
    private static volatile long ipv6CacheTime;
    private static final long IPV6_CACHE_MS = 60000L;

    private static List<List<String>> unmodifiableGroups(List<List<String>> list) {
        ArrayList<List<String>> arrayList = new ArrayList<List<String>>();
        for (List<String> list2 : list) {
            arrayList.add(Collections.unmodifiableList(list2));
        }
        return Collections.unmodifiableList(arrayList);
    }

    private StunDetector() {
    }

    public static List<String> getStunGroup(int n) {
        return STUN_SERVER_GROUPS.get(n % STUN_SERVER_GROUPS.size());
    }

    public static int getStunGroupCount() {
        return STUN_SERVER_GROUPS.size();
    }

    public static void clearLocalCaches() {
        cachedLocalIp = null;
        localIpCacheTime = 0L;
        cachedIpv6Reachable = null;
        ipv6CacheTime = 0L;
    }

    public static List<String> getAllStunUrls() {
        ArrayList<String> arrayList = new ArrayList<String>();
        for (List<String> list : STUN_SERVER_GROUPS) {
            arrayList.addAll(list);
        }
        return arrayList;
    }

    public static List<List<String>> getStunServerGroups() {
        return STUN_SERVER_GROUPS;
    }

    public static boolean isNatTypeSymmetric(String string) {
        return "symmetric".equals(string) || "symmetric_easy_inc".equals(string) || "symmetric_easy_dec".equals(string);
    }

    public static boolean isEasySymmetric(String string) {
        return "symmetric_easy_inc".equals(string) || "symmetric_easy_dec".equals(string);
    }

    public static boolean isHardSymmetric(String string) {
        return "symmetric".equals(string);
    }

    public static String getLocalIpAddress() {
        long l = System.currentTimeMillis();
        if (cachedLocalIp != null && l - localIpCacheTime < 30000L) {
            return cachedLocalIp;
        }
        String string = StunDetector.probeLocalIpAddress();
        if (string != null) {
            cachedLocalIp = string;
            localIpCacheTime = l;
        }
        return string;
    }

    private static String probeLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface networkInterface = enumeration.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) continue;
                Enumeration<InetAddress> enumeration2 = networkInterface.getInetAddresses();
                while (enumeration2.hasMoreElements()) {
                    String string;
                    InetAddress inetAddress = enumeration2.nextElement();
                    if (!(inetAddress instanceof Inet4Address) || inetAddress.isLoopbackAddress() || (string = inetAddress.getHostAddress()).startsWith("127.")) continue;
                    return string;
                }
            }
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[StunDetector] Get local IP failed: {}", (Object)exception.getMessage());
        }
        return null;
    }

    public static boolean verifyIPv6Connectivity() {
        long l = System.currentTimeMillis();
        if (cachedIpv6Reachable != null && l - ipv6CacheTime < 60000L) {
            return cachedIpv6Reachable;
        }
        boolean bl = ConnectionFallback.verifyIPv6Connectivity();
        cachedIpv6Reachable = bl;
        ipv6CacheTime = l;
        return bl;
    }

    public static boolean isSameLan(String string) {
        String string2 = StunDetector.getLocalIpAddress();
        if (string2 != null && string != null) {
            try {
                String[] stringArray = string2.split("\\.");
                String[] stringArray2 = string.split("\\.");
                if (stringArray.length == 4 && stringArray2.length == 4) {
                    boolean bl = stringArray[0].equals(stringArray2[0]) && stringArray[1].equals(stringArray2[1]) && stringArray[2].equals(stringArray2[2]);
                    boolean bl2 = bl;
                    if (bl) {
                        VoxLinkMod.LOGGER.info("[StunDetector] LAN check: myIp={} hostIp={} same LAN", (Object)string2, (Object)string);
                    }
                    return bl;
                }
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[StunDetector] LAN check failed: {}", (Object)exception.getMessage());
            }
            return false;
        }
        return false;
    }
}

