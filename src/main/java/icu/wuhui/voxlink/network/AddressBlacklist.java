/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AddressBlacklist {
    private static final long UDP_BLACKLIST_MS = 3600000L;
    private static final long DIRECT_BLACKLIST_MS = 300000L;
    private static final int UDP_FAIL_THRESHOLD = 3;
    private static final long FAIL_WINDOW_MS = 600000L;
    private final Map<InetSocketAddress, Long> expireAt = new ConcurrentHashMap<InetSocketAddress, Long>();
    private final Map<InetSocketAddress, long[]> udpFailState = new ConcurrentHashMap<InetSocketAddress, long[]>();

    public boolean isBlacklisted(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress == null) {
            return false;
        }
        Long l = this.expireAt.get(inetSocketAddress);
        if (l == null) {
            return false;
        }
        if (System.currentTimeMillis() >= l) {
            this.expireAt.remove(inetSocketAddress, l);
            return false;
        }
        return true;
    }

    public void recordUdpFailure(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress != null) {
            long l = System.currentTimeMillis();
            this.udpFailState.compute(inetSocketAddress, (inetSocketAddress2, lArray) -> {
                if (lArray == null || l - lArray[1] > 600000L) {
                    lArray = new long[]{0L, l};
                }
                lArray[0] = lArray[0] + 1L;
                if (lArray[0] >= 3L) {
                    this.expireAt.put(inetSocketAddress, l + 3600000L);
                    return null;
                }
                return lArray;
            });
        }
    }

    public void recordDirectFailure(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress != null) {
            this.expireAt.put(inetSocketAddress, System.currentTimeMillis() + 300000L);
            this.udpFailState.remove(inetSocketAddress);
        }
    }
}

