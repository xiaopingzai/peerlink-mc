/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.room;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomInfo {
    private final String code;
    private volatile String name;
    private volatile boolean hasPassword;
    private volatile int maxPlayers;
    private final String token;
    private final boolean isHost;
    private final int hostPort;
    private volatile String natType;
    private volatile String hostIp;
    private volatile String hostIpv6;
    private volatile InetSocketAddress hostMappedAddress = null;
    private volatile boolean hostSymmetric;
    private volatile boolean hostEasySym;
    private volatile int hostMappedPortDelta = 0;
    private volatile int hostMappedPortRange = 100;
    private volatile List<Integer> hostBirthdayPorts = null;
    private volatile long punchSyncTimeMs = 0L;
    private volatile long punchSyncSentAtMs = 0L;
    private volatile long joinerOfferRecvMs = 0L;
    private final AtomicInteger currentPlayers;
    private volatile String clientId;
    private volatile int bedrockPort;
    private volatile int serverProtocolVersion;
    private volatile String category;
    private volatile String authType;
    private volatile int peerPort;
    private volatile String clientType = "mod";
    private volatile String loader = "unknown";
    private volatile String connectionMode = "";
    private volatile boolean usingRelay = false;
    private volatile boolean connectionFailed = false;
    private volatile int localBridgePort = 0;
    private volatile int hostConnectPort = 0;
    private volatile PortStatus ipv4Status = PortStatus.UNKNOWN;
    private volatile PortStatus ipv6Status = PortStatus.UNKNOWN;
    private volatile boolean nameApproved = true;
    private volatile boolean visible = true;
    private volatile boolean sameCgnat = false;
    private volatile boolean guestOp = false;
    private volatile String gameType = "survival";
    private volatile boolean hostOp = false;
    private volatile String hostLocalIp = null;
    private volatile String joinerLocalIp = null;
    private volatile String myMappedIp = null;
    private volatile int myMappedPort = 0;
    private volatile String terracottaCode = null;
    private final ConcurrentHashMap<String, PeerInfo> peerMap = new ConcurrentHashMap();
    private volatile boolean connectionAttemptFailed = false;
    private volatile boolean everHadPeer = false;
    private volatile int hostProtocolVersion = 0;
    private volatile Set<String> hostCapabilities = Collections.emptySet();

    public void addOrUpdatePeer(String string2, String string3, String string4, int n) {
        PeerInfo peerInfo = this.peerMap.computeIfAbsent(string2, string -> new PeerInfo((String)string));
        if (string3 != null) {
            peerInfo.natType = string3;
        }
        if (string4 != null) {
            peerInfo.mappedIp = string4;
        }
        if (n > 0) {
            peerInfo.mappedPort = n;
        }
        peerInfo.lastSeenMs = System.currentTimeMillis();
        this.everHadPeer = true;
    }

    public void addOrUpdatePeer(String string2, String string3, String string4, int n, int n2, Set<String> set) {
        PeerInfo peerInfo = this.peerMap.computeIfAbsent(string2, string -> new PeerInfo((String)string));
        if (string3 != null) {
            peerInfo.natType = string3;
        }
        if (string4 != null) {
            peerInfo.mappedIp = string4;
        }
        if (n > 0) {
            peerInfo.mappedPort = n;
        }
        if (n2 > 0) {
            peerInfo.protocolVersion = n2;
        }
        if (set != null && !set.isEmpty()) {
            peerInfo.capabilities = set;
        }
        peerInfo.lastSeenMs = System.currentTimeMillis();
        this.everHadPeer = true;
    }

    public PeerInfo getPeer(String string) {
        return this.peerMap.get(string);
    }

    public void removePeer(String string) {
        this.peerMap.remove(string);
    }

    public void clearPeers() {
        this.peerMap.clear();
    }

    public Collection<PeerInfo> getPeers() {
        return this.peerMap.values();
    }

    public int getPeerCount() {
        return this.peerMap.size();
    }

    public boolean hasEverHadPeer() {
        return this.everHadPeer;
    }

    public boolean hasRecentPeer(long l) {
        long l2 = System.currentTimeMillis();
        for (PeerInfo peerInfo : this.peerMap.values()) {
            if (l2 - peerInfo.lastSeenMs >= l) continue;
            return true;
        }
        return false;
    }

    public boolean isConnectionAttemptFailed() {
        return this.connectionAttemptFailed;
    }

    public void setConnectionAttemptFailed(boolean bl) {
        this.connectionAttemptFailed = bl;
    }

    public int getHostProtocolVersion() {
        return this.hostProtocolVersion;
    }

    public Set<String> getHostCapabilities() {
        return this.hostCapabilities;
    }

    public void setHostCapabilities(int n, Set<String> set) {
        this.hostProtocolVersion = n;
        this.hostCapabilities = set != null ? set : Collections.emptySet();
    }

    public boolean isHostLegacy() {
        return this.hostProtocolVersion == 0 || this.hostCapabilities.isEmpty();
    }

    public boolean hostSupportsRelay() {
        return this.hostCapabilities.contains("relay");
    }

    public RoomInfo(String string, String string2, boolean bl, int n, String string3, boolean bl2, int n2, String string4) {
        this.code = string;
        this.name = string2;
        this.hasPassword = bl;
        this.maxPlayers = n;
        this.token = string3;
        this.isHost = bl2;
        this.hostPort = n2;
        this.natType = string4;
        this.currentPlayers = new AtomicInteger(bl2 ? 1 : 0);
        this.bedrockPort = -1;
        this.serverProtocolVersion = 0;
        this.category = "other";
    }

    public String getCode() {
        return this.code;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String string) {
        this.name = string;
    }

    public boolean hasPassword() {
        return this.hasPassword;
    }

    public void setPassword(String string) {
        this.hasPassword = string != null && !string.isEmpty();
    }

    public int getMaxPlayers() {
        return this.maxPlayers;
    }

    public void setMaxPlayers(int n) {
        this.maxPlayers = n;
    }

    public String getToken() {
        return this.token;
    }

    public boolean isHost() {
        return this.isHost;
    }

    public int getHostPort() {
        return this.hostPort;
    }

    public String getNatType() {
        return this.natType;
    }

    public void setNatType(String string) {
        this.natType = string;
    }

    public String getHostIp() {
        return this.hostIp;
    }

    public void setHostIp(String string) {
        this.hostIp = string;
    }

    public String getHostIpv6() {
        return this.hostIpv6;
    }

    public void setHostIpv6(String string) {
        this.hostIpv6 = string;
    }

    public String getHostMappedIp() {
        InetSocketAddress inetSocketAddress = this.hostMappedAddress;
        return inetSocketAddress != null ? inetSocketAddress.getHostString() : null;
    }

    public int getHostMappedPort() {
        InetSocketAddress inetSocketAddress = this.hostMappedAddress;
        return inetSocketAddress != null ? inetSocketAddress.getPort() : 0;
    }

    public InetSocketAddress getHostMappedAddress() {
        return this.hostMappedAddress;
    }

    public void setHostMappedAddress(String string, int n) {
        this.hostMappedAddress = string != null && !string.isEmpty() ? new InetSocketAddress(string, n) : null;
    }

    public boolean isHostSymmetric() {
        return this.hostSymmetric;
    }

    public void setHostSymmetric(boolean bl) {
        this.hostSymmetric = bl;
    }

    public boolean isHostEasySym() {
        return this.hostEasySym;
    }

    public void setHostEasySym(boolean bl) {
        this.hostEasySym = bl;
    }

    public int getHostMappedPortDelta() {
        return this.hostMappedPortDelta;
    }

    public void setHostMappedPortDelta(int n) {
        this.hostMappedPortDelta = n;
    }

    public int getHostMappedPortRange() {
        return this.hostMappedPortRange;
    }

    public void setHostMappedPortRange(int n) {
        this.hostMappedPortRange = n;
    }

    public List<Integer> getHostBirthdayPorts() {
        return this.hostBirthdayPorts;
    }

    public void setHostBirthdayPorts(List<Integer> list) {
        this.hostBirthdayPorts = list;
    }

    public long getPunchSyncTimeMs() {
        return this.punchSyncTimeMs;
    }

    public void setPunchSyncTimeMs(long l) {
        this.punchSyncTimeMs = l;
    }

    public long getPunchSyncSentAtMs() {
        return this.punchSyncSentAtMs;
    }

    public void setPunchSyncSentAtMs(long l) {
        this.punchSyncSentAtMs = l;
    }

    public long getJoinerOfferRecvMs() {
        return this.joinerOfferRecvMs;
    }

    public void setJoinerOfferRecvMs(long l) {
        this.joinerOfferRecvMs = l;
    }

    public int getCurrentPlayers() {
        return this.currentPlayers.get();
    }

    public void setCurrentPlayers(int n) {
        this.currentPlayers.set(n);
    }

    public void incrementCurrentPlayers() {
        this.currentPlayers.incrementAndGet();
    }

    public void decrementCurrentPlayers() {
        this.currentPlayers.getAndUpdate(n -> Math.max(0, n - 1));
    }

    public String getClientId() {
        return this.clientId;
    }

    public void setClientId(String string) {
        this.clientId = string;
    }

    public int getBedrockPort() {
        return this.bedrockPort;
    }

    public void setBedrockPort(int n) {
        this.bedrockPort = n;
    }

    public int getServerProtocolVersion() {
        return this.serverProtocolVersion;
    }

    public void setServerProtocolVersion(int n) {
        this.serverProtocolVersion = n;
    }

    public String getCategory() {
        return this.category;
    }

    public void setCategory(String string) {
        this.category = string;
    }

    public String getAuthType() {
        return this.authType;
    }

    public void setAuthType(String string) {
        this.authType = string;
    }

    public int getPeerPort() {
        return this.peerPort;
    }

    public void setPeerPort(int n) {
        this.peerPort = n;
    }

    public String getClientType() {
        return this.clientType;
    }

    public void setClientType(String string) {
        this.clientType = string;
    }

    public String getLoader() {
        return this.loader;
    }

    public void setLoader(String string) {
        this.loader = string;
    }

    public String getConnectionMode() {
        return this.connectionMode;
    }

    public void setConnectionMode(String string) {
        this.connectionMode = string;
        this.usingRelay = false;
    }

    public void setConnectionMode(String string, boolean bl) {
        this.connectionMode = string;
        this.connectionFailed = bl;
        this.usingRelay = false;
    }

    public boolean isUsingRelay() {
        return this.usingRelay;
    }

    public void setUsingRelay(boolean bl) {
        this.usingRelay = bl;
    }

    public int getLocalBridgePort() {
        return this.localBridgePort;
    }

    public void setLocalBridgePort(int n) {
        this.localBridgePort = n;
    }

    public int getHostConnectPort() {
        return this.hostConnectPort;
    }

    public void setHostConnectPort(int n) {
        this.hostConnectPort = n;
    }

    public PortStatus getIpv4Status() {
        return this.ipv4Status;
    }

    public void setIpv4Status(PortStatus portStatus) {
        this.ipv4Status = portStatus;
    }

    public PortStatus getIpv6Status() {
        return this.ipv6Status;
    }

    public void setIpv6Status(PortStatus portStatus) {
        this.ipv6Status = portStatus;
    }

    public boolean isConnectionFailed() {
        return this.connectionFailed;
    }

    public void setConnectionFailed(boolean bl) {
        this.connectionFailed = bl;
    }

    public boolean isNameApproved() {
        return this.nameApproved;
    }

    public void setNameApproved(boolean bl) {
        this.nameApproved = bl;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public void setVisible(boolean bl) {
        this.visible = bl;
    }

    public boolean isSameCgnat() {
        return this.sameCgnat;
    }

    public void setSameCgnat(boolean bl) {
        this.sameCgnat = bl;
    }

    public boolean isGuestOp() {
        return this.guestOp;
    }

    public void setGuestOp(boolean bl) {
        this.guestOp = bl;
    }

    public String getGameType() {
        return this.gameType;
    }

    public void setGameType(String string) {
        this.gameType = string;
    }

    public boolean isHostOp() {
        return this.hostOp;
    }

    public void setHostOp(boolean bl) {
        this.hostOp = bl;
    }

    public String getHostLocalIp() {
        return this.hostLocalIp;
    }

    public void setHostLocalIp(String string) {
        this.hostLocalIp = string;
    }

    public String getJoinerLocalIp() {
        return this.joinerLocalIp;
    }

    public void setJoinerLocalIp(String string) {
        this.joinerLocalIp = string;
    }

    public String getMyMappedIp() {
        return this.myMappedIp;
    }

    public void setMyMappedIp(String string) {
        this.myMappedIp = string;
    }

    public int getMyMappedPort() {
        return this.myMappedPort;
    }

    public void setMyMappedPort(int n) {
        this.myMappedPort = n;
    }

    public String getTerracottaCode() {
        return this.terracottaCode;
    }

    public void setTerracottaCode(String string) {
        this.terracottaCode = string;
    }

    public static enum PortStatus {
        UNKNOWN("unknown"),
        REACHABLE("reachable"),
        UNREACHABLE("unreachable"),
        NO_ADDRESS("no_address");

        public final String label;

        private PortStatus(String string2) {
            this.label = string2;
        }
    }

    public static class PeerInfo {
        public final String clientId;
        public volatile String natType = "unknown";
        public volatile boolean relayEnabled = true;
        public volatile String mappedIp;
        public volatile int mappedPort;
        public volatile int protocolVersion = 0;
        public volatile Set<String> capabilities = Collections.emptySet();
        public volatile long lastSeenMs = System.currentTimeMillis();

        public PeerInfo(String string) {
            this.clientId = string;
        }
    }
}

