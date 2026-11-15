/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.room.RoomInfo;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ProtocolNegotiator {
    public static final int PROTOCOL_VERSION_LEGACY = 0;
    public static final int PROTOCOL_VERSION_7 = 7;
    public static final String CAP_RELAY = "relay";
    public static final String CAP_ICE_RESTART = "ice_restart";
    public static final String CAP_CONTINUOUS_RETRY = "continuous_retry";
    public static final Set<String> CURRENT_CAPABILITIES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("relay", "ice_restart", "continuous_retry")));

    private ProtocolNegotiator() {
    }

    public static boolean isLegacyPeer(RoomInfo.PeerInfo peerInfo) {
        return peerInfo == null ? true : peerInfo.protocolVersion == 0 || peerInfo.capabilities == null || peerInfo.capabilities.isEmpty();
    }

    public static boolean supportsRelay(RoomInfo.PeerInfo peerInfo) {
        return peerInfo == null ? false : peerInfo.capabilities != null && peerInfo.capabilities.contains(CAP_RELAY);
    }

    public static boolean supportsIceRestart(RoomInfo.PeerInfo peerInfo) {
        return peerInfo == null ? false : peerInfo.capabilities != null && peerInfo.capabilities.contains(CAP_ICE_RESTART);
    }

    public static boolean supportsContinuousRetry(RoomInfo.PeerInfo peerInfo) {
        return peerInfo == null ? false : peerInfo.capabilities != null && peerInfo.capabilities.contains(CAP_CONTINUOUS_RETRY);
    }

    public static String describe(RoomInfo.PeerInfo peerInfo) {
        if (peerInfo == null) {
            return "null";
        }
        return ProtocolNegotiator.isLegacyPeer(peerInfo) ? "legacy(v0)" : "v" + peerInfo.protocolVersion + "+" + peerInfo.capabilities;
    }
}

