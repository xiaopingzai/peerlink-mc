/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.VoxLinkMod;

public enum ConnectionState {
    IDLE("\u7a7a\u95f2"),
    STUN_PROBE("STUN\u63a2\u6d4b"),
    SIGNAL_EXCHANGE("\u4fe1\u4ee4\u4ea4\u6362"),
    UDP_PUNCH("UDP\u6253\u6d1e"),
    TCP_FALLBACK("TCP\u56de\u9000"),
    TRANSPORT_SETUP("\u4f20\u8f93\u5efa\u7acb"),
    CONNECTED("\u5df2\u8fde\u63a5"),
    DISCONNECTED("\u5df2\u65ad\u5f00"),
    FAILED("\u5931\u8d25");

    public final String displayName;
    private static volatile ConnectionState currentState;
    private static volatile long stateEnterTime;

    private ConnectionState(String string2) {
        this.displayName = string2;
    }

    public boolean isTerminal() {
        return this == CONNECTED || this == DISCONNECTED || this == FAILED;
    }

    public boolean canTransitionTo(ConnectionState connectionState) {
        switch (this) {
            case IDLE: {
                return connectionState == STUN_PROBE || connectionState == DISCONNECTED || connectionState == UDP_PUNCH || connectionState == TRANSPORT_SETUP;
            }
            case STUN_PROBE: {
                return connectionState == SIGNAL_EXCHANGE || connectionState == UDP_PUNCH || connectionState == TCP_FALLBACK || connectionState == FAILED;
            }
            case SIGNAL_EXCHANGE: {
                return connectionState == UDP_PUNCH || connectionState == TCP_FALLBACK || connectionState == FAILED || connectionState == TRANSPORT_SETUP;
            }
            case UDP_PUNCH: {
                return connectionState == TRANSPORT_SETUP || connectionState == TCP_FALLBACK || connectionState == FAILED || connectionState == CONNECTED || connectionState == IDLE;
            }
            case TCP_FALLBACK: {
                return connectionState == TRANSPORT_SETUP || connectionState == FAILED || connectionState == CONNECTED || connectionState == STUN_PROBE || connectionState == UDP_PUNCH || connectionState == IDLE;
            }
            case TRANSPORT_SETUP: {
                return connectionState == CONNECTED || connectionState == FAILED || connectionState == UDP_PUNCH || connectionState == IDLE;
            }
            case CONNECTED: {
                return connectionState == DISCONNECTED || connectionState == UDP_PUNCH || connectionState == IDLE;
            }
            case DISCONNECTED: 
            case FAILED: {
                return connectionState == IDLE || connectionState == STUN_PROBE || connectionState == UDP_PUNCH;
            }
        }
        throw new IncompatibleClassChangeError();
    }

    public static synchronized void transitionTo(ConnectionState connectionState, String string) {
        ConnectionState connectionState2 = currentState;
        if (connectionState2 != connectionState) {
            if (!connectionState2.canTransitionTo(connectionState)) {
                VoxLinkMod.LOGGER.warn("[ConnState] Invalid state transition: {} -> {} (detail={})", new Object[]{connectionState2, connectionState, string});
                if (!connectionState.isTerminal()) {
                    return;
                }
            }
            long l = System.currentTimeMillis() - stateEnterTime;
            VoxLinkMod.LOGGER.info("[ConnState] {} -> {} ({}ms) {}", new Object[]{connectionState2.displayName, connectionState.displayName, l, string});
            currentState = connectionState;
            stateEnterTime = System.currentTimeMillis();
        }
    }

    public static ConnectionState getCurrent() {
        return currentState;
    }

    public static long getStateEnterTime() {
        return stateEnterTime;
    }

    public static long getStateDurationMs() {
        return System.currentTimeMillis() - stateEnterTime;
    }

    public static void reset() {
        currentState = IDLE;
        stateEnterTime = System.currentTimeMillis();
    }

    static {
        currentState = IDLE;
        stateEnterTime = System.currentTimeMillis();
    }
}

