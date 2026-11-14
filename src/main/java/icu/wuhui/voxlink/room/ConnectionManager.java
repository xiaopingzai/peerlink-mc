/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 */
package icu.wuhui.voxlink.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.AddressBlacklist;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.FirewallBlockedException;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PortPredictor;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.network.PunchParams;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.PunchResult;
import icu.wuhui.voxlink.network.PunchStrategy;
import icu.wuhui.voxlink.network.PunchStrategySelector;
import icu.wuhui.voxlink.network.PunchTuner;
import icu.wuhui.voxlink.network.RelayBridge;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.network.ScenarioTier;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.UdpForwardBridge;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.room.ConnectionState;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.room.StunDetector;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public class ConnectionManager {
    private final RoomManager roomManager;
    private final SignalingClient signalingClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService punchExecutor;
    private final ConcurrentHashMap<String, UdpHolePuncher> activeHolePunchers = new ConcurrentHashMap();
    private final List<UdpForwardBridge> udpForwardBridges = new CopyOnWriteArrayList<UdpForwardBridge>();
    private final ConcurrentHashMap<String, ReliableUdpTransport> activeUdpTransports = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, ReliableUdpTransport> oldUdpTransports = new ConcurrentHashMap();
    private static final int ICE_POOL_RETAIN_SECONDS = 12;
    private static final int TCP_CONNECT_TIMEOUT_MS = 5000;
    private static final int SHORT_SLEEP_MS = 100;
    private static final int EXTRA_TIMEOUT_SEC = 5;
    private static final int AWAIT_TIMEOUT_SEC = 20;
    private static final int RELAY_GRACE_MS = 3000;
    private static final int MAX_DELAY_MS = 6000;
    private static final int PROBE_SOCKET_TIMEOUT_MS = 1000;
    private static final int MAX_FALLBACK_LOOPS = 200;
    private static final int FALLBACK_SLEEP_MS = 300;
    private static final int MAX_RELAY_CANDIDATES = 3;
    private static final int SHORT_TIMEOUT_SEC = 8;
    private static final int RELAY_SETUP_TIMEOUT_SEC = 15;
    private static final int POLL_INTERVAL_MS = 500;
    private static final int AWAIT_TERM_SEC = 2;
    private static final int REVERSE_PUNCH_TIMEOUT_SEC = 20;
    private static final int STUN_PROBE_TIMEOUT_SEC = 10;
    private static final int RTT_SYNC_MAX_DELAY_MS = 8000;
    private static final int HOST_MAPPED_RESYNC_MS = 3000;
    private static final byte PUNCH_ACK_TYPE = 2;
    private static final long RELAY_FAILURE_WINDOW_MS = 60000L;
    private final Map<String, Long> failedRelayPeers = new ConcurrentHashMap<String, Long>();
    private final AtomicReference<String> currentRelayPeer = new AtomicReference<Object>(null);
    private volatile ScheduledFuture<?> relayFailoverTask = null;
    private volatile int nextRelayEligibleRound = 0;
    private volatile boolean manualRelayInProgress = false;
    private final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);
    private final AtomicBoolean reversePunchAttempted = new AtomicBoolean(false);
    private final AtomicBoolean connectionWon = new AtomicBoolean(false);
    private final List<ConnectionFallback> activeFallbacks = new CopyOnWriteArrayList<ConnectionFallback>();
    private static final int CONTINUOUS_RETRY_MAX_ROUNDS = Integer.MAX_VALUE;
    private final AtomicBoolean continuousRetryCancelled = new AtomicBoolean(false);
    private final AtomicInteger continuousRetryRound = new AtomicInteger(0);
    private volatile PunchFailureClassifier.FailureReason lastFailureReason;
    private final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);
    private static final int ICE_RESTART_MAX_ATTEMPTS = 3;
    private static final long ICE_RESTART_COOLDOWN_MS = 5000L;
    private final AtomicInteger iceRestartAttempts = new AtomicInteger(0);
    private final AtomicLong lastIceRestartTimeMs = new AtomicLong(0L);
    private volatile RoomManager.RoomState savedConnectionState;
    private volatile String savedConnectionFrom = "";
    private volatile String savedConnectionHostIpv6;
    private volatile String savedConnectionHostIp;
    private volatile int savedConnectionHostPort;
    private volatile String savedConnectionHostMappedIp;
    private volatile int savedConnectionHostMappedPort;
    private volatile ScheduledFuture<?> connectionTimeoutFuture;
    private volatile ScheduledFuture<?> connectionCycleSafetyTimeout;
    private volatile long connectionStartTimeMs;
    private volatile int connectionTimeoutSec;
    private volatile StunProbe.ProbeResult stunProbeResult;
    private final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference();
    private volatile String lastPunchInfoId = "";
    private volatile List<Integer> lastHostMappedPorts;
    private volatile boolean hostPunching = false;
    private static final int TARGET_CHANGE_IGNORE_MS = 3000;
    private volatile long lastPunchStartMs = 0L;
    private volatile boolean relayConnectedSignaled = false;
    private static final int RELAY_REGISTRATION_RENEWAL_SEC = 60;
    private volatile CompletableFuture<Void> dualVoxlinkBridgeFuture;
    private static final int DUAL_VOXLINK_BRIDGE_TIMEOUT_SEC = 120;
    private volatile boolean dualRaceActive = false;
    private volatile boolean terracottaWon = false;
    private volatile boolean voxlinkWon = false;
    private volatile boolean voxlinkSideDisabled = false;
    private volatile CompletableFuture<Void> dualResultRef;
    private final AtomicInteger dualFailedCount = new AtomicInteger(0);
    private final AtomicBoolean dualRaceWon = new AtomicBoolean(false);
    private static final long HANDOFF_GRACE_MS = 5000L;
    private volatile long connectionEstablishedAtMs = 0L;
    private volatile PunchResult lastPunchResult;
    private volatile NatClass localNatClass = NatClass.UNKNOWN;
    private volatile NatClass remoteNatClass = NatClass.UNKNOWN;
    private volatile ScenarioTier.Tier scenarioTier = ScenarioTier.Tier.NORMAL;
    private volatile PunchProfile activePunchProfile = PunchProfile.DEFAULT;
    private volatile PunchParams activePunchParams;
    private volatile CompletableFuture<Void> relayPrefetchFuture;
    private static final int PUNCH_INFO_WAIT_TIMEOUT_S = 15;
    private static final long[] BACKOFF_DELAYS_MS = new long[]{1000L, 2000L, 4000L};
    private static final int JOIN_QUEUE_RETRY_SEC = 10;
    private final AddressBlacklist addressBlacklist = new AddressBlacklist();
    private volatile UdpSocketArray cachedUdpArray;
    private static final long UDP_ARRAY_REUSE_WINDOW_MS = 30000L;
    private static volatile ConnectionManager instance;
    private static final long PEER_SIGNAL_FRESH_MS = 60000L;
    private volatile long lastProfileSwitchMs = 0L;

    private boolean isRelayPeerFailed(String string) {
        Long l = this.failedRelayPeers.get(string);
        if (l == null) {
            return false;
        }
        if (System.currentTimeMillis() - l > 60000L) {
            this.failedRelayPeers.remove(string, l);
            return false;
        }
        return true;
    }

    private ConnectionFallback trackFallback(ConnectionFallback connectionFallback) {
        this.activeFallbacks.add(connectionFallback);
        return connectionFallback;
    }

    private void cancelAllFallbacks() {
        for (ConnectionFallback connectionFallback : this.activeFallbacks) {
            try {
                connectionFallback.cancel();
            }
            catch (Exception exception) {}
        }
        this.activeFallbacks.clear();
    }

    private UdpSocketArray getOrCreateUdpArray(int n, boolean bl, List<String> arrayList) {
        UdpSocketArray udpSocketArray;
        Object object;
        int n2;
        long l = System.currentTimeMillis();
        if (this.cachedUdpArray != null) {
            if (this.cachedUdpArray.isReusable(n, bl, l)) {
                VoxLinkMod.LOGGER.info("[BirthdayPunch] Reuse cached socket array: {} sockets, age={}ms", (Object)this.cachedUdpArray.punchers.size(), (Object)(l - this.cachedUdpArray.createTime));
                return this.cachedUdpArray;
            }
            this.cachedUdpArray.close();
            this.cachedUdpArray = null;
        }
        ArrayList<UdpHolePuncher> arrayList2 = new ArrayList<UdpHolePuncher>();
        ArrayList<StunProbe.PublicMappedAddress> arrayList3 = new ArrayList<StunProbe.PublicMappedAddress>();
        ArrayList<CompletableFuture<Object[]>> arrayList4 = new ArrayList<CompletableFuture<Object[]>>();
        int n3 = Math.max(1, Math.min(this.punchProfile().socketStunCount, arrayList.size()));
        ArrayList arrayList5 = arrayList.size() > n3 ? new ArrayList(arrayList.subList(0, n3)) : arrayList;
        int n4 = Math.max(0, this.punchProfile().socketCreateIntervalMs);
        for (n2 = 0; n2 < n; ++n2) {
            int n5 = n2;
            object = new UdpHolePuncher();
            this.applyPunchTemplate((UdpHolePuncher)object);
            try {
                ((UdpHolePuncher)object).createSocket();
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[BirthdayPunch] Create socket #{} failed: {}", (Object)n5, (Object)exception.getMessage());
                try {
                    ((UdpHolePuncher)object).close();
                }
                catch (Exception exception2) {}
                continue;
            }
            arrayList4.add(CompletableFuture.supplyAsync(() -> ConnectionManager.lambda$getOrCreateUdpArray$0((UdpHolePuncher)object, arrayList5)));
            if (n4 <= 0) continue;
            try {
                Thread.sleep(n4);
                continue;
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        n2 = Math.min(this.punchProfile().birthdaySocketCount, n);
        AtomicInteger atomicInteger = new AtomicInteger(0);
        object = new AtomicInteger(0);
        CompletableFuture completableFuture = new CompletableFuture();
        for (CompletableFuture completableFuture2 : arrayList4) {
            completableFuture2.whenComplete((arg_0, arg_1) -> ConnectionManager.lambda$getOrCreateUdpArray$1((AtomicInteger)object, atomicInteger, n2, arrayList4, completableFuture, arg_0, arg_1));
        }
        long l2 = Math.max(5000L, (long)n * (long)n4 + 3000L);
        try {
            completableFuture.get(l2, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException timeoutException) {
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException executionException) {
            // empty catch block
        }
        for (int i = 0; i < arrayList4.size(); ++i) {
            try {
                Object[] exception = ((CompletableFuture)arrayList4.get(i)).getNow(null);
                if (exception == null) continue;
                UdpHolePuncher udpHolePuncher = (UdpHolePuncher)exception[0];
                StunProbe.PublicMappedAddress publicMappedAddress = (StunProbe.PublicMappedAddress)exception[1];
                if (udpHolePuncher.getSocket() != null && !udpHolePuncher.getSocket().isClosed() && publicMappedAddress != null) {
                    arrayList2.add(udpHolePuncher);
                    arrayList3.add(publicMappedAddress);
                    continue;
                }
                if (udpHolePuncher.getSocket() == null || udpHolePuncher.getSocket().isClosed()) continue;
                try {
                    udpHolePuncher.close();
                }
                catch (Exception exception2) {}
                continue;
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        for (CompletableFuture completableFuture2 : arrayList4) {
            if (completableFuture2.isDone()) continue;
            completableFuture2.cancel(true);
        }
        if (arrayList2.isEmpty()) {
            return null;
        }
        this.cachedUdpArray = udpSocketArray = new UdpSocketArray(arrayList2, arrayList3, bl);
        VoxLinkMod.LOGGER.info("[BirthdayPunch] Create new socket array: {} sockets, easySym={}", (Object)arrayList2.size(), (Object)bl);
        return udpSocketArray;
    }

    static int calculatePortDelta(List<Integer> list) {
        if (list != null && list.size() >= 2) {
            int n;
            int n2;
            ArrayList<Integer> arrayList = new ArrayList<Integer>();
            for (n2 = 1; n2 < list.size(); ++n2) {
                arrayList.add(list.get(n2) - list.get(n2 - 1));
            }
            Collections.sort(arrayList);
            n2 = arrayList.size() / 4;
            List list2 = arrayList.subList(n2, arrayList.size() - n2);
            double d = ((Integer)list2.get(0)).intValue();
            double d2 = 0.4;
            for (n = 1; n < list2.size(); ++n) {
                d += d2 * ((double)((Integer)list2.get(n)).intValue() - d);
            }
            n = (int)Math.round(d);
            return n > 0 ? n : 1;
        }
        return 1;
    }

    public static ConnectionManager getInstance() {
        return instance;
    }

    public ConnectionManager(RoomManager roomManager, SignalingClient signalingClient, ScheduledExecutorService scheduledExecutorService) {
        this.roomManager = roomManager;
        this.signalingClient = signalingClient;
        this.scheduler = scheduledExecutorService;
        instance = this;
        this.punchExecutor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-HostPunch");
            thread.setDaemon(true);
            return thread;
        });
    }

    private PunchProfile punchProfile() {
        PunchProfile punchProfile = this.activePunchProfile;
        return punchProfile != null ? punchProfile : PunchProfile.DEFAULT;
    }

    public PunchProfile getActivePunchProfile() {
        return this.punchProfile();
    }

    private void applyPunchTemplate(UdpHolePuncher udpHolePuncher) {
        if (udpHolePuncher != null) {
            udpHolePuncher.setProfile(this.punchProfile());
            udpHolePuncher.setPunchParams(this.activePunchParams);
        }
    }

    private void switchPunchProfile(PunchProfile punchProfile, String string) {
        if (punchProfile != null && punchProfile != this.activePunchProfile) {
            long l = System.currentTimeMillis();
            if (this.lastProfileSwitchMs != 0L && l - this.lastProfileSwitchMs < 20000L) {
                return;
            }
            this.lastProfileSwitchMs = l;
            PunchProfile punchProfile2 = this.activePunchProfile;
            this.activePunchProfile = punchProfile;
            VoxLinkMod.LOGGER.info("[PunchProfile] Instance switch: {} -> {} reason: {}", new Object[]{punchProfile2, punchProfile.name, string});
        }
    }

    private int punchTimeoutMs() {
        PunchParams punchParams = this.activePunchParams;
        return punchParams != null && punchParams.timeoutMs > 0 ? punchParams.timeoutMs : this.punchProfile().punchTimeoutMs;
    }

    private boolean punchSkipDirect() {
        PunchParams punchParams = this.activePunchParams;
        return punchParams != null && punchParams.skipDirectPunch;
    }

    public boolean isConnectionCycleActive() {
        return this.connectionCycleActive.get();
    }

    public boolean isConnectionActive() {
        return this.connectionCycleActive.get() || this.hostPunching || this.connectionWon.get() || this.continuousRetryRound.get() > 0;
    }

    public StunProbe.ProbeResult getStunProbeResult() {
        return this.stunProbeResult;
    }

    public NatClass getLocalNatClass() {
        return this.localNatClass;
    }

    public NatClass getRemoteNatClass() {
        return this.remoteNatClass;
    }

    public String getConnectionDifficultyKey() {
        NatClass natClass = this.localNatClass;
        NatClass natClass2 = this.remoteNatClass;
        if (natClass == NatClass.UNKNOWN || natClass2 == NatClass.UNKNOWN) {
            NatClass natClass3 = natClass == NatClass.UNKNOWN ? natClass2 : natClass;
            NatClass natClass4 = natClass3;
            if (natClass3 == NatClass.CONE) {
                return "voxlink.nat.diff_medium";
            }
            if (natClass3.isSymmetric()) {
                return "voxlink.nat.diff_hell";
            }
            return "voxlink.nat.diff_medium";
        }
        if (natClass == NatClass.CONE && natClass2 == NatClass.CONE) {
            return "voxlink.nat.diff_easy";
        }
        if (natClass == NatClass.HARD_SYM && natClass2 == NatClass.HARD_SYM) {
            return "voxlink.nat.diff_hell";
        }
        if (natClass == NatClass.HARD_SYM || natClass2 == NatClass.HARD_SYM) {
            return "voxlink.nat.diff_hard";
        }
        if (natClass.isSymmetric() && natClass2.isSymmetric()) {
            return "voxlink.nat.diff_hard";
        }
        if (natClass.isSymmetric() || natClass2.isSymmetric()) {
            return "voxlink.nat.diff_medium";
        }
        return "voxlink.nat.diff_medium";
    }

    public void setStunProbeResult(StunProbe.ProbeResult probeResult) {
        this.stunProbeResult = probeResult;
    }

    public AtomicReference<CompletableFuture<StunProbe.ProbeResult>> getStunProbeFutureRef() {
        return this.stunProbeFutureRef;
    }

    public void setConnectionCycleActive(boolean bl) {
        this.connectionCycleActive.set(bl);
    }

    private void scheduleConnectionCycleSafety(RoomManager.RoomState roomState) {
        if (this.connectionCycleSafetyTimeout != null) {
            this.connectionCycleSafetyTimeout.cancel(false);
        }
        this.connectionCycleSafetyTimeout = this.scheduler.schedule(() -> {
            if (this.connectionCycleActive.get() && !this.connectionWon.get() && this.roomManager.currentRoom.get() == roomState) {
                if (this.isPersistentRetrying()) {
                    VoxLinkMod.LOGGER.info("[Connection] Safety timeout skipped: persistent retrying round={}", (Object)this.continuousRetryRound.get());
                    return;
                }
                VoxLinkMod.LOGGER.warn("[Connection] Safety timeout (120s), connection cycle stuck, auto-reset");
                this.connectionCycleActive.set(false);
                this.showConnectFailed(roomState, "voxlink.connection.cycle_safety_timeout");
            }
        }, 120L, TimeUnit.SECONDS);
    }

    private void cancelConnectionCycleSafety() {
        if (this.connectionCycleSafetyTimeout != null) {
            this.connectionCycleSafetyTimeout.cancel(false);
            this.connectionCycleSafetyTimeout = null;
        }
    }

    public void setReversePunchAttempted(boolean bl) {
        this.reversePunchAttempted.set(bl);
    }

    public void clearActiveHolePunchers() {
        for (String string : new ArrayList(this.activeHolePunchers.keySet())) {
            UdpHolePuncher udpHolePuncher = this.activeHolePunchers.remove(string);
            if (udpHolePuncher == null) continue;
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.debug("cleanup puncher close error: {}", (Object)exception.getMessage());
            }
        }
        this.activeHolePunchers.clear();
    }

    public void clearHostPunchingState() {
        this.hostPunching = false;
        UdpHolePuncher udpHolePuncher = this.activeHolePunchers.remove("host");
        if (udpHolePuncher != null) {
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.debug("host puncher close error: {}", (Object)exception.getMessage());
            }
        }
    }

    public void stopAllPunchingAfterHostBridge() {
        this.continuousRetryCancelled.set(true);
        this.connectionCycleActive.set(false);
        this.connectionWon.set(true);
        this.cancelAllFallbacks();
        if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
            this.connectionTimeoutFuture = null;
        }
        for (UdpHolePuncher udpHolePuncher : this.activeHolePunchers.values()) {
            try {
                udpHolePuncher.cancel();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                udpHolePuncher.stopPunch();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception) {}
        }
        this.activeHolePunchers.clear();
        this.hostPunching = false;
        this.lastPunchInfoId = "";
        if (this.dualRaceActive && !this.terracottaWon) {
            this.killAllConnectionAttempts("terracotta");
        }
    }

    private void stopAllPunchingForRelay() {
        this.connectionCycleActive.set(false);
        if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
            this.connectionTimeoutFuture = null;
        }
        for (UdpHolePuncher udpHolePuncher : this.activeHolePunchers.values()) {
            try {
                udpHolePuncher.cancel();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                udpHolePuncher.stopPunch();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception) {}
        }
        this.activeHolePunchers.clear();
        this.hostPunching = false;
        this.lastPunchInfoId = "";
    }

    public boolean canShowRelayButton() {
        if (!VoxLinkMod.getConfig().isRelayEnabled()) {
            return false;
        }
        if (this.manualRelayInProgress) {
            return false;
        }
        if (this.isLegacyPeer()) {
            return false;
        }
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            int n = this.continuousRetryRound.get();
            if (n < 2) {
                return false;
            }
            if (this.nextRelayEligibleRound == 0) {
                boolean bl = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
                this.nextRelayEligibleRound = bl ? 2 : 3;
            }
            return n >= this.nextRelayEligibleRound;
        }
        return false;
    }

    public void triggerManualRelay() {
        RoomManager.RoomState roomState;
        if (VoxLinkMod.getConfig().isRelayEnabled() && (roomState = this.roomManager.currentRoom.get()) != null && roomState != RoomManager.PENDING && !this.manualRelayInProgress) {
            this.manualRelayInProgress = true;
            int n = this.continuousRetryRound.get();
            boolean bl = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
            this.nextRelayEligibleRound = n + (bl ? 2 : 4);
            VoxLinkMod.LOGGER.info("[Relay] Manual relay triggered at round={}, next eligible round={}", (Object)n, (Object)this.nextRelayEligibleRound);
            this.stopAllPunchingForRelay();
            roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.relay.trying", (Object[])new Object[0]));
            this.tryRelay(roomState);
        }
    }

    public boolean isManualRelayInProgress() {
        return this.manualRelayInProgress;
    }

    public int getContinuousRetryRound() {
        return this.continuousRetryRound.get();
    }

    public boolean isPersistentRetrying() {
        return this.continuousRetryRound.get() > 0 && !this.continuousRetryCancelled.get();
    }

    public boolean isDualRaceActive() {
        return this.dualRaceActive;
    }

    private void notifyRelayFailed() {
        if (this.manualRelayInProgress) {
            this.manualRelayInProgress = false;
            VoxLinkMod.LOGGER.info("[Relay] Manual relay failed, resume hole punching");
        }
    }

    public void stopRelay() {
        VoxLinkMod.LOGGER.info("[Relay] stopRelay: cleanup relay state");
        this.manualRelayInProgress = false;
        this.clearRelayTracking();
        RelayBridge.getInstance(this.scheduler).stopAllRelays();
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isUsingRelay()) {
            roomState.roomInfo.setUsingRelay(false);
            ReliableUdpTransport reliableUdpTransport = this.activeUdpTransports.remove("relay_cone");
            if (reliableUdpTransport != null) {
                try {
                    reliableUdpTransport.close();
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
            P2PBridge.disconnect();
        }
    }

    public void clearActiveUdpTransports() {
        for (ReliableUdpTransport reliableUdpTransport : this.activeUdpTransports.values()) {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.debug("cleanup udp transport close error: {}", (Object)exception.getMessage());
            }
        }
        this.activeUdpTransports.clear();
    }

    public void stopAllConnectionWork() {
        this.hostPunching = false;
        this.lastPunchInfoId = "";
        this.cancelAllFallbacks();
        for (UdpHolePuncher object : this.activeHolePunchers.values()) {
            try {
                object.cancel();
                object.close();
            }
            catch (Exception exception) {}
        }
        this.activeHolePunchers.clear();
        for (ReliableUdpTransport reliableUdpTransport : this.activeUdpTransports.values()) {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {}
        }
        this.activeUdpTransports.clear();
    }

    public UdpHolePuncher removeHolePuncher(String string) {
        return this.activeHolePunchers.remove(string);
    }

    public ReliableUdpTransport removeUdpTransport(String string) {
        return this.activeUdpTransports.remove(string);
    }

    public boolean hasUdpTransport(String string) {
        return string != null && this.activeUdpTransports.containsKey(string);
    }

    public void handleJoinRequest(String string, JsonObject jsonObject) {
        this.handleJoinRequest(string, jsonObject, 0);
    }

    public void handleJoinRequest(String string, JsonObject jsonObject, int n) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        VoxLinkMod.LOGGER.info("[RoomManager] Received join_request from {}, state={}", (Object)string, (Object)(roomState != null && roomState != RoomManager.PENDING ? "active" : "null/pending"));
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isHost()) {
            HashSet<String> hashSet;
            int n2;
            if (jsonObject.has("clientProtocolVersion") && !jsonObject.get("clientProtocolVersion").isJsonNull()) {
                n2 = jsonObject.get("clientProtocolVersion").getAsInt();
                hashSet = Collections.emptySet();
                if (jsonObject.has("clientCapabilities") && jsonObject.get("clientCapabilities").isJsonArray()) {
                    hashSet = new HashSet<String>();
                    for (JsonElement jsonElement : jsonObject.getAsJsonArray("clientCapabilities")) {
                        if (jsonElement.isJsonNull()) continue;
                        hashSet.add(jsonElement.getAsString());
                    }
                }
                roomState.roomInfo.addOrUpdatePeer(string, null, null, 0, n2, hashSet);
                VoxLinkMod.LOGGER.info("[handleJoinRequest] joiner={} capability: v{} caps={}", new Object[]{string, n2, hashSet});
            }
            if (!this.hostPunching && !this.activeHolePunchers.containsKey("host")) {
                hashSet = roomState.roomInfo.getHostIp();
                String string2 = roomState.roomInfo.getHostIpv6();
                boolean bl = hashSet == null || ((String)((Object)hashSet)).isEmpty();
                n2 = string2 == null || string2.isEmpty() ? 1 : 0;
                int n3 = n2;
                if (!bl && n2 == 0) {
                    this.sendHolepunchOffer(roomState, string);
                } else {
                    ((CompletableFuture)this.signalingClient.getPublicIp().thenAccept(apiResponse -> {
                        if (apiResponse.success && apiResponse.data != null) {
                            RoomManager.RoomState roomState2 = this.roomManager.currentRoom.get();
                            if (roomState2 != null && roomState2 != RoomManager.PENDING && roomState2.roomInfo.isHost()) {
                                String string2;
                                if (apiResponse.data.has("ip") && !apiResponse.data.get("ip").isJsonNull() && (roomState2.roomInfo.getHostIp() == null || roomState2.roomInfo.getHostIp().isEmpty())) {
                                    roomState2.roomInfo.setHostIp(apiResponse.data.get("ip").getAsString());
                                }
                                if (apiResponse.data.has("ipv6") && !apiResponse.data.get("ipv6").isJsonNull() && (roomState2.roomInfo.getHostIpv6() == null || roomState2.roomInfo.getHostIpv6().isEmpty())) {
                                    roomState2.roomInfo.setHostIpv6(apiResponse.data.get("ipv6").getAsString());
                                }
                                if ((roomState2.roomInfo.getHostIpv6() == null || roomState2.roomInfo.getHostIpv6().isEmpty()) && StunDetector.verifyIPv6Connectivity() && (string2 = ConnectionFallback.getLocalGlobalIpv6()) != null) {
                                    roomState2.roomInfo.setHostIpv6(string2);
                                    VoxLinkMod.LOGGER.info("[handleJoinRequest] API returned no IPv6, using local IPv6: {}", (Object)string2);
                                }
                                this.sendHolepunchOffer(roomState2, string);
                            } else {
                                VoxLinkMod.LOGGER.warn("[RoomManager] Room state changed during IP query, use original state for offer");
                                this.sendHolepunchOffer(roomState, string);
                            }
                        } else {
                            this.sendHolepunchOffer(roomState, string);
                        }
                    })).exceptionally(object -> {
                        VoxLinkMod.LOGGER.warn("[RoomManager] Get public IP failed in handleJoinRequest: {}", (Object)((Throwable)object).getMessage());
                        this.sendHolepunchOffer(roomState, string);
                        return null;
                    });
                }
            } else if (n >= 3) {
                VoxLinkMod.LOGGER.warn("[RoomManager] join_request retry exhausted, discard {}", (Object)string);
            } else if (this.activeUdpTransports.containsKey(string)) {
                VoxLinkMod.LOGGER.info("[RoomManager] Client {} has active transport, ignore duplicate join_request", (Object)string);
            } else {
                VoxLinkMod.LOGGER.info("[RoomManager] Punching in progress, queue join_request from {} (retry {}/3)", (Object)string, (Object)(n + 1));
                n2 = n + 1;
                this.scheduler.schedule(() -> {
                    RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
                    if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isHost()) {
                        if (this.activeUdpTransports.containsKey(string)) {
                            VoxLinkMod.LOGGER.info("[RoomManager] Client {} connected, skip queued retry", (Object)string);
                        } else {
                            VoxLinkMod.LOGGER.info("[RoomManager] Retry queued join_request from {}", (Object)string);
                            this.handleJoinRequest(string, jsonObject, n2);
                        }
                    }
                }, 10L, TimeUnit.SECONDS);
            }
        }
    }

    public void sendHolepunchOffer(RoomManager.RoomState roomState, String string) {
        int n;
        Object object2;
        UdpHolePuncher udpHolePuncher;
        int n2;
        this.connectionWon.set(false);
        JsonObject jsonObject = new JsonObject();
        if (roomState.roomInfo.getHostIp() != null && !roomState.roomInfo.getHostIp().isEmpty()) {
            jsonObject.addProperty("hostIp", roomState.roomInfo.getHostIp());
        }
        if (roomState.roomInfo.getHostIpv6() != null && !roomState.roomInfo.getHostIpv6().isEmpty()) {
            jsonObject.addProperty("hostIpv6", roomState.roomInfo.getHostIpv6());
        }
        int n3 = (n2 = P2PBridge.getHostPort()) > 0 ? n2 : roomState.roomInfo.getHostPort();
        jsonObject.addProperty("hostPort", (Number)n3);
        String string2 = StunDetector.getLocalIpAddress();
        if (string2 != null && !string2.isEmpty()) {
            jsonObject.addProperty("hostLocalIp", string2);
            roomState.roomInfo.setHostLocalIp(string2);
            VoxLinkMod.LOGGER.info("[RoomManager] Include host LAN IP: {}", (Object)string2);
        }
        if ((udpHolePuncher = this.activeHolePunchers.get("host")) != null && udpHolePuncher.getSocket() != null && !udpHolePuncher.getSocket().isClosed()) {
            udpHolePuncher.stopPunch();
            VoxLinkMod.LOGGER.info("[RoomManager] Reuse existing host punch socket (localPort={})", (Object)udpHolePuncher.getSocket().getLocalPort());
        } else {
            udpHolePuncher = new UdpHolePuncher();
            this.applyPunchTemplate(udpHolePuncher);
            try {
                int n4 = roomState.roomInfo.getHostPort();
                udpHolePuncher.createSocket(n4);
                this.activeHolePunchers.put("host", udpHolePuncher);
            }
            catch (Exception exception) {
                try {
                    udpHolePuncher.createSocket();
                    this.activeHolePunchers.put("host", udpHolePuncher);
                }
                catch (Exception exception2) {
                    VoxLinkMod.LOGGER.warn("[RoomManager] Create host punch socket failed: {}", (Object)exception2.getMessage());
                    udpHolePuncher = null;
                }
            }
        }
        String string3 = roomState.roomInfo.getNatType();
        int n5 = StunDetector.isNatTypeSymmetric(string3);
        if (this.stunProbeResult != null && roomState.roomInfo.getClientId() != null) {
            object2 = null;
            n = 0;
            for (StunProbe.StunServerResult object3 : this.stunProbeResult.serverResults) {
                if (!object3.reachable || object3.mappedIp == null || object3.mappedPort <= 0) continue;
                object2 = object3.mappedIp;
                n = object3.mappedPort;
                break;
            }
            if (object2 != null && n > 0) {
                boolean bl = VoxLinkMod.getConfig().isRelayEnabled();
                this.signalingClient.registerRelayPeer(roomState.roomInfo.getClientId(), roomState.roomInfo.getCode(), this.stunProbeResult.natType.key, (String)object2, n, bl);
                this.scheduleRelayRegistrationRenewal(roomState, this.stunProbeResult.natType.key, (String)object2, n);
            }
        }
        object2 = udpHolePuncher;
        n = n5;
        String string4 = string3;
        JsonObject jsonObject2 = jsonObject;
        RoomManager.RoomState roomState2 = roomState;
        String string5 = string;
        int n4 = n3;
        CompletableFuture.supplyAsync(() -> this.lambda$sendHolepunchOffer$8((UdpHolePuncher)object2, string4, n != 0)).thenAccept(arg_0 -> this.lambda$sendHolepunchOffer$13(n != 0, string4, jsonObject2, (UdpHolePuncher)object2, roomState2, string5, n4, arg_0));
    }

    public void handleHolePunchOffer(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        VoxLinkMod.LOGGER.info("[RoomManager] Received holepunch_offer, state={}", (Object)(roomState != null && roomState != RoomManager.PENDING ? "active" : "null/pending"));
        if (roomState != null && roomState != RoomManager.PENDING && !roomState.roomInfo.isHost()) {
            if (this.voxlinkSideDisabled || this.terracottaWon) {
                VoxLinkMod.LOGGER.info("[DualP2P] VoxLink disabled or Terracotta won, ignore holepunch_offer");
            } else if (this.connectionWon.get() && P2PBridge.isRunning()) {
                VoxLinkMod.LOGGER.info("[RoomManager] Already connected, ignore holepunch_offer");
            } else if (ConnectionHelper.isConnecting() && this.connectionCycleActive.get()) {
                VoxLinkMod.LOGGER.info("[RoomManager] Already connecting with active cycle, ignore holepunch_offer");
            } else if (!this.connectionCycleActive.compareAndSet(false, true)) {
                VoxLinkMod.LOGGER.info("[RoomManager] Connection cycle in progress, ignore duplicate holepunch_offer");
            } else {
                CompletableFuture<StunProbe.ProbeResult> completableFuture;
                String string2;
                this.connectionWon.set(false);
                this.scheduleConnectionCycleSafety(roomState);
                if (P2PBridge.isRunning()) {
                    int n = P2PBridge.getJoinerPort();
                    string2 = jsonObject.has("hostIp") && !jsonObject.get("hostIp").isJsonNull() ? jsonObject.get("hostIp").getAsString() : roomState.roomInfo.getHostIp();
                    String string3 = string2;
                    if (n > 0 && P2PBridge.isTargetMatch(string2, roomState.roomInfo.getHostPort())) {
                        VoxLinkMod.LOGGER.info("[RoomManager] Bridge running with same target, ignore duplicate holepunch_offer");
                        this.connectionCycleActive.set(false);
                        ConnectionHelper.resetConnecting();
                        return;
                    }
                    P2PBridge.disconnect();
                }
                string2 = null;
                if (jsonObject.has("hostIp") && !jsonObject.get("hostIp").isJsonNull() && !jsonObject.get("hostIp").getAsString().isEmpty()) {
                    string2 = jsonObject.get("hostIp").getAsString();
                }
                String string4 = null;
                int n = roomState.roomInfo.getHostPort();
                if (jsonObject.has("hostPort") && !jsonObject.get("hostPort").isJsonNull()) {
                    n = jsonObject.get("hostPort").getAsInt();
                }
                if (jsonObject.has("hostIpv6") && !jsonObject.get("hostIpv6").isJsonNull() && !jsonObject.get("hostIpv6").getAsString().isEmpty()) {
                    string4 = jsonObject.get("hostIpv6").getAsString();
                }
                String string5 = null;
                int n2 = 0;
                if (jsonObject.has("hostMappedIp") && !jsonObject.get("hostMappedIp").isJsonNull()) {
                    string5 = jsonObject.get("hostMappedIp").getAsString();
                }
                if (jsonObject.has("hostMappedPort") && !jsonObject.get("hostMappedPort").isJsonNull()) {
                    n2 = jsonObject.get("hostMappedPort").getAsInt();
                }
                if (string5 != null && !string5.isEmpty() && n2 > 0) {
                    roomState.roomInfo.setHostMappedAddress(string5, n2);
                    VoxLinkMod.LOGGER.info("[RoomManager] Update roomInfo hostMapped={}:{}", (Object)string5, (Object)n2);
                }
                String string6 = null;
                if (jsonObject.has("hostLocalIp") && !jsonObject.get("hostLocalIp").isJsonNull()) {
                    string6 = jsonObject.get("hostLocalIp").getAsString();
                    roomState.roomInfo.setHostLocalIp(string6);
                    VoxLinkMod.LOGGER.info("[RoomManager] Host provided LAN IP: {}", (Object)string6);
                }
                String string7 = string2;
                String string8 = string4;
                int n3 = n;
                String string9 = string5;
                int n4 = n2;
                boolean bl = jsonObject.has("hostSymmetric") && !jsonObject.get("hostSymmetric").isJsonNull() && jsonObject.get("hostSymmetric").getAsBoolean();
                boolean bl2 = jsonObject.has("hostEasySym") && !jsonObject.get("hostEasySym").isJsonNull() && jsonObject.get("hostEasySym").getAsBoolean();
                int n5 = jsonObject.has("hostMappedPortDelta") && !jsonObject.get("hostMappedPortDelta").isJsonNull() ? jsonObject.get("hostMappedPortDelta").getAsInt() : 0;
                int n6 = jsonObject.has("hostMappedPortRange") && !jsonObject.get("hostMappedPortRange").isJsonNull() ? jsonObject.get("hostMappedPortRange").getAsInt() : 100;
                int n7 = n6;
                if (n6 != 100) {
                    roomState.roomInfo.setHostMappedPortRange(n6);
                }
                long l = jsonObject.has("punchSyncTimeMs") ? jsonObject.get("punchSyncTimeMs").getAsLong() : 0L;
                long l2 = l;
                if (l > 0L) {
                    roomState.roomInfo.setPunchSyncTimeMs(l);
                    VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={} ({}ms ago)", (Object)l, (Object)(l - System.currentTimeMillis()));
                }
                if (jsonObject.has("punchSyncSentAtMs")) {
                    roomState.roomInfo.setJoinerOfferRecvMs(System.currentTimeMillis());
                }
                ArrayList<Integer> arrayList = null;
                if (jsonObject.has("hostBirthdayPorts") && jsonObject.get("hostBirthdayPorts").isJsonArray()) {
                    arrayList = new ArrayList<Integer>();
                    for (Object object : jsonObject.getAsJsonArray("hostBirthdayPorts")) {
                        arrayList.add(object.getAsInt());
                    }
                    VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer contains {} birthday ports", (Object)arrayList.size());
                }
                ArrayList<Integer> arrayList2 = arrayList;
                if (string2 != null && !string2.isEmpty()) {
                    roomState.roomInfo.setHostIp(string2);
                }
                if (string4 != null && !string4.isEmpty()) {
                    roomState.roomInfo.setHostIpv6(string4);
                }
                if (n > 0) {
                    roomState.roomInfo.setHostConnectPort(n);
                }
                if (bl2) {
                    roomState.roomInfo.setHostEasySym(true);
                }
                if (arrayList2 != null) {
                    roomState.roomInfo.setHostBirthdayPorts((List<Integer>)arrayList2);
                }
                if ((completableFuture = this.stunProbeFutureRef.get()) != null && this.stunProbeResult == null && !completableFuture.isDone()) {
                    Object object;
                    object = roomState;
                    String string10 = string;
                    String string11 = string8;
                    String string12 = string7;
                    int n8 = n3;
                    String string13 = string9;
                    int n9 = n4;
                    boolean bl3 = bl;
                    int n10 = n5;
                    completableFuture.thenAccept(arg_0 -> this.lambda$handleHolePunchOffer$14((RoomManager.RoomState)object, string12, arg_0));
                }
                this.finishHandleHolePunchOffer(roomState, string, string8, string7, n3, string9, n4, bl, n5);
            }
        }
    }

    private void applyProbeResultToActiveConnection(RoomManager.RoomState roomState, String string) {
        if (this.stunProbeResult == null) {
            return;
        }
        VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Probe done: NAT={}, reachable STUN={}, apply to active punch", (Object)this.stunProbeResult.natType.key, (Object)this.stunProbeResult.reachableStunUrls.size());
        String string2 = null;
        for (StunProbe.StunServerResult object : this.stunProbeResult.serverResults) {
            if (!object.reachable || object.mappedIp == null) continue;
            string2 = object.mappedIp;
            break;
        }
        if (string2 != null && string != null && string2.equals(string)) {
            VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] Same public IP ({}): both behind same CGNAT, P2P direct unlikely", (Object)string2);
            roomState.roomInfo.setSameCgnat(true);
        }
        if (string2 != null) {
            int n = 0;
            for (StunProbe.StunServerResult stunServerResult : this.stunProbeResult.serverResults) {
                if (!stunServerResult.reachable || stunServerResult.mappedPort <= 0) continue;
                n = stunServerResult.mappedPort;
                break;
            }
            roomState.roomInfo.setMyMappedIp(string2);
            roomState.roomInfo.setMyMappedPort(n);
        }
        if (this.connectionWon.get() || this.connectionCycleActive.get()) {
            this.localNatClass = this.classifyLocalNat();
            this.remoteNatClass = this.classifyRemoteNat(roomState);
            PunchProfile punchProfile = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
            this.switchPunchProfile(punchProfile, "probe_done_" + (Object)((Object)this.localNatClass) + "x" + (Object)((Object)this.remoteNatClass));
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Probe upgrade: local={} remote={} -> profile={}", new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()});
        }
    }

    private void finishHandleHolePunchOffer(RoomManager.RoomState roomState, String string, String string2, String string3, int n, String string4, int n2, boolean bl, int n3) {
        String string5;
        if (this.stunProbeResult != null) {
            if (this.stunProbeResult.natType.isSymmetric()) {
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.probing", (Object[])new Object[0]));
            }
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Use probe result: NAT={}, reachable STUN={}", (Object)this.stunProbeResult.natType.key, (Object)this.stunProbeResult.reachableStunUrls.size());
            string5 = null;
            for (StunProbe.StunServerResult iterator : this.stunProbeResult.serverResults) {
                if (!iterator.reachable || iterator.mappedIp == null) continue;
                string5 = iterator.mappedIp;
                break;
            }
            if (string5 != null && string3 != null && string5.equals(string3)) {
                VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] Same public IP ({}): both behind same CGNAT, P2P direct unlikely", (Object)string5);
                roomState.roomInfo.setSameCgnat(true);
            }
            if (string5 != null) {
                int n4 = 0;
                for (StunProbe.StunServerResult stunServerResult : this.stunProbeResult.serverResults) {
                    if (!stunServerResult.reachable || stunServerResult.mappedPort <= 0) continue;
                    n4 = stunServerResult.mappedPort;
                    break;
                }
                roomState.roomInfo.setMyMappedIp(string5);
                roomState.roomInfo.setMyMappedPort(n4);
            }
        }
        if (bl) {
            roomState.roomInfo.setHostSymmetric(true);
        }
        if (n3 != 0) {
            roomState.roomInfo.setHostMappedPortDelta(n3);
        }
        string5 = string4;
        int n5 = n2;
        if (string5 == null || n5 <= 0) {
            String string6 = roomState.roomInfo.getHostMappedIp();
            int n4 = roomState.roomInfo.getHostMappedPort();
            if (string6 != null && n4 > 0) {
                string5 = string6;
                n5 = n4;
                VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Use first holepunch_mapped: {}:{}", (Object)string5, (Object)n5);
            }
        }
        if (string5 != null && n5 > 0) {
            this.runConnectionCycle(roomState, string, string2, string3, n, string5, n5, 0);
        } else {
            this.connectionCycleActive.set(false);
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] offer has no mapped address, wait for holepunch_mapped...");
            this.scheduler.schedule(() -> {
                if (this.connectionCycleActive.compareAndSet(false, true)) {
                    String string4 = roomState.roomInfo.getHostMappedIp();
                    int n2 = roomState.roomInfo.getHostMappedPort();
                    if (string4 == null || n2 <= 0) {
                        VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] holepunch_mapped timeout (12s), start without mapped address");
                        this.runConnectionCycle(roomState, string, string2, string3, n, null, 0, 0);
                    }
                } else {
                    VoxLinkMod.LOGGER.debug("[handleHolePunchOffer] CAS failed, new offer in progress");
                }
            }, 12L, TimeUnit.SECONDS);
        }
    }

    public void handleHolepunchMapped(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && !roomState.roomInfo.isHost()) {
            Object object2;
            Object object3;
            String string2 = null;
            int n = 0;
            if (jsonObject.has("hostMappedIp") && !jsonObject.get("hostMappedIp").isJsonNull()) {
                string2 = jsonObject.get("hostMappedIp").getAsString();
            }
            if (jsonObject.has("hostMappedPort") && !jsonObject.get("hostMappedPort").isJsonNull()) {
                n = jsonObject.get("hostMappedPort").getAsInt();
            }
            boolean bl = jsonObject.has("hostSymmetric") && !jsonObject.get("hostSymmetric").isJsonNull() && jsonObject.get("hostSymmetric").getAsBoolean();
            boolean bl2 = jsonObject.has("hostEasySym") && !jsonObject.get("hostEasySym").isJsonNull() && jsonObject.get("hostEasySym").getAsBoolean();
            int n2 = jsonObject.has("hostMappedPortDelta") && !jsonObject.get("hostMappedPortDelta").isJsonNull() ? jsonObject.get("hostMappedPortDelta").getAsInt() : 0;
            int n3 = jsonObject.has("hostMappedPortRange") && !jsonObject.get("hostMappedPortRange").isJsonNull() ? jsonObject.get("hostMappedPortRange").getAsInt() : 100;
            ArrayList<Integer> arrayList = new ArrayList<Integer>();
            boolean bl3 = false;
            if (jsonObject.has("hostMappedPorts") && jsonObject.get("hostMappedPorts").isJsonArray()) {
                object3 = jsonObject.getAsJsonArray("hostMappedPorts").iterator();
                while (object3.hasNext()) {
                    object2 = (JsonElement)object3.next();
                    arrayList.add(object2.getAsInt());
                }
            }
            if (!(string2 == null || n <= 0 && arrayList.isEmpty())) {
                String string3;
                Object object4;
                int n4;
                Object object5;
                Object object6;
                if (arrayList.isEmpty()) {
                    arrayList.add(n);
                }
                bl3 = this.lastHostMappedPorts != null && this.lastHostMappedPorts.equals(arrayList);
                boolean bl4 = bl3;
                if (bl3) {
                    VoxLinkMod.LOGGER.debug("[RoomManager] host mapped ports unchanged, skip rebuild");
                }
                VoxLinkMod.LOGGER.info("[RoomManager] Received host mapped: {}:{} ports={} (sym={}, delta={})", new Object[]{string2, n, arrayList, bl, n2});
                roomState.roomInfo.setHostMappedAddress(string2, n);
                this.lastHostMappedPorts = new ArrayList<Integer>(arrayList);
                if (bl) {
                    roomState.roomInfo.setHostSymmetric(true);
                    object6 = bl2 ? NatClass.EASY_SYM : NatClass.HARD_SYM;
                    this.remoteNatClass = object6;
                    VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Update remoteNat {} -> {} (sym=true)", (Object)NatClass.CONE, object6);
                    object5 = this.scenarioTier;
                    n4 = this.stunProbeResult != null ? this.stunProbeResult.reachableStunUrls.size() : 0;
                    this.scenarioTier = ScenarioTier.classify(this.localNatClass.isSymmetric(), this.remoteNatClass.isSymmetric(), n4 > 0 && n4 <= 3, false, false);
                    if (this.scenarioTier != object5) {
                        VoxLinkMod.LOGGER.info("[handleHolepunchMapped] tier {} -> {} (host symmetric known)", (Object)ScenarioTier.key((ScenarioTier.Tier)((Object)object5)), (Object)ScenarioTier.key(this.scenarioTier));
                    }
                    if ((object4 = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier)) != this.punchProfile()) {
                        this.switchPunchProfile((PunchProfile)object4, "nat_matrix_" + (Object)((Object)this.localNatClass) + "x" + (Object)((Object)this.remoteNatClass) + "_after_mapped");
                    }
                }
                if (bl2) {
                    roomState.roomInfo.setHostEasySym(true);
                }
                if (n2 != 0) {
                    roomState.roomInfo.setHostMappedPortDelta(n2);
                }
                if (n3 != 100) {
                    roomState.roomInfo.setHostMappedPortRange(n3);
                }
                if (string2 != null && n > 0) {
                    object4 = bl ? (bl2 ? "symmetric_easy_inc" : "symmetric") : "full_cone";
                    roomState.roomInfo.addOrUpdatePeer(string, (String)object4, string2, n);
                }
                if (jsonObject.has("hostLocalIp") && !jsonObject.get("hostLocalIp").isJsonNull() && (string3 = jsonObject.get("hostLocalIp").getAsString()) != null && !string3.isEmpty() && ((object2 = roomState.roomInfo.getHostLocalIp()) == null || ((String)object2).isEmpty())) {
                    roomState.roomInfo.setHostLocalIp(string3);
                    VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Received host LAN IP: {}", (Object)string3);
                    if (this.connectionCycleActive.get() && roomState.roomInfo.isSameCgnat() && !this.connectionWon.get()) {
                        int n5 = roomState.roomInfo.getHostConnectPort() > 0 ? roomState.roomInfo.getHostConnectPort() : roomState.roomInfo.getHostPort();
                        int n6 = roomState.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT: also try hostLocalIp {}:{}", (Object)string3, (Object)n5);
                        object5 = this.trackFallback(new ConnectionFallback());
                        ((ConnectionFallback)object5).tryIpv4Direct(string3, n5).thenAccept(connectResult -> {
                            if (this.roomManager.currentRoom.get() == roomState && connectResult.success && this.connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp direct connect won");
                                this.connectViaBridge(roomState, (ConnectionFallback.ConnectResult)connectResult);
                            }
                        });
                        ConnectionFallback connectionFallback = this.trackFallback(new ConnectionFallback());
                        connectionFallback.tryIpv4Direct(string3, n6).thenAccept(connectResult -> {
                            if (this.roomManager.currentRoom.get() == roomState && connectResult.success && this.connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp MC port won");
                                this.connectViaBridge(roomState, (ConnectionFallback.ConnectResult)connectResult);
                            }
                        });
                    }
                }
                if ((object3 = this.activeHolePunchers.get("joiner")) != null && this.connectionCycleActive.get()) {
                    int n7 = (Integer)arrayList.get(0);
                    VoxLinkMod.LOGGER.info("[RoomManager] Punch host mapped ports raw={} (delta={} skipped: \u540csocket\u53ccSTUN\u5dee\u503c\u975e\u8de8socket\u6b65\u957f), target={}:{}", new Object[]{arrayList, n2, string2, n7});
                    ((UdpHolePuncher)object3).updateTarget(string2, n7);
                    if (!bl3 && arrayList.size() > 1) {
                        object6 = new ArrayList();
                        for (String string4 : this.activeHolePunchers.keySet()) {
                            if (!string4.startsWith("joiner_extra_")) continue;
                            ((ArrayList)object6).add(string4);
                        }
                        if (!((ArrayList)object6).isEmpty()) {
                            VoxLinkMod.LOGGER.info("[RoomManager] Drift resync: remove {} stale extra punchers, rebuild with latest ports", (Object)((ArrayList)object6).size());
                            object5 = ((ArrayList)object6).iterator();
                            while (object5.hasNext()) {
                                String string5 = (String)object5.next();
                                UdpHolePuncher udpHolePuncher = this.activeHolePunchers.remove(string5);
                                if (udpHolePuncher == null) continue;
                                try {
                                    udpHolePuncher.cancel();
                                    udpHolePuncher.close();
                                }
                                catch (Exception exception) {}
                            }
                        }
                    }
                    int n8 = Math.min(arrayList.size(), 6);
                    for (int i = 1; i < n8; ++i) {
                        n4 = i;
                        int n9 = (Integer)arrayList.get(i);
                        String string6 = "joiner_extra_" + n4;
                        if (this.activeHolePunchers.containsKey(string6)) continue;
                        UdpHolePuncher udpHolePuncher = new UdpHolePuncher();
                        this.applyPunchTemplate(udpHolePuncher);
                        try {
                            udpHolePuncher.createSocket();
                            this.activeHolePunchers.put(string6, udpHolePuncher);
                            int n10 = n9;
                            String string7 = string2;
                            VoxLinkMod.LOGGER.info("[RoomManager] Multi-port puncher#{}: {}:{}", new Object[]{i, string7, n10});
                            int n11 = this.punchProfile().joinerMultiPortRange;
                            ((CompletableFuture)udpHolePuncher.punchWithPortPrediction(string7, n10, n11).thenAccept(punchResult -> {
                                if (!punchResult.isSuccess()) {
                                    PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                                    this.lastPunchResult = punchResult.withReason(failureReason);
                                    this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
                                    VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                                } else {
                                    DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                                    if (!this.connectionWon.compareAndSet(false, true)) {
                                        try {
                                            udpHolePuncher.close();
                                        }
                                        catch (Exception exception) {}
                                    } else {
                                        udpHolePuncher.markSocketTransferred();
                                        this.stopAllPunchingAfterHostBridge();
                                        udpHolePuncher.stopPunch();
                                        DatagramSocket datagramSocket2 = datagramSocket;
                                        UdpHolePuncher udpHolePuncher2 = udpHolePuncher;
                                        this.scheduler.submit(() -> {
                                            try {
                                                this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher2, new InetSocketAddress(string7, n10), "joiner", false, null);
                                            }
                                            catch (Exception exception) {
                                                VoxLinkMod.LOGGER.error("[RoomManager] Multi-port transport failed: {}", (Object)exception.getMessage());
                                                udpHolePuncher2.close();
                                            }
                                        });
                                    }
                                }
                            })).exceptionally(object -> {
                                VoxLinkMod.LOGGER.debug("[RoomManager] Multi-port puncher#{} punch failed: {}", (Object)n4, (Object)((Throwable)object).getMessage());
                                this.activeHolePunchers.remove(string6);
                                try {
                                    udpHolePuncher.close();
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                                return null;
                            });
                            continue;
                        }
                        catch (Exception exception) {
                            VoxLinkMod.LOGGER.warn("[RoomManager] Create multi-port puncher#{} failed: {}", (Object)n4, (Object)exception.getMessage());
                        }
                    }
                } else if (!this.connectionCycleActive.get() && !ConnectionHelper.isConnecting()) {
                    VoxLinkMod.LOGGER.info("[RoomManager] Start connection cycle with mapped address");
                    if (this.connectionCycleActive.compareAndSet(false, true)) {
                        int n12;
                        object6 = roomState.roomInfo.getHostIp();
                        object5 = roomState.roomInfo.getHostIpv6();
                        n4 = roomState.roomInfo.getHostConnectPort() > 0 ? roomState.roomInfo.getHostConnectPort() : roomState.roomInfo.getHostPort();
                        int n13 = n;
                        if (n2 != 0 && (n12 = n + n2) > 0 && n12 <= 65535) {
                            n13 = n12;
                        }
                        this.runConnectionCycle(roomState, string, (String)object5, (String)object6, n4, string2, n13, 0);
                    }
                }
            }
        }
    }

    public void handlePunchInfo(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            if (roomState.roomInfo.isHost()) {
                this.handleHostPunchInfo(roomState, string, jsonObject);
            } else {
                this.handleJoinerPunchInfo(roomState, string, jsonObject);
            }
        }
    }

    public void handleHostPunchInfo(RoomManager.RoomState roomState, String string, JsonObject jsonObject) {
        boolean bl;
        String string2;
        this.activePunchParams = null;
        String string3 = jsonObject.has("joinerMappedIp") ? jsonObject.get("joinerMappedIp").getAsString() : null;
        int n = jsonObject.has("joinerMappedPort") ? jsonObject.get("joinerMappedPort").getAsInt() : 0;
        int n2 = jsonObject.has("joinerMappedPortDelta") && !jsonObject.get("joinerMappedPortDelta").isJsonNull() ? jsonObject.get("joinerMappedPortDelta").getAsInt() : 0;
        String string4 = null;
        if (jsonObject.has("joinerLocalIp") && !jsonObject.get("joinerLocalIp").isJsonNull()) {
            string4 = jsonObject.get("joinerLocalIp").getAsString();
            roomState.roomInfo.setJoinerLocalIp(string4);
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Joiner LAN IP: {}", (Object)string4);
        }
        if (string3 != null && (string2 = roomState.roomInfo.getHostMappedIp()) != null && string2.equals(string3)) {
            roomState.roomInfo.setSameCgnat(true);
            VoxLinkMod.LOGGER.warn("[HostPunchInfo] Same public IP ({}): both behind same CGNAT", (Object)string3);
        }
        boolean bl2 = jsonObject.has("requestHostLocalIp") && jsonObject.get("requestHostLocalIp").getAsBoolean();
        boolean bl3 = bl2;
        if (n2 != 0 && n > 0) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Punch joiner raw port={} (delta={} skipped: \u540csocket\u53ccSTUN\u5dee\u503c\u975e\u8de8socket\u6b65\u957f)", new Object[]{n, n2});
        }
        boolean bl4 = false;
        for (Object object : this.activeHolePunchers.values()) {
            if (object == null || !object.isPunching()) continue;
            bl4 = true;
            break;
        }
        if (!bl4) {
            long l = System.currentTimeMillis() - this.lastPunchStartMs;
            bl = false;
            for (String string5 : this.activeHolePunchers.keySet()) {
                if (!string5.startsWith("host_")) continue;
                bl = true;
                break;
            }
            if (l >= 0L && l < 120000L && bl && this.lastPunchInfoId != null && !this.lastPunchInfoId.isEmpty()) {
                bl4 = true;
            }
        }
        this.hostPunching = bl4;
        VoxLinkMod.LOGGER.info("[HostPunchInfo] called: joinerMapped={}:{}, delta={}, hostPunching={}, bridgeRunning={}, hostSym={}", new Object[]{string3, n, n2, this.hostPunching, P2PBridge.isRunning(), roomState.roomInfo.isHostSymmetric()});
        if (string3 == null || n == 0) {
            VoxLinkMod.LOGGER.warn("[RoomManager] Invalid punch_info from {}: no mapped address", (Object)string);
        } else if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.debug("[HostPunchInfo] already connected, ignoring punch_info");
        } else {
            int n3;
            Object object;
            String string6 = string3 + ":" + n;
            if (this.hostPunching) {
                if (string6.equals(this.lastPunchInfoId)) {
                    VoxLinkMod.LOGGER.debug("[RoomManager] Already punching same target, ignore duplicate punch_info");
                    return;
                }
                if (System.currentTimeMillis() - this.lastPunchStartMs < 3000L) {
                    VoxLinkMod.LOGGER.info("[RoomManager] Already punching, target changed ({} -> {}) within {}ms, ignore to avoid CGNAT IP switch false restart", new Object[]{this.lastPunchInfoId, string6, 3000});
                    return;
                }
                VoxLinkMod.LOGGER.info("[RoomManager] Punching {}ms no success, target changed ({} -> {}), restart with new target", new Object[]{System.currentTimeMillis() - this.lastPunchStartMs, this.lastPunchInfoId, string6});
                String[] stringArray = this.lastPunchInfoId.split(":");
                if (stringArray.length == 2 && stringArray[0].equals(string3)) {
                    this.lastPunchInfoId = string6;
                    for (UdpHolePuncher udpHolePuncher : this.activeHolePunchers.values()) {
                        if (udpHolePuncher == null || udpHolePuncher.getSocket() == null || udpHolePuncher.getSocket().isClosed()) continue;
                        udpHolePuncher.updateTarget(string3, n);
                    }
                    VoxLinkMod.LOGGER.info("[RoomManager] joiner mapped port drift ({} -> {}), update target, keep socket group stable", new Object[]{stringArray[1], n});
                    return;
                }
                this.hostPunching = false;
                this.activeHolePunchers.entrySet().removeIf(entry -> {
                    if (((String)entry.getKey()).startsWith("host_")) {
                        UdpHolePuncher udpHolePuncher = (UdpHolePuncher)entry.getValue();
                        if (udpHolePuncher != null) {
                            try {
                                udpHolePuncher.stopPunch();
                            }
                            catch (Exception exception) {
                                // empty catch block
                            }
                            try {
                                udpHolePuncher.close();
                            }
                            catch (Exception exception) {
                                // empty catch block
                            }
                        }
                        return true;
                    }
                    return false;
                });
            }
            this.lastPunchInfoId = string6;
            LogUploadManager.schedulePunchUpload();
            VoxLinkMod.LOGGER.info("[RoomManager] Received punch_info from {}: {}:{}", new Object[]{string, string3, n});
            boolean bl5 = StunDetector.isNatTypeSymmetric(roomState.roomInfo.getNatType());
            boolean bl6 = StunDetector.isHardSymmetric(roomState.roomInfo.getNatType());
            boolean bl7 = jsonObject.has("joinerSymmetric") && jsonObject.get("joinerSymmetric").getAsBoolean();
            boolean bl8 = jsonObject.has("joinerEasySym") && !jsonObject.get("joinerEasySym").isJsonNull() && jsonObject.get("joinerEasySym").getAsBoolean();
            boolean bl9 = bl8;
            if (string3 != null && n > 0) {
                String string7 = bl7 ? (bl8 ? "symmetric_easy_inc" : "symmetric") : "full_cone";
                roomState.roomInfo.addOrUpdatePeer(string, string7, string3, n);
            }
            boolean bl10 = bl7 && !bl8;
            boolean bl11 = bl10;
            if (bl5 && bl7 && (bl6 || bl10) && !"unknown".equals(roomState.roomInfo.getNatType()) && roomState.roomInfo.getNatType() != null) {
                VoxLinkMod.LOGGER.warn("[HostPunchInfo] Both symmetric NAT with HardSym (hostHard={}, joinerHard={}), UPnP stripped, continue Birthday Attack+port prediction (fallback to Relay on failure)", (Object)bl6, (Object)bl10);
            }
            if (bl5 && bl7) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");
            }
            bl = (object = roomState.roomInfo.getIpv4Status()) == RoomInfo.PortStatus.UNREACHABLE || object == RoomInfo.PortStatus.UNKNOWN;
            boolean bl12 = bl;
            if (bl && !bl5) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Host port status={}, upgrade to 20 socket birthday attack", object);
            }
            if (bl5 && bl7) {
                n3 = PunchProfile.HARDSYM.hardSymSocketCount;
                this.switchPunchProfile(PunchProfile.HARDSYM, "Sym\u00d7Sym");
            } else if (bl5) {
                boolean bl13 = this.scenarioTier == ScenarioTier.Tier.HARD_DUAL_SYM || this.scenarioTier == ScenarioTier.Tier.HARD_ONE_SYM;
                n3 = bl13 ? PunchProfile.HARDSYM.hardSymSocketCount : Math.max(PunchProfile.HARDSYM.hostMultiMinSocketCount, this.punchProfile().hostMultiSocketCount);
            } else {
                n3 = bl ? this.punchProfile().hostMultiSocketCount : this.punchProfile().hostMultiBaseSocketCount;
            }
            RoomManager.RoomState roomState2 = roomState;
            String string8 = string;
            JsonObject jsonObject2 = jsonObject;
            boolean bl14 = bl2;
            int n4 = n3;
            String string9 = string3;
            int n5 = n;
            CompletableFuture.runAsync(() -> {
                int n3;
                ArrayList<UdpHolePuncher> arrayList = new ArrayList<UdpHolePuncher>();
                ArrayList<StunProbe.PublicMappedAddress> arrayList2 = new ArrayList<StunProbe.PublicMappedAddress>();
                boolean bl2 = false;
                int n4 = 0;
                UdpHolePuncher udpHolePuncher = this.activeHolePunchers.remove("host");
                if (udpHolePuncher != null) {
                    try {
                        udpHolePuncher.close();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
                ArrayList<CompletableFuture<StunProbe.PublicMappedAddress[]>> arrayList3 = new ArrayList<CompletableFuture<StunProbe.PublicMappedAddress[]>>();
                int n5 = 0;
                for (n3 = 0; n3 < n4; ++n3) {
                    UdpHolePuncher udpHolePuncher2 = new UdpHolePuncher();
                    this.applyPunchTemplate(udpHolePuncher2);
                    PunchParams punchParams = PunchParams.fromProfile(this.punchProfile());
                    punchParams.timeoutMs = Math.min(punchParams.timeoutMs, this.punchProfile().hostRoundTimeoutMs);
                    udpHolePuncher2.setPunchParams(punchParams);
                    try {
                        if (n3 == 0) {
                            udpHolePuncher2.createSocket(roomState.roomInfo.getHostPort());
                        } else {
                            udpHolePuncher2.createSocket();
                        }
                    }
                    catch (Exception exception) {
                        try {
                            udpHolePuncher2.createSocket();
                        }
                        catch (Exception exception2) {
                            continue;
                        }
                    }
                    arrayList.add(udpHolePuncher2);
                    this.activeHolePunchers.put("host_" + n3, udpHolePuncher2);
                    ++n5;
                    UdpHolePuncher udpHolePuncher3 = udpHolePuncher2;
                    int n6 = n3;
                    arrayList3.add(CompletableFuture.supplyAsync(() -> {
                        StunProbe.PublicMappedAddress[] publicMappedAddressArray = StunProbe.discoverMappedAddressDual(udpHolePuncher3.getSocket(), StunDetector.getAllStunUrls().get(0), StunDetector.getAllStunUrls().get(1));
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} STUN(dual): {} vs {} (localPort={})", new Object[]{n6, publicMappedAddressArray[0] != null ? publicMappedAddressArray[0].port() : -1, publicMappedAddressArray[1] != null ? publicMappedAddressArray[1].port() : -1, udpHolePuncher3.getSocket().getLocalPort()});
                        return publicMappedAddressArray;
                    }));
                    int n7 = Math.max(0, this.punchProfile().socketCreateIntervalMs);
                    if (n7 <= 0) continue;
                    try {
                        Thread.sleep(n7);
                        continue;
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Parallel start {}/{} socket+dual STUN tasks (no sleep)", (Object)n5, (Object)n4);
                n3 = Math.min(this.punchProfile().birthdaySocketCount, n4);
                long l = System.currentTimeMillis() + Math.max(5000L, (long)n4 * (long)Math.max(0, this.punchProfile().socketCreateIntervalMs) + 3000L);
                while (System.currentTimeMillis() < l) {
                    int n8 = 0;
                    int n9 = 0;
                    for (CompletableFuture exception : arrayList3) {
                        if (!exception.isDone()) continue;
                        ++n8;
                        try {
                            StunProbe.PublicMappedAddress[] publicMappedAddressArray = exception.getNow(null);
                            if (publicMappedAddressArray == null || publicMappedAddressArray[0] == null && publicMappedAddressArray[1] == null) continue;
                            ++n9;
                        }
                        catch (Exception exception2) {}
                    }
                    if (n9 >= n3 || n8 == arrayList3.size()) break;
                    try {
                        Thread.sleep(100L);
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                String string3 = null;
                for (int i = 0; i < arrayList3.size(); ++i) {
                    try {
                        StunProbe.PublicMappedAddress jsonObject2;
                        StunProbe.PublicMappedAddress[] publicMappedAddressArray = ((CompletableFuture)arrayList3.get(i)).getNow(null);
                        if (publicMappedAddressArray == null || publicMappedAddressArray[0] == null && publicMappedAddressArray[1] == null) continue;
                        StunProbe.PublicMappedAddress publicMappedAddress = publicMappedAddressArray[0] != null && publicMappedAddressArray[0].ip().indexOf(58) < 0 ? publicMappedAddressArray[0] : (publicMappedAddressArray[1] != null && publicMappedAddressArray[1].ip().indexOf(58) < 0 ? publicMappedAddressArray[1] : (jsonObject2 = publicMappedAddressArray[0] != null ? publicMappedAddressArray[0] : publicMappedAddressArray[1]));
                        if (jsonObject2 != null) {
                            arrayList2.add(jsonObject2);
                            if (string3 == null) {
                                string3 = jsonObject2.ip();
                            }
                        }
                        if (publicMappedAddressArray[0] == null || publicMappedAddressArray[1] == null || !ConnectionManager.sameIpFamily(publicMappedAddressArray[0].ip(), publicMappedAddressArray[1].ip()) || publicMappedAddressArray[0].port() == publicMappedAddressArray[1].port()) continue;
                        bl2 = true;
                        if (n4 != 0) continue;
                        n4 = publicMappedAddressArray[1].port() - publicMappedAddressArray[0].port();
                        continue;
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
                if (arrayList2.isEmpty()) {
                    VoxLinkMod.LOGGER.error("[HostPunchInfo] All STUN queries failed");
                } else if (this.connectionWon.get()) {
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] Connection established, discard late 84-socket punch task");
                    for (UdpHolePuncher udpHolePuncher4 : arrayList) {
                        try {
                            udpHolePuncher4.close();
                        }
                        catch (Exception exception) {}
                    }
                } else {
                    String string4;
                    boolean bl3;
                    Object object2;
                    Object object32;
                    Object object4;
                    if (bl2 && !roomState.roomInfo.isHostSymmetric()) {
                        roomState.roomInfo.setHostSymmetric(true);
                        boolean bl4 = Math.abs(n4) <= 100;
                        NatClass natClass = bl4 ? NatClass.EASY_SYM : NatClass.HARD_SYM;
                        object4 = natClass;
                        if (this.localNatClass != natClass) {
                            VoxLinkMod.LOGGER.info("[HostPunchInfo] Update localNat {} -> {} (multi-socket symmetric detected, delta={})", new Object[]{this.localNatClass, natClass, n4});
                            this.localNatClass = natClass;
                            this.remoteNatClass = this.classifyRemoteNat(roomState2);
                            object32 = this.scenarioTier;
                            this.scenarioTier = ScenarioTier.classify(this.localNatClass.isSymmetric(), this.remoteNatClass.isSymmetric(), true, true, true);
                            if (this.scenarioTier != object32) {
                                VoxLinkMod.LOGGER.info("[HostPunchInfo] tier {} -> {} (host symmetric known)", (Object)ScenarioTier.key((ScenarioTier.Tier)((Object)((Object)object32))), (Object)ScenarioTier.key(this.scenarioTier));
                            }
                            object2 = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
                            this.switchPunchProfile((PunchProfile)object2, "host_punch_sym_" + (Object)((Object)this.localNatClass) + "x" + (Object)((Object)this.remoteNatClass));
                            VoxLinkMod.LOGGER.info("[HostPunchInfo] Probe upgrade: local={} remote={} -> profile={}", new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()});
                        }
                        if (bl4 && !"symmetric_easy_inc".equals(roomState.roomInfo.getNatType())) {
                            roomState.roomInfo.setNatType("symmetric_easy_inc");
                            VoxLinkMod.LOGGER.info("[HostPunchInfo] Override cached NAT {} -> symmetric_easy_inc (multi-socket dual STUN detected)", (Object)"cached");
                        }
                    }
                    if (bl2 && arrayList.size() < PunchProfile.HARDSYM.hardSymSocketCount) {
                        int n6 = arrayList.size();
                        int n7 = PunchProfile.HARDSYM.hardSymSocketCount;
                        for (int i = arrayList.size(); i < n7; ++i) {
                            try {
                                object32 = new UdpHolePuncher();
                                this.applyPunchTemplate((UdpHolePuncher)object32);
                                object2 = PunchParams.fromProfile(this.punchProfile());
                                ((PunchParams)object2).timeoutMs = Math.min(((PunchParams)object2).timeoutMs, this.punchProfile().hostRoundTimeoutMs);
                                ((UdpHolePuncher)object32).setProfile(this.punchProfile());
                                ((UdpHolePuncher)object32).setPunchParams((PunchParams)object2);
                                ((UdpHolePuncher)object32).createSocket();
                                arrayList.add((UdpHolePuncher)object32);
                                this.activeHolePunchers.put("host_" + (arrayList.size() - 1), (UdpHolePuncher)object32);
                                continue;
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.debug("[HostPunchInfo] extra socket create failed: {}", (Object)exception.getMessage());
                            }
                        }
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] Symmetric host, extend birthday attack {} -> {} sockets", (Object)n6, (Object)arrayList.size());
                    }
                    JsonObject jsonObject2 = new JsonObject();
                    if (bl2 || StunDetector.isNatTypeSymmetric(roomState.roomInfo.getNatType())) {
                        jsonObject2.addProperty("hostSymmetric", Boolean.valueOf(true));
                    }
                    boolean bl5 = bl3 = bl2 && Math.abs(n4) <= 100;
                    if (bl3) {
                        jsonObject2.addProperty("hostEasySym", Boolean.valueOf(true));
                    }
                    if (bl2 && n4 != 0) {
                        jsonObject2.addProperty("hostMappedPortDelta", (Number)n4);
                    }
                    jsonObject2.addProperty("hostMappedIp", string3);
                    jsonObject2.addProperty("hostMappedPort", (Number)((StunProbe.PublicMappedAddress)arrayList2.get(0)).port());
                    if ((bl14 || roomState.roomInfo.isSameCgnat()) && (string4 = StunDetector.getLocalIpAddress()) != null && !string4.isEmpty()) {
                        jsonObject2.addProperty("hostLocalIp", string4);
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] CGNAT scenario include hostLocalIp: {}", (Object)string4);
                    }
                    JsonArray jsonArray = new JsonArray();
                    for (Object object32 : arrayList2) {
                        jsonArray.add((Number)((StunProbe.PublicMappedAddress)object32).port());
                    }
                    jsonObject2.add("hostMappedPorts", (JsonElement)jsonArray);
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] holepunch_mapped: {} ports={} (symmetric={})", new Object[]{string3, jsonArray, bl2});
                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "holepunch_mapped", jsonObject2, string8).exceptionally(throwable -> {
                        VoxLinkMod.LOGGER.debug("holepunch_mapped send failed: {}", (Object)throwable.getMessage());
                        return null;
                    });
                    this.hostPunching = true;
                    this.lastPunchStartMs = System.currentTimeMillis();
                    object4 = string8;
                    ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "Host\u5f00\u59cb\u6253\u6d1e client=" + (String)object4);
                    object32 = new AtomicBoolean(false);
                    for (UdpHolePuncher udpHolePuncher2 : arrayList) {
                        udpHolePuncher2.setOnPeerPunchReceived(inetSocketAddress -> {
                            String string2 = roomState.roomInfo.getCode();
                            String string3 = roomState.roomInfo.getToken();
                            JsonObject jsonObject = new JsonObject();
                            jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                            jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                            this.signalingClient.sendSignal(string2, string3, true, "peer_port", jsonObject, string8).exceptionally(throwable -> {
                                VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", (Object)throwable.getMessage());
                                return null;
                            });
                        });
                    }
                    object2 = this.scheduler.schedule(() -> this.lambda$handleHostPunchInfo$27((String)object4, arrayList), 120L, TimeUnit.SECONDS);
                    boolean bl6 = jsonObject2.has("joinerSymmetric") && jsonObject2.get("joinerSymmetric").getAsBoolean();
                    boolean bl7 = this.localNatClass.isSymmetric() && this.remoteNatClass.isSymmetric();
                    int n8 = bl6 || bl7 ? this.punchProfile().joinerMultiPortRange : 0;
                    for (UdpHolePuncher udpHolePuncher3 : arrayList) {
                        if (udpHolePuncher3 == null || udpHolePuncher3.getSocket() == null || udpHolePuncher3.getSocket().isClosed()) continue;
                        udpHolePuncher3.setProfile(this.punchProfile());
                        PunchParams punchParams = PunchParams.fromProfile(this.punchProfile());
                        punchParams.timeoutMs = Math.min(punchParams.timeoutMs, this.punchProfile().hostRoundTimeoutMs);
                        if (n8 > 0) {
                            punchParams.sendMinRounds = 1;
                            punchParams.sendMinPass = 1;
                        }
                        udpHolePuncher3.setPunchParams(punchParams);
                    }
                    VoxLinkMod.LOGGER.info("[HostPunchInfo] {} sockets parallel punch to {}:{} range=\u00b1{} (joinerSym={})", new Object[]{arrayList.size(), string9, n5, n8, bl6});
                    boolean bl8 = bl2 || roomState.roomInfo.isHostSymmetric();
                    long l2 = 120000L;
                    long l3 = System.currentTimeMillis();
                    long l4 = 0L;
                    if (!this.connectionWon.get() && this.roomManager.currentRoom.get() == roomState2) {
                        long l5 = System.currentTimeMillis() + 120000L;
                        while (!this.connectionWon.get() && this.roomManager.currentRoom.get() == roomState2 && System.currentTimeMillis() < l5) {
                            if (bl8 && this.hostPunching && !arrayList2.isEmpty()) {
                                long l6 = System.currentTimeMillis();
                                long l7 = l6 - l3;
                                if (l6 - l4 >= 3000L && l7 < l2) {
                                    l4 = l6;
                                    try {
                                        JsonObject jsonObject3 = new JsonObject();
                                        jsonObject3.addProperty("hostMappedIp", string3);
                                        jsonObject3.addProperty("hostMappedPort", (Number)((StunProbe.PublicMappedAddress)arrayList2.get(0)).port());
                                        jsonObject3.addProperty("hostSymmetric", Boolean.valueOf(true));
                                        JsonArray jsonArray2 = new JsonArray();
                                        for (StunProbe.PublicMappedAddress publicMappedAddress : arrayList2) {
                                            jsonArray2.add((Number)publicMappedAddress.port());
                                        }
                                        jsonObject3.add("hostMappedPorts", (JsonElement)jsonArray2);
                                        if (bl3) {
                                            jsonObject3.addProperty("hostEasySym", Boolean.valueOf(true));
                                        }
                                        this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "holepunch_mapped", jsonObject3, string8).exceptionally(throwable -> {
                                            VoxLinkMod.LOGGER.debug("holepunch_mapped resync send failed: {}", (Object)throwable.getMessage());
                                            return null;
                                        });
                                        VoxLinkMod.LOGGER.info("[HostPunchInfo] drift resync: resend holepunch_mapped {}:{} ports={} (elapsed={}ms)", new Object[]{string3, ((StunProbe.PublicMappedAddress)arrayList2.get(0)).port(), jsonArray2, l7});
                                    }
                                    catch (Exception exception) {
                                        // empty catch block
                                    }
                                }
                            }
                            boolean bl9 = false;
                            ArrayList<CompletionStage> arrayList4 = new ArrayList<CompletionStage>();
                            for (int i = 0; i < arrayList.size(); ++i) {
                                UdpHolePuncher udpHolePuncher4 = (UdpHolePuncher)arrayList.get(i);
                                if (udpHolePuncher4.isSocketTransferred() || udpHolePuncher4.getSocket() == null || udpHolePuncher4.getSocket().isClosed()) continue;
                                bl9 = true;
                                int n9 = i;
                                arrayList4.add(((CompletableFuture)udpHolePuncher4.punchWithPortPrediction(string9, n5, n8).thenAccept(arg_0 -> this.lambda$handleHostPunchInfo$30((AtomicBoolean)object32, udpHolePuncher4, roomState2, n9, string9, n5, (String)object4, arg_0))).exceptionally(object -> {
                                    VoxLinkMod.LOGGER.debug("[HostPunchInfo] Socket#{} punch failed: {}", (Object)n13, (Object)((Throwable)object).getMessage());
                                    return null;
                                }));
                            }
                            if (!bl9) break;
                            try {
                                CompletableFuture.allOf(arrayList4.toArray(new CompletableFuture[0])).join();
                            }
                            catch (Exception exception) {
                                // empty catch block
                            }
                            if (this.connectionWon.get() || this.roomManager.currentRoom.get() != roomState2) break;
                            try {
                                Thread.sleep(300L);
                            }
                            catch (InterruptedException interruptedException) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } else {
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] Connected or room changed during lazy, abort punch");
                        for (UdpHolePuncher udpHolePuncher5 : arrayList) {
                            try {
                                udpHolePuncher5.close();
                            }
                            catch (Exception exception) {}
                        }
                    }
                }
            }, this.punchExecutor);
        }
    }

    public void handleJoinerPunchInfo(RoomManager.RoomState roomState, String string, JsonObject jsonObject) {
        String string2 = null;
        int n = 0;
        if (jsonObject.has("hostMappedIp") && !jsonObject.get("hostMappedIp").isJsonNull()) {
            string2 = jsonObject.get("hostMappedIp").getAsString();
        }
        if (jsonObject.has("hostMappedPort") && !jsonObject.get("hostMappedPort").isJsonNull()) {
            n = jsonObject.get("hostMappedPort").getAsInt();
        }
        if (string2 != null && n > 0) {
            VoxLinkMod.LOGGER.info("[RoomManager] Host mapped address in punch_info: {}:{}", (Object)string2, (Object)n);
            roomState.roomInfo.setHostMappedAddress(string2, n);
            UdpHolePuncher udpHolePuncher = this.activeHolePunchers.get("joiner");
            if (udpHolePuncher != null && this.connectionCycleActive.get()) {
                VoxLinkMod.LOGGER.info("[RoomManager] Update joiner punch target to {}:{}", (Object)string2, (Object)n);
                udpHolePuncher.updateTarget(string2, n);
            }
        }
    }

    public void handlePeerPort(String string, JsonObject jsonObject) {
        UdpHolePuncher udpHolePuncher;
        String string2 = jsonObject.has("peer_ip") ? jsonObject.get("peer_ip").getAsString() : null;
        int n = jsonObject.has("peer_port") ? jsonObject.get("peer_port").getAsInt() : 0;
        int n2 = n;
        if (string2 != null && n > 0 && (udpHolePuncher = this.activeHolePunchers.get(string.contains("host") ? "joiner" : "host")) != null) {
            VoxLinkMod.LOGGER.info("[RoomManager] Received peer_port signal: update target to {}:{}", (Object)string2, (Object)n);
            udpHolePuncher.updateTarget(string2, n);
        }
    }

    public void handleReverseHolepunchOffer(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isHost()) {
            long l;
            long l2 = roomState.roomInfo.getPunchSyncTimeMs();
            if (l2 > 0L && (l = l2 - System.currentTimeMillis()) > 0L && l < 8000L) {
                VoxLinkMod.LOGGER.info("[ReversePunch] RTT sync wait: start host punch after {}ms", (Object)l);
                this.scheduler.schedule(() -> {
                    if (this.roomManager.currentRoom.get() == roomState && !this.connectionWon.get()) {
                        this.handleReverseHolepunchOfferDelayed(string, jsonObject);
                    }
                }, l, TimeUnit.MILLISECONDS);
                return;
            }
            this.handleReverseHolepunchOfferDelayed(string, jsonObject);
        }
    }

    private void handleReverseHolepunchOfferDelayed(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isHost()) {
            if (this.activeUdpTransports.containsKey(string)) {
                VoxLinkMod.LOGGER.info("[ReversePunch] Active transport to {}, ignore reverse_holepunch_offer", (Object)string);
            } else {
                Object object;
                String string2 = jsonObject.has("joinerMappedIp") ? jsonObject.get("joinerMappedIp").getAsString() : null;
                int n = jsonObject.has("joinerMappedPort") ? jsonObject.get("joinerMappedPort").getAsInt() : 0;
                boolean bl = jsonObject.has("joinerSymmetric") && jsonObject.get("joinerSymmetric").getAsBoolean();
                int n2 = jsonObject.has("joinerMappedPortDelta") && !jsonObject.get("joinerMappedPortDelta").isJsonNull() ? jsonObject.get("joinerMappedPortDelta").getAsInt() : 0;
                int n3 = n2;
                if (string2 != null && n > 0) {
                    object = bl ? "symmetric" : "full_cone";
                    roomState.roomInfo.addOrUpdatePeer(string, (String)object, string2, n);
                }
                if (n2 != 0 && n > 0) {
                    VoxLinkMod.LOGGER.info("[ReversePunch] Punch joiner raw port={} (delta={} skipped: \u540csocket\u53ccSTUN\u5dee\u503c\u975e\u8de8socket\u6b65\u957f)", new Object[]{n, n2});
                }
                if (string2 != null && n != 0) {
                    VoxLinkMod.LOGGER.info("[ReversePunch] Host received reverse_holepunch_offer from {}: {}:{} (joinerSym={})", new Object[]{string, string2, n, bl});
                    object = this.activeHolePunchers.get("hostRev");
                    if (object != null && ((UdpHolePuncher)object).isPunching()) {
                        VoxLinkMod.LOGGER.info("[ReversePunch] already reverse punching, update target to {}:{}", (Object)string2, (Object)n);
                        ((UdpHolePuncher)object).updateTarget(string2, n);
                    } else {
                        UdpHolePuncher udpHolePuncher = new UdpHolePuncher();
                        this.applyPunchTemplate(udpHolePuncher);
                        try {
                            udpHolePuncher.createSocket();
                        }
                        catch (Exception exception) {
                            VoxLinkMod.LOGGER.error("[ReversePunch] create socket failed: {}", (Object)exception.getMessage());
                            return;
                        }
                        VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse punch socket: localPort={}", (Object)udpHolePuncher.getSocket().getLocalPort());
                        this.activeHolePunchers.put("hostRev", udpHolePuncher);
                        RoomManager.RoomState roomState2 = roomState;
                        String string3 = string;
                        JsonObject jsonObject2 = jsonObject;
                        UdpHolePuncher udpHolePuncher2 = udpHolePuncher;
                        String string4 = string2;
                        int n4 = n;
                        boolean bl2 = bl;
                        this.punchExecutor.execute(() -> {
                            CompletableFuture<PunchResult> completableFuture;
                            Object object4;
                            Object object2;
                            boolean bl2;
                            Object object3;
                            VoxLinkMod.LOGGER.info("[ReversePunch] dual STUN on reverse socket...");
                            StunProbe.PublicMappedAddress publicMappedAddress = null;
                            StunProbe.PublicMappedAddress publicMappedAddress2 = null;
                            try {
                                publicMappedAddress = udpHolePuncher2.discoverMappedAddress(Collections.singletonList(StunDetector.getAllStunUrls().get(0)));
                                VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse STUN #1: ip={}, port={} (localPort={})", new Object[]{publicMappedAddress != null ? publicMappedAddress.ip() : "null", publicMappedAddress != null ? publicMappedAddress.port() : -1, udpHolePuncher2.getSocket().getLocalPort()});
                                publicMappedAddress2 = udpHolePuncher2.discoverMappedAddress(Collections.singletonList(StunDetector.getAllStunUrls().get(1)));
                                VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse STUN #2: ip={}, port={} (localPort={})", new Object[]{publicMappedAddress2 != null ? publicMappedAddress2.ip() : "null", publicMappedAddress2 != null ? publicMappedAddress2.port() : -1, udpHolePuncher2.getSocket().getLocalPort()});
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.warn("[ReversePunch] Dual STUN failed: {}", (Object)exception.getMessage());
                            }
                            StunProbe.PublicMappedAddress publicMappedAddress3 = publicMappedAddress;
                            StunProbe.PublicMappedAddress publicMappedAddress4 = publicMappedAddress2;
                            boolean bl3 = false;
                            int n2 = 0;
                            StunProbe.PublicMappedAddress publicMappedAddress5 = null;
                            if (publicMappedAddress3 != null && publicMappedAddress4 != null) {
                                if (ConnectionManager.sameIpFamily(publicMappedAddress3.ip(), publicMappedAddress4.ip()) && publicMappedAddress3.port() != publicMappedAddress4.port()) {
                                    bl3 = true;
                                    n2 = publicMappedAddress4.port() - publicMappedAddress3.port();
                                    VoxLinkMod.LOGGER.info("[ReversePunch] Host punch socket STUN: symmetric detected ({} vs {}, delta={})", new Object[]{publicMappedAddress3.port(), publicMappedAddress4.port(), n2});
                                }
                                publicMappedAddress5 = publicMappedAddress4;
                            } else {
                                publicMappedAddress5 = publicMappedAddress3 != null ? publicMappedAddress3 : publicMappedAddress4;
                                object3 = publicMappedAddress5;
                            }
                            if (bl3 && !roomState.roomInfo.isHostSymmetric()) {
                                roomState.roomInfo.setHostSymmetric(true);
                                bl2 = Math.abs(n2) <= 100;
                                object3 = bl2 ? NatClass.EASY_SYM : NatClass.HARD_SYM;
                                NatClass natClass = object3;
                                if (this.localNatClass != object3) {
                                    VoxLinkMod.LOGGER.info("[ReversePunch] Update localNat {} -> {} (reverse socket symmetric detected)", (Object)this.localNatClass, object3);
                                    this.localNatClass = object3;
                                    this.remoteNatClass = this.classifyRemoteNat(roomState2);
                                    object2 = this.scenarioTier;
                                    this.scenarioTier = ScenarioTier.classify(this.localNatClass.isSymmetric(), this.remoteNatClass.isSymmetric(), true, true, true);
                                    if (this.scenarioTier != object2) {
                                        VoxLinkMod.LOGGER.info("[ReversePunch] tier {} -> {} (reverse socket symmetric known)", (Object)ScenarioTier.key(object2), (Object)ScenarioTier.key(this.scenarioTier));
                                    }
                                    PunchProfile punchProfile = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
                                    this.switchPunchProfile(punchProfile, "reverse_socket_" + (Object)((Object)this.localNatClass) + "x" + (Object)((Object)this.remoteNatClass));
                                }
                            }
                            object3 = new JsonObject();
                            if (publicMappedAddress5 != null) {
                                object3.addProperty("hostMappedIp", publicMappedAddress5.ip());
                                object3.addProperty("hostMappedPort", publicMappedAddress5.port());
                            }
                            boolean bl4 = bl3 || roomState.roomInfo.isHostSymmetric() || StunDetector.isNatTypeSymmetric(roomState.roomInfo.getNatType());
                            bl2 = bl4;
                            if (bl4) {
                                object3.addProperty("hostSymmetric", true);
                            }
                            boolean bl5 = bl3 && Math.abs(n2) <= 100;
                            boolean bl6 = bl5;
                            if (bl5) {
                                object3.addProperty("hostEasySym", true);
                            }
                            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "reverse_punch_info", (JsonObject)object3, string3).exceptionally(throwable -> {
                                VoxLinkMod.LOGGER.debug("[ReversePunch] reverse_punch_info send failed: {}", (Object)throwable.getMessage());
                                return null;
                            });
                            udpHolePuncher2.setOnPeerPunchReceived(inetSocketAddress -> {
                                String string2 = roomState.roomInfo.getCode();
                                String string3 = roomState.roomInfo.getToken();
                                JsonObject jsonObject = new JsonObject();
                                jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                                jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                                this.signalingClient.sendSignal(string2, string3, true, "peer_port", jsonObject, string3).exceptionally(throwable -> {
                                    VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", (Object)throwable.getMessage());
                                    return null;
                                });
                            });
                            object2 = roomState.roomInfo.getNatType();
                            boolean bl7 = bl3 || StunDetector.isNatTypeSymmetric((String)object2) || roomState.roomInfo.isHostSymmetric();
                            ArrayList<Integer> arrayList = new ArrayList<Integer>();
                            if (jsonObject2.has("joinerMappedPorts") && jsonObject2.get("joinerMappedPorts").isJsonArray()) {
                                for (Object object4 : jsonObject2.getAsJsonArray("joinerMappedPorts")) {
                                    arrayList.add(object4.getAsInt());
                                }
                            }
                            if (arrayList.isEmpty()) {
                                arrayList.add(n4);
                            }
                            int n3 = this.punchProfile().defaultPortRange;
                            if (bl7 && bl2) {
                                n3 = 0;
                            } else if (bl2 && arrayList.size() > 1) {
                                n3 = 0;
                                VoxLinkMod.LOGGER.info("[ReversePunch] Birthday attack mode: host punch {} joiner ports: {}", (Object)arrayList.size(), arrayList);
                            } else {
                                n3 = bl2 ? this.punchProfile().widePortRange : (bl7 ? this.punchProfile().defaultPortRange : (!"moderate".equals(object2) && !"port_restricted_cone".equals(object2) ? this.punchProfile().defaultPortRange : this.punchProfile().portPredictionMaxRange));
                            }
                            VoxLinkMod.LOGGER.info("[ReversePunch] Host punch to joiner {} (range=\u00b1{}, hostNat={}, joinerSym={}, birthdayPorts={})", new Object[]{string4, n3, object2, bl2, arrayList.size() > 1 ? arrayList : "no"});
                            object4 = string3;
                            UdpHolePuncher udpHolePuncher2 = udpHolePuncher2;
                            String string3 = string4;
                            int n4 = n4;
                            udpHolePuncher2.setProfile(this.punchProfile());
                            PunchParams punchParams = PunchParams.fromProfile(this.punchProfile());
                            punchParams.timeoutMs = Math.max(punchParams.timeoutMs, 12000);
                            udpHolePuncher2.setPunchParams(punchParams);
                            ScheduledFuture<?> scheduledFuture = this.scheduler.schedule(() -> this.lambda$handleReverseHolepunchOfferDelayed$37(udpHolePuncher2, (String)object4), (long)this.punchProfile().reverseWindowSec, TimeUnit.SECONDS);
                            if (arrayList.size() > 1) {
                                LinkedHashSet<Integer> linkedHashSet = new LinkedHashSet<Integer>();
                                Iterator iterator = arrayList.iterator();
                                while (iterator.hasNext()) {
                                    int n5 = (Integer)iterator.next();
                                    for (int i = -this.punchProfile().defaultPortRange; i <= this.punchProfile().defaultPortRange; ++i) {
                                        int n6 = n5 + i;
                                        if (n6 <= 0 || n6 > 65535) continue;
                                        linkedHashSet.add(n6);
                                    }
                                }
                                ArrayList<Integer> arrayList2 = new ArrayList<Integer>(linkedHashSet);
                                VoxLinkMod.LOGGER.info("[ReversePunch] Birthday attack: {} ports expanded to {} ports (range {}-{})", new Object[]{arrayList.size(), arrayList2.size(), (Integer)arrayList.get(0) - this.punchProfile().defaultPortRange, (Integer)arrayList.get(arrayList.size() - 1) + this.punchProfile().defaultPortRange});
                                completableFuture = udpHolePuncher2.punchMultiPort(string3, arrayList2);
                            } else {
                                completableFuture = udpHolePuncher2.punchWithPortPrediction(string3, n4, n3, true);
                            }
                            ((CompletableFuture)completableFuture.thenAccept(arg_0 -> this.lambda$handleReverseHolepunchOfferDelayed$39(roomState2, string3, n4, udpHolePuncher2, (String)object4, arg_0))).exceptionally(arg_0 -> this.lambda$handleReverseHolepunchOfferDelayed$40(scheduledFuture, (String)object4, udpHolePuncher2, arg_0));
                        });
                    }
                } else {
                    VoxLinkMod.LOGGER.warn("[ReversePunch] Invalid reverse_holepunch_offer: no mapped address");
                }
            }
        }
    }

    public void handleReversePunchInfo(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && !roomState.roomInfo.isHost()) {
            if (!this.connectionCycleActive.get()) {
                VoxLinkMod.LOGGER.info("[ReversePunch] Not in connection cycle, ignore reverse_punch_info");
            } else {
                UdpHolePuncher udpHolePuncher;
                String string2 = null;
                int n = 0;
                if (jsonObject.has("hostMappedIp") && !jsonObject.get("hostMappedIp").isJsonNull()) {
                    string2 = jsonObject.get("hostMappedIp").getAsString();
                }
                if (jsonObject.has("hostMappedPort") && !jsonObject.get("hostMappedPort").isJsonNull()) {
                    n = jsonObject.get("hostMappedPort").getAsInt();
                }
                boolean bl = jsonObject.has("hostSymmetric") && jsonObject.get("hostSymmetric").getAsBoolean();
                boolean bl2 = jsonObject.has("hostEasySym") && !jsonObject.get("hostEasySym").isJsonNull() && jsonObject.get("hostEasySym").getAsBoolean();
                boolean bl3 = bl2;
                if (bl) {
                    roomState.roomInfo.setHostSymmetric(true);
                }
                if (bl2) {
                    roomState.roomInfo.setHostEasySym(true);
                }
                if ((udpHolePuncher = this.activeHolePunchers.get("joiner_reverse")) != null && udpHolePuncher.isPunching()) {
                    UdpHolePuncher udpHolePuncher2;
                    int n2;
                    int n3 = n;
                    int n4 = roomState.roomInfo.getHostMappedPortDelta();
                    if (n > 0 && n4 != 0 && (n2 = n + n4) > 0 && n2 <= 65535) {
                        n3 = n2;
                    }
                    VoxLinkMod.LOGGER.info("[ReversePunch] Already reverse punching, update target to {}:{}", (Object)string2, (Object)n3);
                    if (string2 != null && n3 > 0) {
                        udpHolePuncher.updateTarget(string2, n3);
                    }
                    if ((udpHolePuncher2 = this.activeHolePunchers.get("joiner")) != null && udpHolePuncher2.isPunching() && string2 != null && n3 > 0) {
                        VoxLinkMod.LOGGER.info("[ReversePunch] Sync update main joiner punch target to {}:{}", (Object)string2, (Object)n3);
                        udpHolePuncher2.updateTarget(string2, n3);
                    }
                } else {
                    if (string2 == null || n <= 0) {
                        string2 = roomState.roomInfo.getHostMappedIp();
                        n = roomState.roomInfo.getHostMappedPort();
                    }
                    if (string2 != null && n > 0) {
                        if (bl) {
                            roomState.roomInfo.setHostSymmetric(true);
                        }
                        VoxLinkMod.LOGGER.info("[ReversePunch] Joiner received reverse_punch_info: {}:{} (hostSym={})", new Object[]{string2, n, bl});
                        ArrayList<UdpHolePuncher> arrayList = new ArrayList<UdpHolePuncher>();
                        ArrayList<String> arrayList2 = new ArrayList<String>();
                        for (Map.Entry<String, UdpHolePuncher> object2 : this.activeHolePunchers.entrySet()) {
                            if (!object2.getKey().startsWith("joiner_birthday_")) continue;
                            arrayList.add(object2.getValue());
                            arrayList2.add(object2.getKey());
                        }
                        if (!arrayList.isEmpty()) {
                            boolean bl4 = arrayList.stream().anyMatch(UdpHolePuncher::isPunching);
                            if (bl4) {
                                VoxLinkMod.LOGGER.info("[BirthdayPunch] Already punching, update target to {}:{}, {} sockets total", new Object[]{string2, n, arrayList.size()});
                                for (UdpHolePuncher udpHolePuncher3 : arrayList) {
                                    if (!udpHolePuncher3.isPunching()) continue;
                                    udpHolePuncher3.updateTarget(string2, n);
                                }
                            } else {
                                VoxLinkMod.LOGGER.info("[BirthdayPunch] Start birthday attack {} sockets punch to {}:{}", new Object[]{arrayList.size(), string2, n});
                                this.startBirthdayPunchPhase2(roomState, arrayList, arrayList2, string2, n, bl, false);
                            }
                        } else {
                            UdpHolePuncher udpHolePuncher4 = this.activeHolePunchers.get("joiner_reverse");
                            if (udpHolePuncher4 != null && udpHolePuncher4.getSocket() != null && !udpHolePuncher4.getSocket().isClosed()) {
                                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch state: localPort={}, punching={}", (Object)udpHolePuncher4.getSocket().getLocalPort(), (Object)udpHolePuncher4.isPunching());
                                udpHolePuncher4.setOnPeerPunchReceived(inetSocketAddress -> {
                                    VoxLinkMod.LOGGER.info("[ReversePunch] Joiner received peer punch packet {}:{} \u2014 send peer_port signal", (Object)inetSocketAddress.getAddress().getHostAddress(), (Object)inetSocketAddress.getPort());
                                    String string = roomState.roomInfo.getCode();
                                    String string2 = roomState.roomInfo.getToken();
                                    JsonObject jsonObject = new JsonObject();
                                    jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                                    jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                                    this.signalingClient.sendSignal(string, string2, false, "peer_port", jsonObject, "host").exceptionally(throwable -> {
                                        VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", (Object)throwable.getMessage());
                                        return null;
                                    });
                                });
                                boolean bl4 = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
                                int n3 = this.punchProfile().defaultPortRange;
                                if (bl4) {
                                    n3 = this.punchProfile().defaultPortRange;
                                    VoxLinkMod.LOGGER.info("[ReversePunch] Joiner is symmetric NAT \u2014 use small range (\u00b130) to open NAT mapping");
                                } else {
                                    n3 = bl ? this.punchProfile().portPredictionMaxRange : this.punchProfile().defaultPortRange;
                                }
                                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner punch to host {}:{} (range=\u00b1{}, joinerSym={}, hostSym={})", new Object[]{string2, n, n3, bl4, bl});
                                Object object = udpHolePuncher4;
                                String string3 = string2;
                                int n4 = n;
                                ((CompletableFuture)udpHolePuncher4.punchWithPortPrediction(string2, n, n3, true).thenAccept(arg_0 -> this.lambda$handleReversePunchInfo$45(roomState, string3, n4, (UdpHolePuncher)object, arg_0))).exceptionally(arg_0 -> this.lambda$handleReversePunchInfo$46((UdpHolePuncher)object, roomState, arg_0));
                            } else {
                                Object[] objectArray = new Object[3];
                                objectArray[0] = udpHolePuncher4 != null;
                                objectArray[1] = udpHolePuncher4 != null ? udpHolePuncher4.getSocket() != null : false;
                                objectArray[2] = udpHolePuncher4 != null && udpHolePuncher4.getSocket() != null ? udpHolePuncher4.getSocket().isClosed() : false;
                                VoxLinkMod.LOGGER.warn("[ReversePunch] No joiner_reverse puncher available (puncher={}, socket={}, closed={})", objectArray);
                                this.showConnectFailed(roomState, "voxlink.connection.reverse_punch_failed");
                            }
                        }
                    } else {
                        VoxLinkMod.LOGGER.warn("[ReversePunch] No host mapped address in reverse_punch_info");
                    }
                }
            }
        }
    }

    public void handleTcpSimopenRequest(String string, JsonObject jsonObject) {
        String string2 = jsonObject.has("joinerMappedIp") ? jsonObject.get("joinerMappedIp").getAsString() : null;
        int n = jsonObject.has("joinerMappedPort") ? jsonObject.get("joinerMappedPort").getAsInt() : 0;
        int n2 = n;
        if (string2 != null && n != 0) {
            if (this.connectionWon.get()) {
                VoxLinkMod.LOGGER.info("[TcpSimOpen] already connected, ignoring tcp_simopen_request");
            } else {
                int n3 = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : 25565;
                int n4 = n3;
                if (P2PBridge.getHostPort() > 0) {
                    VoxLinkMod.LOGGER.info("[TcpSimOpen] Host bridge listening port={}, skip SimOpen", (Object)n3);
                } else {
                    VoxLinkMod.LOGGER.info("[TcpSimOpen] Host received joiner {} request, try TCP connect {}:{}", new Object[]{string, string2, n});
                    ConnectionFallback connectionFallback = this.trackFallback(new ConnectionFallback());
                    connectionFallback.tryTcpSimultaneousOpen(string2, n, n3).thenAccept(connectResult -> {
                        if (connectResult.success && this.connectionWon.compareAndSet(false, true)) {
                            VoxLinkMod.LOGGER.info("[TcpSimOpen] Host connected to joiner via TCP SimOpen!");
                            RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
                            if (roomState != null) {
                                this.connectViaBridge(roomState, (ConnectionFallback.ConnectResult)connectResult);
                            }
                        } else if (connectResult.success) {
                            VoxLinkMod.LOGGER.info("[TcpSimOpen] TCP SimOpen success but connection occupied, ignore");
                        } else {
                            VoxLinkMod.LOGGER.info("[TcpSimOpen] Host TCP SimOpen failed: {}", (Object)connectResult.failureReason);
                        }
                    });
                }
            }
        }
    }

    public void handleHolePunchAnswer(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isHost()) {
            VoxLinkMod.LOGGER.info("Received punch ack from joiner {}", (Object)string);
        }
    }

    public int getEffectiveMaxCycles() {
        if (this.stunProbeResult != null) {
            int n = this.stunProbeResult.reachableStunUrls.size();
            return this.stunProbeResult.natType.isSymmetric() ? Math.max(2, Math.min(n * 2 / 2, this.punchProfile().maxSymCycles)) : Math.max(1, Math.min(n, this.punchProfile().maxCycles));
        }
        return this.punchProfile().fallbackCycles;
    }

    private static boolean sameIpFamily(String string, String string2) {
        if (string == null || string2 == null) {
            return false;
        }
        return string.indexOf(58) >= 0 == string2.indexOf(58) >= 0;
    }

    private NatClass classifyLocalNat() {
        return this.stunProbeResult != null && this.stunProbeResult.natType != null ? NatClass.fromStunProbeResult(this.stunProbeResult.natType) : NatClass.UNKNOWN;
    }

    private NatClass classifyRemoteNat(RoomManager.RoomState roomState) {
        if (roomState != null && roomState.roomInfo != null) {
            boolean bl = roomState.roomInfo.isHostSymmetric();
            boolean bl2 = roomState.roomInfo.isHostEasySym();
            if (bl) {
                return bl2 ? NatClass.EASY_SYM : NatClass.HARD_SYM;
            }
            return NatClass.UNKNOWN;
        }
        return NatClass.UNKNOWN;
    }

    private boolean shouldPrefetchRelay(NatClass natClass, NatClass natClass2) {
        return natClass.isSymmetric() || natClass2.isSymmetric();
    }

    private CompletableFuture<Void> prefetchRelayCandidates(RoomManager.RoomState roomState) {
        if (roomState != null && roomState.roomInfo != null) {
            String string = roomState.roomInfo.getCode();
            String string2 = roomState.roomInfo.getToken();
            return string != null && string2 != null ? ((CompletableFuture)this.signalingClient.pollTopology(string, string2, false, 0).thenAccept(apiResponse -> VoxLinkMod.LOGGER.info("[Connection] Layer2 relay pre-check topology done"))).exceptionally(object -> {
                VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check topology failed: {}", (Object)((Throwable)object).getMessage());
                return null;
            }) : CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(null);
    }

    private boolean isLegacyPeer() {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        return roomState != null && roomState.roomInfo != null ? !roomState.roomInfo.hostSupportsRelay() : false;
    }

    public void runConnectionCycle(RoomManager.RoomState roomState, String string, String string2, String string3, int n, String string4, int n2, int n3) {
        this.savedConnectionState = roomState;
        this.savedConnectionFrom = string != null ? string : "";
        this.savedConnectionHostIpv6 = string2;
        this.savedConnectionHostIp = string3;
        this.savedConnectionHostPort = n;
        this.savedConnectionHostMappedIp = string4;
        this.savedConnectionHostMappedPort = n2;
        if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, skip cycle {}", (Object)(n3 + 1));
        } else {
            int n4 = this.getEffectiveMaxCycles();
            if (n3 >= n4) {
                if (!this.connectionWon.get()) {
                    if (this.shouldContinuousRetry(roomState)) {
                        int n5 = this.continuousRetryRound.incrementAndGet();
                        this.escalateProfileForRound(n5);
                        VoxLinkMod.LOGGER.info("[Connection] Cycle {} done, both support persistent retry (round={}, level={}), reset cycle from 0", new Object[]{n4, n5, this.punchProfile().describeInstance()});
                        ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "\u6301\u7eed\u91cd\u8bd5 round " + n5);
                        this.connectionStartTimeMs = System.currentTimeMillis();
                        if (this.connectionTimeoutFuture != null) {
                            this.connectionTimeoutFuture.cancel(false);
                            this.connectionTimeoutFuture = null;
                        }
                        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.retry_round", (Object[])new Object[]{n5}));
                        this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, 0, 1, n4, 0);
                    } else {
                        ConnectionState.transitionTo(ConnectionState.FAILED, "\u8d85\u8fc7\u6700\u5927\u5468\u671f" + n4);
                        this.showConnectFailed(roomState, "voxlink.connection.max_cycles_exceeded");
                    }
                }
            } else {
                int n6;
                this.connectionWon.set(false);
                ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "\u5468\u671f" + (n3 + 1) + "/" + n4);
                if (n3 == 0) {
                    this.activePunchParams = null;
                    this.localNatClass = this.classifyLocalNat();
                    this.remoteNatClass = this.classifyRemoteNat(roomState);
                    n6 = this.stunProbeResult != null ? this.stunProbeResult.reachableStunUrls.size() : 0;
                    boolean bl = n6 > 0 && n6 <= 3;
                    boolean bl2 = this.localNatClass.isSymmetric();
                    boolean bl3 = this.remoteNatClass.isSymmetric() || roomState.roomInfo.isHostSymmetric();
                    this.scenarioTier = ScenarioTier.classify(bl2, bl3, bl, false, false);
                    PunchProfile punchProfile = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
                    this.switchPunchProfile(punchProfile, "nat_matrix_" + (Object)((Object)this.localNatClass) + "x" + (Object)((Object)this.remoteNatClass));
                    VoxLinkMod.LOGGER.info("[Connection] Layer1 NAT classification: local={} remote={} tier={} -> profile={}", new Object[]{this.localNatClass, this.remoteNatClass, ScenarioTier.key(this.scenarioTier), this.punchProfile().describeInstance()});
                    if (this.shouldPrefetchRelay(this.localNatClass, this.remoteNatClass) && this.relayPrefetchFuture == null) {
                        this.relayPrefetchFuture = this.prefetchRelayCandidates(roomState).exceptionally(throwable -> {
                            VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check failed: {}", (Object)throwable.getMessage());
                            return null;
                        });
                    }
                    this.connectionStartTimeMs = System.currentTimeMillis();
                    int n7 = this.punchProfile().connectionTimeoutSec;
                    boolean bl4 = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
                    boolean bl5 = roomState.roomInfo.isHostSymmetric();
                    if (bl4 || bl5) {
                        n7 = this.punchProfile().symmetricConnectionTimeoutSec;
                        VoxLinkMod.LOGGER.info("[Connection] One side symmetric NAT (joinerSym={}, hostSym={}), global timeout extended to {}s", new Object[]{bl4, bl5, n7});
                    }
                    this.connectionTimeoutSec = n7;
                    if (this.continuousRetryRound.get() == 0) {
                        this.scheduleConnectionTimeout(roomState, n7);
                    }
                }
                n6 = n3 + 1;
                if (n3 == 0 && this.stunProbeResult == null) {
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.probing", (Object[])new Object[0]));
                    RoomManager.RoomState roomState2 = roomState;
                    this.scheduler.schedule(() -> {
                        String string;
                        if (this.roomManager.currentRoom.get() == roomState2 && !this.connectionWon.get() && (string = roomState.roomInfo.getConnectionMode()) != null && string.equals(I18n.func_135052_a((String)"voxlink.connection.probing", (Object[])new Object[0]))) {
                            roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
                        }
                    }, 15L, TimeUnit.SECONDS);
                } else {
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
                }
                this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n6, n4, 0);
            }
        }
    }

    private void scheduleConnectionTimeout(RoomManager.RoomState roomState, int n) {
        if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
        }
        int n2 = n;
        this.connectionTimeoutFuture = this.scheduler.schedule(() -> {
            if (!this.connectionWon.get() && this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == roomState) {
                VoxLinkMod.LOGGER.warn("[Connection] Global timeout ({}s), enter failure handling (persistent retry/relay/final)", (Object)n2);
                this.showConnectFailed(roomState, "voxlink.connection.global_timeout");
            }
        }, (long)n2, TimeUnit.SECONDS);
    }

    private void extendConnectionTimeoutIfNeeded(RoomManager.RoomState roomState) {
        if (this.connectionStartTimeMs != 0L && this.stunProbeResult != null) {
            long l;
            long l2;
            boolean bl = this.stunProbeResult.natType.isSymmetric();
            boolean bl2 = roomState.roomInfo.isHostSymmetric();
            if ((bl || bl2) && this.connectionTimeoutSec < this.punchProfile().symmetricConnectionTimeoutSec && (l2 = (long)this.punchProfile().symmetricConnectionTimeoutSec * 1000L - (l = System.currentTimeMillis() - this.connectionStartTimeMs)) > 0L) {
                this.connectionTimeoutSec = this.punchProfile().symmetricConnectionTimeoutSec;
                VoxLinkMod.LOGGER.info("[Connection] NAT probe found symmetric NAT (local or remote), global timeout extended to {}s ({}ms left)", (Object)75, (Object)l2);
                this.scheduleConnectionTimeout(roomState, (int)(l2 / 1000L) + 1);
            }
        }
    }

    public void tryConnectionStep(RoomManager.RoomState roomState, String string, String string2, String string3, int n, String string4, int n2, int n3, int n4, int n5, int n6) {
        if (this.roomManager.currentRoom.get() == roomState) {
            if (this.connectionWon.get()) {
                VoxLinkMod.LOGGER.info("[Connection] Connected, skip Wave step (cycle={}, step={})", (Object)(n3 + 1), (Object)n6);
            } else {
                switch (n6) {
                    case 0: {
                        Object object;
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: LAN+IPv6+UDP parallel (cycle {}/{})", (Object)n4, (Object)n5);
                        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
                        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
                        ArrayList<CompletionStage> arrayList = new ArrayList<CompletionStage>();
                        String string5 = roomState.roomInfo.getHostLocalIp();
                        boolean bl = string5 != null && !string5.isEmpty() && StunDetector.isSameLan(string5);
                        boolean bl2 = roomState.roomInfo.isSameCgnat();
                        if (string5 != null && !string5.isEmpty() && (bl || bl2)) {
                            object = bl ? "LAN" : "CGNAT\u540c\u516c\u7f51IP";
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: Detected {} (localIp={}), try direct connect", object, (Object)string5);
                            ConnectionFallback connectionFallback = this.trackFallback(new ConnectionFallback());
                            arrayList.add(connectionFallback.tryIpv4Direct(string5, n).thenAccept(arg_0 -> this.lambda$tryConnectionStep$53(roomState, (String)object, atomicBoolean, string5, n, arg_0)));
                            if (bl2 && !bl) {
                                int n7 = roomState.roomInfo.getHostPort();
                                VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT also try localIp MC port {}:{}", (Object)string5, (Object)n7);
                                ConnectionFallback connectionFallback2 = this.trackFallback(new ConnectionFallback());
                                arrayList.add(connectionFallback2.tryIpv4Direct(string5, n7).thenAccept(connectResult -> {
                                    if (this.roomManager.currentRoom.get() == roomState && connectResult.success && this.connectionWon.compareAndSet(false, true)) {
                                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT localIp MC port won");
                                        atomicBoolean.set(true);
                                        this.connectViaBridge(roomState, (ConnectionFallback.ConnectResult)connectResult);
                                    }
                                }));
                            }
                        }
                        if (string2 != null && !string2.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: Parallel try IPv6 direct connect");
                            object = this.trackFallback(new ConnectionFallback());
                            arrayList.add(((ConnectionFallback)object).tryIpv6Direct(string2, n).thenAccept(connectResult -> {
                                if (this.roomManager.currentRoom.get() == roomState && connectResult.success && this.connectionWon.compareAndSet(false, true)) {
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: IPv6 direct connect won");
                                    atomicBoolean.set(true);
                                    this.connectViaBridge(roomState, (ConnectionFallback.ConnectResult)connectResult);
                                }
                            }));
                        } else if (string2 != null && !string2.isEmpty()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip IPv6 (no local IPv6 connectivity)");
                        }
                        object = PunchStrategySelector.select(this.localNatClass, this.remoteNatClass, n3, this.isLegacyPeer());
                        VoxLinkMod.LOGGER.info("[Connection] Layer4 smart schedule cycle={} strategy={} localNat={} remoteNat={}", new Object[]{n3, object, this.localNatClass, this.remoteNatClass});
                        if (this.punchSkipDirect()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip UDP punch (tuner FIREWALL_DETECTED)");
                        } else {
                            switch ((PunchStrategy) object) {
                                case DIRECT_ONLY: {
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_ONLY)");
                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                    if (n3 != 0 || !this.reversePunchAttempted.compareAndSet(false, true)) break;
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle0 parallel reverse punch (1.0.0 compat)");
                                    this.startReversePunch(roomState);
                                    break;
                                }
                                case DIRECT_WITH_REVERSE_PARALLEL: {
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_WITH_REVERSE_PARALLEL cycle={})", (Object)n3);
                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                    if (n3 < 1 || !this.reversePunchAttempted.compareAndSet(false, true)) break;
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle{} start reverse punch (parallel)", (Object)n3);
                                    this.startReversePunch(roomState);
                                    break;
                                }
                                case REVERSE_FIRST: {
                                    if (n3 == 0 && this.reversePunchAttempted.compareAndSet(false, true)) {
                                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_FIRST start reverse punch first");
                                        this.startReversePunch(roomState);
                                    }
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (REVERSE_FIRST cycle={})", (Object)n3);
                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                    break;
                                }
                                case RELAY_FALLBACK_FAST: {
                                    if (n3 < 2) {
                                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (RELAY_FALLBACK_FAST cycle={})", (Object)n3);
                                        this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                        if (n3 < 1 || !this.reversePunchAttempted.compareAndSet(false, true)) break;
                                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: RELAY_FALLBACK_FAST cycle{} start reverse birthday", (Object)n3);
                                        this.startReversePunch(roomState);
                                        break;
                                    }
                                    VoxLinkMod.LOGGER.info("[Connection] Layer4 RELAY_FALLBACK_FAST cycle{}>=2 keep punch (relay manual only)", (Object)n3);
                                    if (this.reversePunchAttempted.compareAndSet(false, true)) {
                                        this.startReversePunch(roomState);
                                    }
                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                    break;
                                }
                                case REVERSE_ONLY: {
                                    if (!this.reversePunchAttempted.compareAndSet(false, true)) break;
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_ONLY start reverse punch (skip forward)");
                                    this.startReversePunch(roomState);
                                    break;
                                }
                                case PARALLEL_FROM_START: {
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: PARALLEL_FROM_START forward+reverse (cycle={})", (Object)n3);
                                    if (this.reversePunchAttempted.compareAndSet(false, true)) {
                                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: PARALLEL_FROM_START start reverse punch");
                                        this.startReversePunch(roomState);
                                    }
                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                    break;
                                }
                                case REVERSE_THEN_FORWARD: {
                                    if (this.reversePunchAttempted.compareAndSet(false, true)) {
                                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_THEN_FORWARD start reverse punch");
                                        this.startReversePunch(roomState);
                                    }
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_THEN_FORWARD forward+reverse (cycle={})", (Object)n3);
                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5);
                                }
                            }
                        }
                        if (!arrayList.isEmpty()) {
                            CompletableFuture.allOf(arrayList.toArray(new CompletableFuture[0])).thenAccept(void_ -> {
                                if (this.roomManager.currentRoom.get() == roomState && !atomicBoolean.get()) {
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 1 TCP all failed, enter Wave 2");
                                    this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                }
                            });
                        } else {
                            this.scheduler.schedule(() -> {
                                if (this.roomManager.currentRoom.get() == roomState && this.connectionCycleActive.get()) {
                                    this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                }
                            }, 5L, TimeUnit.SECONDS);
                        }
                        return;
                    }
                    case 1: {
                        String string6;
                        int n8;
                        Object object;
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP fallback parallel (cycle{}/{})", (Object)n4, (Object)n5);
                        ConnectionState.transitionTo(ConnectionState.TCP_FALLBACK, "Wave 2 TCP\u515c\u5e95");
                        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
                        new AtomicBoolean(false);
                        ArrayList<CompletionStage> arrayList = new ArrayList<CompletionStage>();
                        if (string4 != null && !string4.isEmpty() && n2 > 0) {
                            object = this.trackFallback(new ConnectionFallback());
                            n8 = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : n;
                            String string7 = roomState.roomInfo.getMyMappedIp();
                            int n9 = roomState.roomInfo.getMyMappedPort();
                            if (string7 != null && n9 > 0 && this.signalingClient != null) {
                                JsonObject jsonObject = new JsonObject();
                                jsonObject.addProperty("joinerMappedIp", string7);
                                jsonObject.addProperty("joinerMappedPort", (Number)n9);
                                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "tcp_simopen_request", jsonObject, "host");
                                VoxLinkMod.LOGGER.info("[Connection] Wave 2: Send tcp_simopen_request to host ({}:{})", (Object)string7, (Object)n9);
                            }
                            int n10 = n > 0 ? n : n2;
                            arrayList.add(((ConnectionFallback)object).tryTcpSimultaneousOpen(string4, n10, n8));
                        }
                        if (string4 != null && !string4.isEmpty() && n2 > 0) {
                            object = this.trackFallback(new ConnectionFallback());
                            n8 = n > 0 ? n : n2;
                            arrayList.add(((ConnectionFallback)object).tryIpv4Direct(string4, n8));
                        }
                        if (string3 != null && !string3.isEmpty()) {
                            object = this.trackFallback(new ConnectionFallback());
                            String string8 = string3;
                            int n11 = n;
                            arrayList.add(((ConnectionFallback)object).tryIpv4Direct(string3, n).whenComplete((connectResult, throwable) -> {
                                if (throwable == null && connectResult != null && !connectResult.success && this.roomManager.currentRoom.get() == roomState) {
                                    this.addressBlacklist.recordDirectFailure(new InetSocketAddress(string8, n11));
                                }
                            }));
                        }
                        if (roomState.roomInfo.isSameCgnat() && (string6 = roomState.roomInfo.getHostLocalIp()) != null && !string6.isEmpty()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try hostLocalIp {}:{}", (Object)string6, (Object)n);
                            object = this.trackFallback(new ConnectionFallback());
                            arrayList.add(((ConnectionFallback)object).tryIpv4Direct(string6, n));
                            int n12 = roomState.roomInfo.getHostPort();
                            VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try localIp MC port {}:{}", (Object)string6, (Object)n12);
                            ConnectionFallback connectionFallback = this.trackFallback(new ConnectionFallback());
                            arrayList.add(connectionFallback.tryIpv4Direct(string6, n12));
                        }
                        if (string2 != null && !string2.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 2: Try IPv6 direct connection");
                            object = this.trackFallback(new ConnectionFallback());
                            arrayList.add(((ConnectionFallback)object).tryIpv6Direct(string2, n));
                        }
                        if (arrayList.isEmpty()) {
                            VoxLinkMod.LOGGER.info("[Connection] Wave 2: No TCP fallback available, enter next cycle");
                            this.advanceToNextCycle(roomState, string, string2, string3, n, string4, n2, n3, n5);
                            return;
                        }
                        for (CompletionStage completionStage : arrayList) {
                            completionStage.thenAccept(connectResult -> {
                                ConnectionFallback.ConnectResult connectResult2 = connectResult;
                                if (this.roomManager.currentRoom.get() == roomState && connectResult2.success && this.connectionWon.compareAndSet(false, true)) {
                                    VoxLinkMod.LOGGER.info("[Connection] Wave 2: {} won", (Object)connectResult2.errorCode);
                                    this.connectViaBridge(roomState, (ConnectionFallback.ConnectResult)connectResult);
                                }
                            });
                        }
                        CompletableFuture.allOf(arrayList.toArray(new CompletableFuture[0])).thenAccept(void_ -> {
                            if (this.roomManager.currentRoom.get() == roomState && !this.connectionWon.get()) {
                                VoxLinkMod.LOGGER.info("[Connection] Wave 2: All TCP fallbacks failed, enter next cycle");
                                this.advanceToNextCycle(roomState, string, string2, string3, n, string4, n2, n3, n5);
                            }
                        });
                        return;
                    }
                }
                this.advanceToNextCycle(roomState, string, string2, string3, n, string4, n2, n3, n5);
            }
        }
    }

    public void tryUdpPunch(RoomManager.RoomState roomState, String string, String string2, String string3, int n, String string4, int n2, int n3, int n4, int n5) {
        this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void tryUdpPunch(RoomManager.RoomState roomState, String string, String string2, String string3, int n, String string4, int n2, int n3, int n4, int n5, int n6) {
        if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Already connected, skip UDP punch (cycle={}, attempt={})", (Object)(n3 + 1), (Object)n6);
        } else {
            Object object2;
            StunProbe.PublicMappedAddress publicMappedAddress;
            StunProbe.PublicMappedAddress publicMappedAddress2;
            roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.connecting", (Object[])new Object[0]));
            VoxLinkMod.LOGGER.info("[Connection] UDP punch cycle{}/{}, attempt{}/{}", new Object[]{n4, n5, n6, 3});
            boolean bl = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
            boolean bl2 = this.stunProbeResult != null && this.stunProbeResult.natType.isHardSymmetric();
            boolean bl3 = roomState.roomInfo.isHostSymmetric();
            boolean bl4 = bl3 && !roomState.roomInfo.isHostEasySym();
            boolean bl5 = bl4;
            if (bl && bl3) {
                if (!bl2 && !bl4) {
                    VoxLinkMod.LOGGER.info("[Connection] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");
                } else {
                    VoxLinkMod.LOGGER.info("[Connection] Both symmetric NAT with HardSym (joinerHard={}, hostHard={}), try Birthday Attack+port prediction first, fallback to Relay on failure", (Object)bl2, (Object)bl4);
                }
            }
            UdpHolePuncher udpHolePuncher = this.activeHolePunchers.get("joiner");
            UdpHolePuncher udpHolePuncher2 = this.activeHolePunchers.get("joiner_reuse");
            if (udpHolePuncher2 != null && udpHolePuncher2.getSocket() != null && !udpHolePuncher2.getSocket().isClosed()) {
                this.activeHolePunchers.put("joiner", udpHolePuncher2);
                VoxLinkMod.LOGGER.info("[Connection] Reuse punch socket (port={})", (Object)udpHolePuncher2.getSocket().getLocalPort());
            } else {
                if (udpHolePuncher != null) {
                    try {
                        udpHolePuncher.close();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
                this.activeHolePunchers.remove("joiner_reuse");
                udpHolePuncher2 = new UdpHolePuncher();
                this.applyPunchTemplate(udpHolePuncher2);
                try {
                    udpHolePuncher2.createSocket();
                }
                catch (Exception exception) {
                    VoxLinkMod.LOGGER.error("[Connection] Failed to create UDP punch socket: {}", (Object)exception.getMessage());
                    this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                    return;
                }
                int n7 = udpHolePuncher2.getSocket().getLocalPort();
                this.activeHolePunchers.put("joiner", udpHolePuncher2);
                this.activeHolePunchers.put("joiner_reuse", udpHolePuncher2);
            }
            StunProbe.PublicMappedAddress publicMappedAddress3 = null;
            boolean bl6 = false;
            int n8 = 0;
            List<String> list = StunDetector.getAllStunUrls();
            StunProbe.PublicMappedAddress[] publicMappedAddressArray = StunProbe.discoverMappedAddressQuad(udpHolePuncher2.getSocket(), list.get(0), list.get(1), list.get(2), list.get(3));
            StunProbe.PublicMappedAddress publicMappedAddress4 = null;
            StunProbe.PublicMappedAddress publicMappedAddress5 = null;
            StunProbe.PublicMappedAddress publicMappedAddress6 = publicMappedAddressArray[0] != null ? publicMappedAddressArray[0] : (publicMappedAddress2 = (publicMappedAddress4 = publicMappedAddressArray[2] != null ? publicMappedAddressArray[2] : publicMappedAddressArray[3]));
            StunProbe.PublicMappedAddress publicMappedAddress7 = publicMappedAddressArray[1] != null ? publicMappedAddressArray[1] : (publicMappedAddress = (publicMappedAddress5 = publicMappedAddressArray[3] != null ? publicMappedAddressArray[3] : publicMappedAddressArray[2]));
            if (publicMappedAddress4 != null && publicMappedAddress5 != null) {
                if (ConnectionManager.sameIpFamily(publicMappedAddress4.ip(), publicMappedAddress5.ip()) && publicMappedAddress4.port() != publicMappedAddress5.port()) {
                    bl6 = true;
                    n8 = publicMappedAddress5.port() - publicMappedAddress4.port();
                    VoxLinkMod.LOGGER.info("[Connection] Joiner punch socket STUN: symmetric NAT ({} vs {}, delta={})", new Object[]{publicMappedAddress4.port(), publicMappedAddress5.port(), n8});
                }
                publicMappedAddress3 = publicMappedAddress5;
            } else {
                publicMappedAddress3 = publicMappedAddress4 != null ? publicMappedAddress4 : publicMappedAddress5;
                object2 = publicMappedAddress3;
            }
            if (bl6 && this.localNatClass != NatClass.HARD_SYM && this.localNatClass != NatClass.EASY_SYM) {
                object2 = this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() ? NatClass.EASY_SYM : NatClass.HARD_SYM;
                VoxLinkMod.LOGGER.info("[Connection] Update localNat {} -> {} (punch socket symmetric detected)", (Object)this.localNatClass, object2);
                this.localNatClass = object2;
                this.remoteNatClass = this.classifyRemoteNat(roomState);
                PunchProfile punchProfile = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
                this.switchPunchProfile(punchProfile, "punch_socket_sym_" + (Object)((Object)this.localNatClass) + "x" + (Object)((Object)this.remoteNatClass));
                VoxLinkMod.LOGGER.info("[Connection] Probe upgrade: local={} remote={} -> profile={}", new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()});
            }
            if (publicMappedAddress3 == null) {
                publicMappedAddress3 = udpHolePuncher2.discoverMappedAddress(StunDetector.getAllStunUrls());
            }
            if (publicMappedAddress3 == null) {
                VoxLinkMod.LOGGER.warn("[Connection] Punch socket STUN failed, try temp socket fallback (attempt{})", (Object)n6);
                object2 = null;
                try {
                    object2 = new DatagramSocket();
                    ((DatagramSocket)object2).setSoTimeout(1000);
                    publicMappedAddress3 = StunProbe.discoverMappedAddress((DatagramSocket)object2, StunDetector.getAllStunUrls());
                }
                catch (Exception exception) {
                    VoxLinkMod.LOGGER.warn("[Connection] Temp socket STUN also failed: {}", (Object)exception.getMessage());
                }
                finally {
                    if (object2 != null && !((DatagramSocket)object2).isClosed()) {
                        ((DatagramSocket)object2).close();
                    }
                }
            }
            if (publicMappedAddress3 != null) {
                long l;
                String string5;
                boolean bl7;
                Object object3;
                VoxLinkMod.LOGGER.info("[Connection] My mapped address: {}:{} (attempt{})", new Object[]{publicMappedAddress3.ip(), publicMappedAddress3.port(), n6});
                if (roomState.roomInfo.getClientId() != null && n6 == 1) {
                    object3 = this.stunProbeResult != null ? this.stunProbeResult.natType.key : "unknown";
                    bl7 = VoxLinkMod.getConfig().isRelayEnabled();
                    this.signalingClient.registerRelayPeer(roomState.roomInfo.getClientId(), roomState.roomInfo.getCode(), (String)object3, publicMappedAddress3.ip(), publicMappedAddress3.port(), bl7);
                    this.scheduleRelayRegistrationRenewal(roomState, (String)object3, publicMappedAddress3.ip(), publicMappedAddress3.port());
                }
                object3 = new JsonObject();
                object3.addProperty("joinerMappedIp", publicMappedAddress3.ip());
                object3.addProperty("joinerMappedPort", (Number)publicMappedAddress3.port());
                boolean bl8 = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric() || bl6;
                bl7 = bl8;
                if (bl8) {
                    object3.addProperty("joinerSymmetric", Boolean.valueOf(true));
                }
                boolean bl9 = this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() || bl6;
                boolean bl10 = bl9;
                if (bl9) {
                    object3.addProperty("joinerEasySym", Boolean.valueOf(true));
                }
                if (n8 != 0) {
                    object3.addProperty("joinerMappedPortDelta", (Number)n8);
                    VoxLinkMod.LOGGER.info("[Connection] punch_info with port offset: delta={}", (Object)n8);
                }
                if (bl6) {
                    VoxLinkMod.LOGGER.info("[Connection] Override joinerSymmetric=true with punch socket dual STUN result");
                }
                if ((string5 = StunDetector.getLocalIpAddress()) != null && !string5.isEmpty()) {
                    object3.addProperty("joinerLocalIp", string5);
                    VoxLinkMod.LOGGER.info("[Connection] punch_info includes joiner LAN IP: {}", (Object)string5);
                }
                if (roomState.roomInfo.isSameCgnat() && (roomState.roomInfo.getHostLocalIp() == null || roomState.roomInfo.getHostLocalIp().isEmpty())) {
                    object3.addProperty("requestHostLocalIp", Boolean.valueOf(true));
                    VoxLinkMod.LOGGER.info("[Connection] CGNAT scenario request host to send LAN IP");
                }
                if ((l = roomState.roomInfo.getJoinerOfferRecvMs()) > 0L) {
                    object3.addProperty("joinerOfferRecvMs", (Number)l);
                }
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "punch_info", (JsonObject)object3, "host");
                if (this.connectionWon.get()) {
                    VoxLinkMod.LOGGER.info("[Connection] Connection succeeded during STUN probe, abort attempt{}", (Object)n6);
                    udpHolePuncher2.close();
                } else {
                    this.activeHolePunchers.put("joiner", udpHolePuncher2);
                    udpHolePuncher2.setOnPeerPunchReceived(inetSocketAddress -> {
                        String string = roomState.roomInfo.getCode();
                        String string2 = roomState.roomInfo.getToken();
                        JsonObject jsonObject = new JsonObject();
                        jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                        jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                        this.signalingClient.sendSignal(string, string2, false, "peer_port", jsonObject, "host").exceptionally(throwable -> {
                            VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", (Object)throwable.getMessage());
                            return null;
                        });
                    });
                    String string6 = roomState.roomInfo.getHostMappedIp();
                    int n9 = roomState.roomInfo.getHostMappedPort();
                    if (string6 == null || n9 <= 0) {
                        string6 = string4;
                        n9 = n2;
                    }
                    String string7 = string6 != null ? string6 : string3;
                    int n10 = n9 > 0 ? n9 : n;
                    int n11 = roomState.roomInfo.getHostMappedPortDelta();
                    if (roomState.roomInfo.isHostSymmetric() && roomState.roomInfo.getHostMappedIp() != null) {
                        VoxLinkMod.LOGGER.info("[Connection] Punch target keeps host mapped port {} (delta={} skipped: \u540csocket\u53ccSTUN\u5dee\u503c\u975e\u8de8socket\u6b65\u957f)", new Object[]{n10, n11});
                    }
                    String string8 = string7;
                    int n12 = n10;
                    VoxLinkMod.LOGGER.info("[Connection] UDP punch target: {}:{} (hostMappedIp={}, hostMappedPort={}, delta={}, hostIp={}, hostPort={}, attempt{})", new Object[]{string8, n12, string4, n2, n11, string3, n, n6});
                    ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "\u5c1d\u8bd5" + n6 + "/3 \u76ee\u6807" + string8 + ":" + n12);
                    if (string7 != null && !string7.isEmpty()) {
                        UdpHolePuncher udpHolePuncher3 = udpHolePuncher2;
                        InetSocketAddress inetSocketAddress2 = new InetSocketAddress(string8, n12);
                        if (this.addressBlacklist.isBlacklisted(inetSocketAddress2)) {
                            VoxLinkMod.LOGGER.info("[Connection] Target {}:{} in blacklist, skip UDP punch", (Object)string8, (Object)n12);
                            udpHolePuncher3.close();
                            this.activeHolePunchers.remove("joiner");
                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                        } else if (this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() && roomState.roomInfo.isHostEasySym()) {
                            int n13 = this.continuousRetryRound.get() > 0 ? this.punchProfile().easySymMutualRetrySocketCount : this.punchProfile().easySymMutualSocketCount;
                            VoxLinkMod.LOGGER.info("Both EasySym, start mutual punch ({} socket x +/-20)", (Object)n13);
                            UdpHolePuncher udpHolePuncher4 = udpHolePuncher3;
                            ((CompletableFuture)udpHolePuncher4.punchEasySymDual(string8, n12, this.stunProbeResult.natType, StunProbe.NatType.SYMMETRIC_EASY_INC, n13).thenAccept(punchResult -> {
                                if (!punchResult.isSuccess()) {
                                    PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                                    this.lastPunchResult = punchResult.withReason(failureReason);
                                    VoxLinkMod.LOGGER.info("[Connection] cycle={} EasySym mutual punch failed reason={} recvPunch={} recvAck={}", new Object[]{n3, failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                                    this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, n3 + 1, n5, failureReason, this.lastPunchResult);
                                    udpHolePuncher4.stopPunch();
                                    if (this.roomManager.currentRoom.get() != roomState) {
                                        this.connectionCycleActive.set(false);
                                        ConnectionHelper.resetConnecting();
                                    } else {
                                        this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                    }
                                } else {
                                    DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                                    if (this.roomManager.currentRoom.get() == roomState && this.connectionWon.compareAndSet(false, true)) {
                                        udpHolePuncher4.markSocketTransferred();
                                        this.stopAllPunchingAfterHostBridge();
                                        udpHolePuncher4.stopPunch();
                                        InetSocketAddress inetSocketAddress2 = udpHolePuncher4.getActualRemoteAddress();
                                        InetSocketAddress inetSocketAddress3 = inetSocketAddress2 != null ? inetSocketAddress2 : inetSocketAddress2;
                                        this.scheduler.submit(() -> {
                                            try {
                                                this.establishUdpTransport(roomState, datagramSocket, udpHolePuncher4, inetSocketAddress3, "joiner", false, null);
                                            }
                                            catch (Exception exception) {
                                                VoxLinkMod.LOGGER.error("[Connection] EasySym mutual punch transport failed: {}", (Object)exception.getMessage());
                                                udpHolePuncher4.close();
                                                this.showConnectFailed(roomState, "voxlink.connection.transport_failed");
                                            }
                                        });
                                    } else {
                                        try {
                                            udpHolePuncher4.close();
                                        }
                                        catch (Exception exception) {
                                            // empty catch block
                                        }
                                    }
                                }
                            })).exceptionally(object -> {
                                VoxLinkMod.LOGGER.warn("[Connection] EasySym mutual punch failed: {}", (Object)((Throwable)object).getMessage());
                                udpHolePuncher4.stopPunch();
                                if (this.roomManager.currentRoom.get() != roomState) {
                                    this.connectionCycleActive.set(false);
                                    ConnectionHelper.resetConnecting();
                                    return null;
                                }
                                this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                return null;
                            });
                        } else {
                            boolean bl11 = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric() || bl6;
                            boolean bl12 = !roomState.roomInfo.isHostSymmetric() && ("moderate".equals(roomState.roomInfo.getNatType()) || "port_restricted_cone".equals(roomState.roomInfo.getNatType()) || "restricted_cone".equals(roomState.roomInfo.getNatType()) || "full_cone".equals(roomState.roomInfo.getNatType()));
                            int n14 = this.punchProfile().defaultPortRange;
                            int n15 = roomState.roomInfo.getHostMappedPortRange();
                            n14 = bl11 && bl12 ? 0 : (roomState.roomInfo.isHostSymmetric() ? (n11 != 0 ? Math.max(n15 > 0 ? n15 : this.punchProfile().widePortRange, this.punchProfile().maxPortRange) : (n3 == 0 ? this.punchProfile().maxPortRange : this.punchProfile().portPredictionMaxRange)) : (n3 == 0 ? this.punchProfile().defaultPortRange : (n3 == 1 ? this.punchProfile().widePortRange : this.punchProfile().maxPortRange)));
                            VoxLinkMod.LOGGER.info("[Connection] Punch mode: cycle={}, portRange={} (hostSym={}, joinerSym={})", new Object[]{n3, n14, roomState.roomInfo.isHostSymmetric(), bl11});
                            if (n14 > 0) {
                                VoxLinkMod.LOGGER.info("[Connection] Port prediction (range=+/-{}) (attempt{}, hostSym={}, joinerSym={}, hostNat={})", new Object[]{n14, n6, roomState.roomInfo.isHostSymmetric(), bl11, roomState.roomInfo.getNatType()});
                            }
                            int n16 = 0;
                            if (bl11) {
                                n16 = roomState.roomInfo.isHostSymmetric() ? this.punchProfile().hardSymSocketCount : this.punchProfile().joinerSymSocketCount;
                                VoxLinkMod.LOGGER.info("[Connection] Joiner symmetric NAT, create {} multi-socket punches", (Object)n16);
                            }
                            ArrayList<UdpHolePuncher> arrayList = new ArrayList<UdpHolePuncher>();
                            AtomicBoolean atomicBoolean = new AtomicBoolean(false);
                            for (int i = 0; i < n16; ++i) {
                                UdpHolePuncher udpHolePuncher5 = new UdpHolePuncher();
                                this.applyPunchTemplate(udpHolePuncher5);
                                try {
                                    udpHolePuncher5.createSocket();
                                }
                                catch (Exception exception) {
                                    continue;
                                }
                                udpHolePuncher5.setOnPeerPunchReceived(inetSocketAddress -> {
                                    String string = roomState.roomInfo.getCode();
                                    String string2 = roomState.roomInfo.getToken();
                                    JsonObject jsonObject = new JsonObject();
                                    jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                                    jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                                    this.signalingClient.sendSignal(string, string2, false, "peer_port", jsonObject, "host").exceptionally(throwable -> {
                                        VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", (Object)throwable.getMessage());
                                        return null;
                                    });
                                });
                                arrayList.add(udpHolePuncher5);
                                this.activeHolePunchers.put("joiner_ms_" + i, udpHolePuncher5);
                            }
                            if (arrayList.isEmpty()) {
                                VoxLinkMod.LOGGER.info("[Connection] Cone side reuse STUN socket punch (port={})", (Object)udpHolePuncher2.getSocket().getLocalPort());
                                udpHolePuncher2.setOnPeerPunchReceived(inetSocketAddress -> {
                                    String string = roomState.roomInfo.getCode();
                                    String string2 = roomState.roomInfo.getToken();
                                    JsonObject jsonObject = new JsonObject();
                                    jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                                    jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                                    this.signalingClient.sendSignal(string, string2, false, "peer_port", jsonObject, "host").exceptionally(throwable -> {
                                        VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", (Object)throwable.getMessage());
                                        return null;
                                    });
                                });
                                ((CompletableFuture)udpHolePuncher2.punchWithPortPrediction(string8, n12, n14).thenAccept(punchResult -> {
                                    if (!punchResult.isSuccess()) {
                                        PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                                        this.lastPunchResult = punchResult.withReason(failureReason);
                                        VoxLinkMod.LOGGER.info("[Connection] cycle={} UDP punch failed (attempt{}/{}) reason={} recvPunch={} recvAck={}", new Object[]{n3, n6, 3, failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                                        this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, n3 + 1, n5, failureReason, this.lastPunchResult);
                                        udpHolePuncher3.stopPunch();
                                        if (this.roomManager.currentRoom.get() != roomState) {
                                            try {
                                                udpHolePuncher3.close();
                                            }
                                            catch (Exception exception) {
                                                // empty catch block
                                            }
                                            this.activeHolePunchers.remove("joiner");
                                            this.connectionCycleActive.set(false);
                                            ConnectionHelper.resetConnecting();
                                        } else if (this.activeHolePunchers.get("joiner") != udpHolePuncher3) {
                                            VoxLinkMod.LOGGER.info("[Connection] Puncher replaced, no retry");
                                        } else if (punchResult.firewallDetected) {
                                            VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP, skip retry enter Wave 2 TCP fallback");
                                            try {
                                                udpHolePuncher3.close();
                                            }
                                            catch (Exception exception) {
                                                // empty catch block
                                            }
                                            this.activeHolePunchers.remove("joiner");
                                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                        } else if (n6 < this.punchProfile().punchMaxAttempts) {
                                            long l = (long)this.punchProfile().punchRetryDelayMs * (1L << Math.min(n6 - 1, 4));
                                            VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{l, n6 + 1, 3});
                                            this.scheduler.schedule(() -> {
                                                if (this.roomManager.currentRoom.get() == roomState && this.connectionCycleActive.get() && this.activeHolePunchers.get("joiner") == udpHolePuncher3) {
                                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, n6 + 1);
                                                }
                                            }, l, TimeUnit.MILLISECONDS);
                                        } else {
                                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                        }
                                    } else {
                                        DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                                        if (this.roomManager.currentRoom.get() == roomState && this.connectionWon.compareAndSet(false, true)) {
                                            String string6 = string8 != null && string8.contains(":") ? "[" + string8 + "]:" + n12 : string8 + ":" + n12;
                                            VoxLinkMod.LOGGER.info("[Connection] UDP punch success {} (attempt {})", (Object)string6, (Object)n6);
                                            udpHolePuncher3.markSocketTransferred();
                                            this.stopAllPunchingAfterHostBridge();
                                            udpHolePuncher3.stopPunch();
                                            DatagramSocket datagramSocket2 = datagramSocket;
                                            UdpHolePuncher udpHolePuncher2 = udpHolePuncher3;
                                            InetSocketAddress inetSocketAddress = udpHolePuncher2.getActualRemoteAddress();
                                            if (inetSocketAddress == null) {
                                                inetSocketAddress = new InetSocketAddress(string8, n12);
                                            }
                                            InetSocketAddress inetSocketAddress2 = inetSocketAddress;
                                            this.scheduler.submit(() -> {
                                                try {
                                                    this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher2, inetSocketAddress2, "joiner", false, null);
                                                }
                                                catch (Exception exception) {
                                                    VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", (Object)exception.getMessage());
                                                    try {
                                                        udpHolePuncher2.close();
                                                    }
                                                    catch (Exception exception2) {
                                                        // empty catch block
                                                    }
                                                    this.showConnectFailed(roomState, "voxlink.connection.transport_failed");
                                                }
                                            });
                                        } else {
                                            try {
                                                udpHolePuncher3.close();
                                            }
                                            catch (Exception exception) {
                                                // empty catch block
                                            }
                                        }
                                    }
                                })).exceptionally(object -> {
                                    VoxLinkMod.LOGGER.warn("[Connection] UDP punch failed (cycle {}/{}, attempt {}/{}): {}", new Object[]{n4, n5, n6, 3, ((Throwable)object).getMessage()});
                                    udpHolePuncher3.stopPunch();
                                    if (this.roomManager.currentRoom.get() != roomState) {
                                        try {
                                            udpHolePuncher3.close();
                                        }
                                        catch (Exception exception) {
                                            // empty catch block
                                        }
                                        this.activeHolePunchers.remove("joiner");
                                        this.connectionCycleActive.set(false);
                                        ConnectionHelper.resetConnecting();
                                        return null;
                                    }
                                    if ("punch stopped".equals(((Throwable)object).getMessage())) {
                                        VoxLinkMod.LOGGER.info("[Connection] Punch actively stopped, no retry");
                                        return null;
                                    }
                                    if (this.activeHolePunchers.get("joiner") != udpHolePuncher3) {
                                        VoxLinkMod.LOGGER.info("[Connection] Puncher replaced, no retry");
                                        return null;
                                    }
                                    if (!(((Throwable)object).getCause() instanceof FirewallBlockedException) && !(object instanceof FirewallBlockedException)) {
                                        if (n6 < this.punchProfile().punchMaxAttempts) {
                                            long l = (long)this.punchProfile().punchRetryDelayMs * (1L << Math.min(n6 - 1, 4));
                                            VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{l, n6 + 1, 3});
                                            this.scheduler.schedule(() -> {
                                                if (this.roomManager.currentRoom.get() == roomState && this.connectionCycleActive.get() && this.activeHolePunchers.get("joiner") == udpHolePuncher3) {
                                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, n6 + 1);
                                                }
                                            }, l, TimeUnit.MILLISECONDS);
                                        } else {
                                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                        }
                                        return null;
                                    }
                                    VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP, skip retry enter Wave 2 TCP fallback");
                                    try {
                                        udpHolePuncher3.close();
                                    }
                                    catch (Exception exception) {
                                        // empty catch block
                                    }
                                    this.activeHolePunchers.remove("joiner");
                                    this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                    return null;
                                });
                            } else {
                                VoxLinkMod.LOGGER.info("[Connection] EasyTier method: {} sockets punch to {}:{}", new Object[]{arrayList.size(), string8, n12});
                                UdpHolePuncher udpHolePuncher6 = (UdpHolePuncher)arrayList.get(0);
                                this.activeHolePunchers.put("joiner", udpHolePuncher6);
                                udpHolePuncher6.setSkipFirewallDetection(this.localNatClass.isSymmetric() || this.remoteNatClass.isSymmetric());
                                boolean bl13 = this.localNatClass.isSymmetric() && this.remoteNatClass.isSymmetric();
                                int n17 = bl13 ? this.punchProfile().joinerMultiPortRange : 0;
                                ((CompletableFuture)udpHolePuncher6.punchMultiSocket(string8, n12, arrayList, atomicBoolean, n17).thenAccept(punchResult -> {
                                    if (!punchResult.isSuccess()) {
                                        PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                                        this.lastPunchResult = punchResult.withReason(failureReason);
                                        VoxLinkMod.LOGGER.info("[Connection] cycle={} multi-socket punch failed reason={} recvPunch={} recvAck={}", new Object[]{n3, failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                                        this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, n3 + 1, n5, failureReason, this.lastPunchResult);
                                        for (UdpHolePuncher udpHolePuncher2 : arrayList) {
                                            try {
                                                udpHolePuncher2.stopPunch();
                                            }
                                            catch (Exception exception) {}
                                        }
                                        for (UdpHolePuncher udpHolePuncher2 : arrayList) {
                                            try {
                                                udpHolePuncher2.close();
                                            }
                                            catch (Exception exception) {}
                                        }
                                        for (int i = 0; i < arrayList.size(); ++i) {
                                            this.activeHolePunchers.remove("joiner_ms_" + i);
                                        }
                                        this.activeHolePunchers.remove("joiner");
                                        if (this.roomManager.currentRoom.get() != roomState) {
                                            this.connectionCycleActive.set(false);
                                            ConnectionHelper.resetConnecting();
                                        } else if (punchResult.firewallDetected) {
                                            VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP (multi-socket), skip retry enter Wave 2 TCP fallback");
                                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                        } else if (n6 < this.punchProfile().punchMaxAttempts) {
                                            long l = (long)this.punchProfile().punchRetryDelayMs * (1L << Math.min(n6 - 1, 4));
                                            VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{l, n6 + 1, 3});
                                            this.scheduler.schedule(() -> {
                                                if (this.roomManager.currentRoom.get() == roomState && this.connectionCycleActive.get()) {
                                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, n6 + 1);
                                                }
                                            }, l, TimeUnit.MILLISECONDS);
                                        } else {
                                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                        }
                                    } else {
                                        DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                                        if (this.roomManager.currentRoom.get() == roomState && this.connectionWon.compareAndSet(false, true)) {
                                            UdpHolePuncher udpHolePuncher32;
                                            String string6 = string8 != null && string8.contains(":") ? "[" + string8 + "]:" + n12 : string8 + ":" + n12;
                                            VoxLinkMod.LOGGER.info("[Connection] Multi-socket punch success {} (attempt {})", (Object)string6, (Object)n6);
                                            udpHolePuncher6.markSocketTransferred();
                                            this.stopAllPunchingAfterHostBridge();
                                            for (UdpHolePuncher udpHolePuncher32 : arrayList) {
                                                if (udpHolePuncher32.getSocket() == datagramSocket) continue;
                                                try {
                                                    udpHolePuncher32.stopPunch();
                                                    udpHolePuncher32.close();
                                                }
                                                catch (Exception exception) {}
                                            }
                                            DatagramSocket datagramSocket2 = datagramSocket;
                                            udpHolePuncher32 = udpHolePuncher6;
                                            InetSocketAddress inetSocketAddress = udpHolePuncher32.getActualRemoteAddress();
                                            if (inetSocketAddress == null) {
                                                inetSocketAddress = new InetSocketAddress(string8, n12);
                                            }
                                            InetSocketAddress inetSocketAddress2 = inetSocketAddress;
                                            this.scheduler.submit(() -> {
                                                try {
                                                    this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher32, inetSocketAddress2, "joiner", false, null);
                                                }
                                                catch (Exception exception) {
                                                    VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", (Object)exception.getMessage());
                                                    try {
                                                        udpHolePuncher32.close();
                                                    }
                                                    catch (Exception exception2) {
                                                        // empty catch block
                                                    }
                                                    this.showConnectFailed(roomState, "voxlink.connection.transport_failed");
                                                }
                                            });
                                        } else {
                                            for (UdpHolePuncher udpHolePuncher4 : arrayList) {
                                                try {
                                                    udpHolePuncher4.close();
                                                }
                                                catch (Exception exception) {}
                                            }
                                        }
                                    }
                                })).exceptionally(object -> {
                                    VoxLinkMod.LOGGER.warn("[Connection] Multi-socket punch failed (cycle {}/{}, attempt {}/{}): {}", new Object[]{n4, n5, n6, 3, ((Throwable)object).getMessage()});
                                    for (UdpHolePuncher udpHolePuncher : arrayList) {
                                        try {
                                            udpHolePuncher.stopPunch();
                                        }
                                        catch (Exception exception) {}
                                    }
                                    if (this.roomManager.currentRoom.get() != roomState) {
                                        for (UdpHolePuncher udpHolePuncher : arrayList) {
                                            try {
                                                udpHolePuncher.close();
                                            }
                                            catch (Exception exception) {}
                                        }
                                        for (int i = 0; i < arrayList.size(); ++i) {
                                            this.activeHolePunchers.remove("joiner_ms_" + i);
                                        }
                                        this.activeHolePunchers.remove("joiner");
                                        this.connectionCycleActive.set(false);
                                        ConnectionHelper.resetConnecting();
                                        return null;
                                    }
                                    if ("punch stopped".equals(((Throwable)object).getMessage())) {
                                        VoxLinkMod.LOGGER.info("[Connection] Punch actively stopped, no retry");
                                        return null;
                                    }
                                    if (!(((Throwable)object).getCause() instanceof FirewallBlockedException) && !(object instanceof FirewallBlockedException)) {
                                        if (n6 < this.punchProfile().punchMaxAttempts) {
                                            long l = (long)this.punchProfile().punchRetryDelayMs * (1L << Math.min(n6 - 1, 4));
                                            VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{l, n6 + 1, 3});
                                            this.scheduler.schedule(() -> {
                                                if (this.roomManager.currentRoom.get() == roomState && this.connectionCycleActive.get()) {
                                                    this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, n6 + 1);
                                                }
                                            }, l, TimeUnit.MILLISECONDS);
                                        } else {
                                            this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                        }
                                        return null;
                                    }
                                    VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP (multi-socket), skip retry enter Wave 2 TCP fallback");
                                    for (UdpHolePuncher udpHolePuncher : arrayList) {
                                        try {
                                            udpHolePuncher.close();
                                        }
                                        catch (Exception exception) {}
                                    }
                                    for (int i = 0; i < arrayList.size(); ++i) {
                                        this.activeHolePunchers.remove("joiner_ms_" + i);
                                    }
                                    this.activeHolePunchers.remove("joiner");
                                    this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                                    return null;
                                });
                            }
                        }
                    } else {
                        VoxLinkMod.LOGGER.warn("[Connection] No target IP, UDP punch cannot proceed");
                        udpHolePuncher2.close();
                        this.activeHolePunchers.remove("joiner");
                        this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                    }
                }
            } else {
                VoxLinkMod.LOGGER.warn("[Connection] STUN binding failed, no mapped address (attempt{})", (Object)n6);
                udpHolePuncher2.close();
                if (n6 < this.punchProfile().punchMaxAttempts) {
                    long l = (long)this.punchProfile().punchRetryDelayMs * (1L << Math.min(n6 - 1, 4));
                    VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{l, n6 + 1, 3});
                    this.scheduler.schedule(() -> {
                        if (this.roomManager.currentRoom.get() == roomState && this.connectionCycleActive.get()) {
                            this.tryUdpPunch(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, n6 + 1);
                        }
                    }, l, TimeUnit.MILLISECONDS);
                } else {
                    this.tryConnectionStep(roomState, string, string2, string3, n, string4, n2, n3, n4, n5, 1);
                }
            }
        }
    }

    public void advanceToNextCycle(RoomManager.RoomState roomState, String string, String string2, String string3, int n, String string4, int n2, int n3, int n4) {
        if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, skip next cycle");
        } else if (n3 + 1 >= n4) {
            if (!this.enterContinuousRetryRound(roomState)) {
                this.showConnectFailed(roomState, "voxlink.connection.max_cycles_exceeded");
            }
        } else {
            for (Map.Entry<String, UdpHolePuncher> entry : this.activeHolePunchers.entrySet()) {
                String string5 = entry.getKey();
                if (!string5.startsWith("joiner")) continue;
                UdpHolePuncher udpHolePuncher = entry.getValue();
                try {
                    udpHolePuncher.stopPunch();
                }
                catch (Exception exception) {
                    // empty catch block
                }
                if (!string5.startsWith("joiner_ms_")) continue;
                try {
                    udpHolePuncher.close();
                }
                catch (Exception exception) {}
            }
            if (n3 == 0 && this.punchProfile() == PunchProfile.DEFAULT) {
                boolean bl = roomState.roomInfo.isHostSymmetric();
                boolean bl2 = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
                boolean bl3 = bl2;
                if (bl || bl2) {
                    this.switchPunchProfile(PunchProfile.AGGRESSIVE, "\u9996\u8f6e\u5931\u8d25+" + (bl ? "HostSym" : "LocalSym"));
                    VoxLinkMod.LOGGER.info("[Connection] Detected extreme symmetric NAT, switch to aggressive level next round: {}", (Object)this.punchProfile().describeInstance());
                }
            }
            if (this.lastPunchResult != null && this.lastPunchResult.reason != null) {
                if (this.lastPunchResult.reason == this.lastFailureReason) {
                    int n5 = this.consecutiveFailureCount.incrementAndGet();
                    if (n5 >= 3) {
                        VoxLinkMod.LOGGER.warn("[Connection] Consecutive {} failures with same reason ({}), switch strategy", (Object)n5, (Object)this.lastFailureReason);
                        switch (this.lastFailureReason) {
                            case NO_RESPONSE: {
                                this.switchPunchProfile(PunchProfile.HARDSYM, "consecutive_no_response");
                            }
                            case FIREWALL_DETECTED: {
                                break;
                            }
                            default: {
                                this.switchPunchProfile(PunchProfile.AGGRESSIVE, "consecutive_" + (Object)((Object)this.lastFailureReason));
                            }
                        }
                        this.consecutiveFailureCount.set(0);
                    }
                } else {
                    this.lastFailureReason = this.lastPunchResult.reason;
                    this.consecutiveFailureCount.set(1);
                }
            }
            int n6 = Math.min(n3, BACKOFF_DELAYS_MS.length - 1);
            long l = BACKOFF_DELAYS_MS[n6];
            VoxLinkMod.LOGGER.info("[Connection] Cycle {}/{} failed, retry in {}s (backoff level={})", new Object[]{n3 + 1, n4, l / 1000L, this.punchProfile().describeInstance()});
            this.scheduler.schedule(() -> {
                if (this.roomManager.currentRoom.get() == roomState && roomState != RoomManager.PENDING) {
                    this.runConnectionCycle(roomState, string, string2, string3, n, string4, n2, n3 + 1);
                }
            }, l, TimeUnit.MILLISECONDS);
        }
    }

    public List<String> selectReachableStunGroup(int n) {
        if (this.stunProbeResult != null && !this.stunProbeResult.reachableStunUrls.isEmpty()) {
            List<String> list = this.stunProbeResult.reachableStunUrls;
            int n2 = n % list.size();
            return Collections.singletonList(list.get(n2));
        }
        return StunDetector.getStunGroup(n % StunDetector.getStunGroupCount());
    }

    public void startReversePunch(RoomManager.RoomState roomState) {
        VoxLinkMod.LOGGER.info("[ReversePunch] Parallel start reverse punch");
        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
        this.connectionCycleActive.set(true);
        boolean bl = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
        boolean bl2 = this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric();
        boolean bl3 = roomState.roomInfo != null && (roomState.roomInfo.isHostSymmetric() || StunDetector.isNatTypeSymmetric(roomState.roomInfo.getNatType()));
        boolean bl4 = bl3;
        if (!bl) {
            this.startSimpleReversePunch(roomState);
        } else {
            this.switchPunchProfile(PunchProfile.V100, "sym_birthday");
            int n = bl2 ? this.punchProfile().birthdaySocketCount : this.punchProfile().hardSymSocketCount;
            VoxLinkMod.LOGGER.info("[ReversePunch] Detected local symmetric (local={}, host={}) \u2014 birthday attack {} sockets (easySym={})", new Object[]{bl, bl3, n, bl2});
            this.startBirthdayPunch(roomState, n, bl2);
        }
    }

    public void startBirthdayPunch(RoomManager.RoomState roomState) {
        this.startBirthdayPunch(roomState, this.punchProfile().birthdaySocketCount, false);
    }

    public void startBirthdayPunch(RoomManager.RoomState roomState, int n, boolean bl) {
        String string = roomState.roomInfo.getHostMappedIp();
        int n2 = roomState.roomInfo.getHostMappedPort();
        if (string == null || n2 <= 0) {
            string = roomState.roomInfo.getHostIp();
            n2 = roomState.roomInfo.getHostPort() > 0 ? roomState.roomInfo.getHostPort() : 51600;
        }
        String string2 = string;
        int n3 = n2;
        VoxLinkMod.LOGGER.info("[BirthdayPunch] Parallel STUN {} sockets (target={}:{}, easySym={})", new Object[]{n, string2, n3, bl});
        CompletableFuture.supplyAsync(() -> this.getOrCreateUdpArray(n, bl, StunDetector.getAllStunUrls())).thenAccept(udpSocketArray -> {
            if (this.roomManager.currentRoom.get() == roomState) {
                if (udpSocketArray != null && !udpSocketArray.punchers.isEmpty()) {
                    Object object2;
                    List<UdpHolePuncher> list = udpSocketArray.punchers;
                    List<StunProbe.PublicMappedAddress> list2 = udpSocketArray.mappedAddrs;
                    ArrayList<String> arrayList = new ArrayList<String>();
                    ArrayList<String> arrayList2 = new ArrayList<String>();
                    for (int i = 0; i < list.size(); ++i) {
                        object2 = "joiner_birthday_" + i;
                        arrayList2.add((String)object2);
                        this.activeHolePunchers.put((String)object2, list.get(i));
                        StunProbe.PublicMappedAddress object3 = i < list2.size() ? list2.get(i) : null;
                        arrayList.add(object3 != null ? String.valueOf(object3.port()) : "0");
                    }
                    StunProbe.PublicMappedAddress publicMappedAddress = null;
                    for (StunProbe.PublicMappedAddress publicMappedAddress2 : list2) {
                        if (publicMappedAddress2 == null || publicMappedAddress2.port() <= 0) continue;
                        publicMappedAddress = publicMappedAddress2;
                        break;
                    }
                    if (publicMappedAddress == null) {
                        VoxLinkMod.LOGGER.error("[BirthdayPunch] No valid mappedAddr");
                        this.showConnectFailed(roomState, "voxlink.connection.no_mapped_addr");
                    } else {
                        VoxLinkMod.LOGGER.info("[BirthdayPunch] Prepare {} sockets, mapped ports: {}", (Object)list.size(), arrayList);
                        object2 = new JsonObject();
                        object2.addProperty("joinerMappedIp", publicMappedAddress.ip());
                        object2.addProperty("joinerMappedPort", (Number)publicMappedAddress.port());
                        object2.addProperty("joinerSymmetric", Boolean.valueOf(true));
                        if (bl) {
                            object2.addProperty("joinerEasySym", Boolean.valueOf(true));
                        }
                        object2.add("joinerMappedPorts", (JsonElement)new JsonArray());
                        for (StunProbe.PublicMappedAddress publicMappedAddress3 : list2) {
                            if (publicMappedAddress3 == null || publicMappedAddress3.port() <= 0) continue;
                            object2.getAsJsonArray("joinerMappedPorts").add((Number)publicMappedAddress3.port());
                        }
                        ((CompletableFuture)this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "reverse_holepunch_offer", (JsonObject)object2, "host").thenAccept(apiResponse -> {
                            if (!apiResponse.success) {
                                VoxLinkMod.LOGGER.error("[BirthdayPunch] Send reverse_holepunch_offer failed: {}", (Object)apiResponse.error);
                            }
                        })).exceptionally(object -> {
                            VoxLinkMod.LOGGER.error("[BirthdayPunch] Send reverse_holepunch_offer failed: {}", (Object)((Throwable)object).getMessage());
                            return null;
                        });
                        this.startBirthdayPunchPhase2(roomState, list, arrayList2, string2, n3, roomState.roomInfo.isHostSymmetric(), bl);
                        VoxLinkMod.LOGGER.info("[BirthdayPunch] Start punch to {}:{} immediately (no wait for reverse_punch_info)", (Object)string2, (Object)n3);
                        this.scheduler.schedule(() -> {
                            if (this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == roomState) {
                                VoxLinkMod.LOGGER.warn("[BirthdayPunch] Timeout (no connection established)");
                                for (UdpHolePuncher udpHolePuncher : list) {
                                    try {
                                        udpHolePuncher.cancel();
                                        udpHolePuncher.close();
                                    }
                                    catch (Exception exception) {}
                                }
                                this.activeHolePunchers.entrySet().removeIf(entry -> ((String)entry.getKey()).startsWith("joiner_birthday_"));
                                this.showConnectFailed(roomState, "voxlink.connection.punch_timeout");
                            }
                        }, (long)this.punchProfile().reverseWindowSec, TimeUnit.SECONDS);
                    }
                } else {
                    VoxLinkMod.LOGGER.error("[BirthdayPunch] All STUN queries failed");
                    this.showConnectFailed(roomState, "voxlink.connection.stun_failed");
                }
            }
        });
    }

    public void startSimpleReversePunch(RoomManager.RoomState roomState) {
        StunProbe.PublicMappedAddress publicMappedAddress;
        StunProbe.PublicMappedAddress publicMappedAddress2;
        UdpHolePuncher udpHolePuncher = new UdpHolePuncher();
        this.applyPunchTemplate(udpHolePuncher);
        try {
            udpHolePuncher.createSocket();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.error("[ReversePunch] Create punch socket failed: {}", (Object)exception.getMessage());
            return;
        }
        this.activeHolePunchers.put("joiner_reverse", udpHolePuncher);
        List<String> list = StunDetector.getAllStunUrls();
        StunProbe.PublicMappedAddress[] publicMappedAddressArray = StunProbe.discoverMappedAddressQuad(udpHolePuncher.getSocket(), list.get(0), list.get(1), list.get(2), list.get(3));
        StunProbe.PublicMappedAddress publicMappedAddress3 = null;
        StunProbe.PublicMappedAddress publicMappedAddress4 = publicMappedAddressArray[0] != null ? publicMappedAddressArray[0] : (publicMappedAddress2 = (publicMappedAddress3 = publicMappedAddressArray[2] != null ? publicMappedAddressArray[2] : publicMappedAddressArray[3]));
        StunProbe.PublicMappedAddress publicMappedAddress5 = publicMappedAddressArray[1] != null ? publicMappedAddressArray[1] : (publicMappedAddressArray[3] != null ? publicMappedAddressArray[3] : publicMappedAddressArray[2]);
        boolean bl = false;
        int n = 0;
        StunProbe.PublicMappedAddress publicMappedAddress6 = null;
        if (publicMappedAddress3 != null && publicMappedAddress5 != null) {
            if (ConnectionManager.sameIpFamily(publicMappedAddress3.ip(), publicMappedAddress5.ip()) && publicMappedAddress3.port() != publicMappedAddress5.port()) {
                bl = true;
                n = publicMappedAddress5.port() - publicMappedAddress3.port();
                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner punch socket STUN: detected symmetric ({} vs {}, delta={})", new Object[]{publicMappedAddress3.port(), publicMappedAddress5.port(), n});
            }
            publicMappedAddress6 = publicMappedAddress5;
        } else {
            publicMappedAddress6 = publicMappedAddress3 != null ? publicMappedAddress3 : publicMappedAddress5;
            publicMappedAddress = publicMappedAddress6;
        }
        if (publicMappedAddress6 == null) {
            publicMappedAddress6 = udpHolePuncher.discoverMappedAddress(StunDetector.getAllStunUrls());
        }
        if (publicMappedAddress6 == null) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] STUN failed, cannot reverse punch");
            udpHolePuncher.close();
            this.activeHolePunchers.remove("joiner_reverse");
        } else {
            VoxLinkMod.LOGGER.info("[ReversePunch] Joiner mapped address: {}:{} (symmetric={})", new Object[]{publicMappedAddress6.ip(), publicMappedAddress6.port(), bl});
            if (bl) {
                VoxLinkMod.LOGGER.info("[ReversePunch] Reverse socket STUN detected symmetric, upgrade to birthday attack");
                try {
                    udpHolePuncher.close();
                }
                catch (Exception exception) {
                    // empty catch block
                }
                this.activeHolePunchers.remove("joiner_reverse");
                this.startBirthdayPunch(roomState, this.punchProfile().hardSymSocketCount, false);
            } else {
                publicMappedAddress = new JsonObject();
                publicMappedAddress.addProperty("joinerMappedIp", publicMappedAddress6.ip());
                publicMappedAddress.addProperty("joinerMappedPort", publicMappedAddress6.port());
                if (bl || this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric()) {
                    publicMappedAddress.addProperty("joinerSymmetric", true);
                }
                if (this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() || bl) {
                    publicMappedAddress.addProperty("joinerEasySym", true);
                }
                if (n != 0) {
                    publicMappedAddress.addProperty("joinerMappedPortDelta", n);
                }
                ((CompletableFuture)this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "reverse_holepunch_offer", (JsonObject)publicMappedAddress, "host").thenAccept(apiResponse -> {
                    if (!apiResponse.success) {
                        VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", (Object)apiResponse.error);
                    }
                })).exceptionally(object -> {
                    VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", (Object)((Throwable)object).getMessage());
                    return null;
                });
                String string = roomState.roomInfo.getHostMappedIp();
                int n2 = roomState.roomInfo.getHostMappedPort();
                if (string == null || n2 <= 0) {
                    string = roomState.roomInfo.getHostIp();
                    n2 = roomState.roomInfo.getHostPort() > 0 ? roomState.roomInfo.getHostPort() : 51600;
                    int n3 = n2;
                }
                if (string != null && !string.isEmpty()) {
                    String string2 = string;
                    int n4 = n2;
                    UdpHolePuncher udpHolePuncher2 = udpHolePuncher;
                    int n5 = roomState.roomInfo.isHostSymmetric() || bl ? this.punchProfile().portPredictionMaxRange : this.punchProfile().portPredictionMaxRange;
                    PunchParams punchParams = PunchParams.fromProfile(this.punchProfile());
                    punchParams.timeoutMs = Math.max(punchParams.timeoutMs, 15000);
                    udpHolePuncher2.setPunchParams(punchParams);
                    VoxLinkMod.LOGGER.info("[ReversePunch] Punch to {}:{} immediately (range=\u00b1{})", new Object[]{string2, n4, n5});
                    udpHolePuncher.setOnPeerPunchReceived(inetSocketAddress -> {
                        String string = roomState.roomInfo.getCode();
                        String string2 = roomState.roomInfo.getToken();
                        JsonObject jsonObject = new JsonObject();
                        jsonObject.addProperty("peer_ip", inetSocketAddress.getAddress().getHostAddress());
                        jsonObject.addProperty("peer_port", (Number)inetSocketAddress.getPort());
                        this.signalingClient.sendSignal(string, string2, false, "peer_port", jsonObject, "host").exceptionally(throwable -> {
                            VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", (Object)throwable.getMessage());
                            return null;
                        });
                    });
                    ((CompletableFuture)udpHolePuncher.punchWithPortPrediction(string2, n4, n5, true).thenAccept(punchResult -> {
                        if (!punchResult.isSuccess()) {
                            PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                            this.lastPunchResult = punchResult.withReason(failureReason);
                            this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
                            VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                        } else {
                            DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                            if (this.roomManager.currentRoom.get() == roomState && this.connectionWon.compareAndSet(false, true)) {
                                VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch success {}:{}", (Object)string2, (Object)n4);
                                udpHolePuncher2.markSocketTransferred();
                                this.stopAllPunchingAfterHostBridge();
                                udpHolePuncher2.stopPunch();
                                DatagramSocket datagramSocket2 = datagramSocket;
                                UdpHolePuncher udpHolePuncher2 = udpHolePuncher2;
                                InetSocketAddress inetSocketAddress = udpHolePuncher2.getActualRemoteAddress();
                                if (inetSocketAddress == null) {
                                    inetSocketAddress = new InetSocketAddress(string2, n4);
                                }
                                InetSocketAddress inetSocketAddress2 = inetSocketAddress;
                                VoxLinkMod.LOGGER.info("[ReversePunch] Actual target address: {} (STUN mapping: {}:{})", new Object[]{inetSocketAddress2, string2, n4});
                                this.scheduler.submit(() -> {
                                    try {
                                        this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher2, inetSocketAddress2, "joiner", false, null);
                                    }
                                    catch (Exception exception) {
                                        VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport create failed: {}", (Object)exception.getMessage());
                                        try {
                                            udpHolePuncher2.close();
                                        }
                                        catch (Exception exception2) {
                                            // empty catch block
                                        }
                                        this.showConnectFailed(roomState, "voxlink.connection.transport_failed");
                                    }
                                });
                            } else {
                                try {
                                    udpHolePuncher2.close();
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                            }
                        }
                    })).exceptionally(object -> {
                        VoxLinkMod.LOGGER.debug("[ReversePunch] Joiner reverse punch failed (wait for reverse_punch_info to update target then retry): {}", (Object)((Throwable)object).getMessage());
                        return null;
                    });
                    this.scheduler.schedule(() -> {
                        if (this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == roomState && !this.connectionWon.get()) {
                            VoxLinkMod.LOGGER.warn("[ReversePunch] Reverse punch timeout");
                            UdpHolePuncher udpHolePuncher = this.activeHolePunchers.remove("joiner_reverse");
                            if (udpHolePuncher != null) {
                                udpHolePuncher.cancel();
                                udpHolePuncher.close();
                            }
                        }
                    }, 20L, TimeUnit.SECONDS);
                } else {
                    VoxLinkMod.LOGGER.warn("[ReversePunch] No host address, cannot punch immediately");
                }
            }
        }
    }

    public void startBirthdayPunchPhase2(RoomManager.RoomState roomState, List<UdpHolePuncher> list, List<String> list2, String string, int n, boolean bl, boolean bl2) {
        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        for (int i = 0; i < list.size(); ++i) {
            UdpHolePuncher udpHolePuncher = list.get(i);
            if (udpHolePuncher.getSocket() == null || udpHolePuncher.getSocket().isClosed()) continue;
            int n2 = i;
            udpHolePuncher.setOnPeerPunchReceived(inetSocketAddress -> {
                if (!atomicBoolean.get()) {
                    VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} received peer punch to {}:{}", new Object[]{n2, inetSocketAddress.getAddress().getHostAddress(), inetSocketAddress.getPort()});
                }
            });
            int n3 = this.punchProfile().defaultPortRange;
            n3 = bl2 ? this.punchProfile().easySymPortRange : (bl ? this.punchProfile().defaultPortRange : this.punchProfile().minPortRange);
            ((CompletableFuture)udpHolePuncher.punchWithPortPrediction(string, n, n3, true).thenAccept(punchResult -> {
                if (!punchResult.isSuccess()) {
                    PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                    this.lastPunchResult = punchResult.withReason(failureReason);
                    this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
                    VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                } else {
                    DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                    if (!this.connectionWon.compareAndSet(false, true)) {
                        try {
                            udpHolePuncher.close();
                        }
                        catch (Exception exception) {}
                    } else if (!atomicBoolean.compareAndSet(false, true)) {
                        try {
                            udpHolePuncher.close();
                        }
                        catch (Exception exception) {}
                    } else {
                        UdpHolePuncher udpHolePuncher22;
                        VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} won! Connected to {}:{}", new Object[]{n2, string, n});
                        udpHolePuncher.markSocketTransferred();
                        this.stopAllPunchingAfterHostBridge();
                        for (UdpHolePuncher udpHolePuncher22 : list) {
                            if (udpHolePuncher22 == udpHolePuncher) continue;
                            try {
                                udpHolePuncher22.stopPunch();
                                udpHolePuncher22.close();
                            }
                            catch (Exception exception) {}
                        }
                        udpHolePuncher.stopPunch();
                        DatagramSocket datagramSocket2 = datagramSocket;
                        udpHolePuncher22 = udpHolePuncher;
                        this.scheduler.submit(() -> {
                            try {
                                this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher22, new InetSocketAddress(string, n), "joiner", false, null);
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.error("[BirthdayPunch] Transport create failed: {}", (Object)exception.getMessage());
                                try {
                                    udpHolePuncher22.close();
                                }
                                catch (Exception exception2) {
                                    // empty catch block
                                }
                                this.showConnectFailed(roomState, "voxlink.connection.transport_failed");
                            }
                        });
                    }
                }
            })).exceptionally(object -> {
                VoxLinkMod.LOGGER.debug("[BirthdayPunch] Socket #{} failed: {}", (Object)n2, (Object)((Throwable)object).getMessage());
                return null;
            });
        }
        this.scheduler.schedule(() -> {
            if (!atomicBoolean.get() && this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == roomState) {
                VoxLinkMod.LOGGER.warn("[BirthdayPunch] All {} sockets timeout", (Object)list.size());
                for (int i = 0; i < list.size(); ++i) {
                    try {
                        ((UdpHolePuncher)list.get(i)).cancel();
                        ((UdpHolePuncher)list.get(i)).close();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                    this.activeHolePunchers.remove(list2.get(i));
                }
                this.showConnectFailed(roomState, "voxlink.connection.punch_timeout");
            }
        }, (long)this.punchProfile().reverseWindowSec, TimeUnit.SECONDS);
    }

    public void connectViaBridge(RoomManager.RoomState roomState, ConnectionFallback.ConnectResult connectResult) {
        if (this.dualRaceActive) {
            this.killAllConnectionAttempts("terracotta");
        }
        this.killAllConnectionAttempts();
        P2PBridge.cancelPendingUdpTimeouts();
        for (ReliableUdpTransport reliableUdpTransport : this.activeUdpTransports.values()) {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {}
        }
        this.activeUdpTransports.clear();
        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
        String string = connectResult.remoteHost;
        int n2 = connectResult.remotePort;
        if (connectResult.mode == ConnectionFallback.ConnectionMode.IPV6_DIRECT) {
            P2PBridge.connectToHostIpv6(string, n2).thenAccept(n -> {
                if (n > 0) {
                    if (this.dualRaceActive) {
                        this.claimVoxlinkDualWin();
                    }
                    this.connectionWon.set(true);
                    this.markConnectionEstablished();
                    this.connectionCycleActive.set(false);
                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "connected", new JsonObject(), "host");
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.bridge_setup", (Object[])new Object[0]));
                    ConnectionHelper.connectToServer(n, roomState.roomInfo);
                    this.notifyDualVoxlinkBridge(true);
                } else {
                    this.connectionCycleActive.set(false);
                    ConnectionHelper.resetConnecting();
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.bridge_start_failed", (Object[])new Object[0]), true);
                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
                    if (!this.dualRaceActive) {
                        this.handleConnectViaBridgeFailed(roomState);
                    }
                    this.notifyDualVoxlinkBridge(false);
                }
            });
        } else {
            P2PBridge.connectToHost(string, n2).thenAccept(n -> {
                if (n > 0) {
                    if (this.dualRaceActive) {
                        this.claimVoxlinkDualWin();
                    }
                    this.connectionWon.set(true);
                    this.markConnectionEstablished();
                    this.connectionCycleActive.set(false);
                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "connected", new JsonObject(), "host");
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.bridge_setup", (Object[])new Object[0]));
                    ConnectionHelper.connectToServer(n, roomState.roomInfo);
                    this.notifyDualVoxlinkBridge(true);
                } else {
                    this.connectionCycleActive.set(false);
                    ConnectionHelper.resetConnecting();
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.bridge_start_failed", (Object[])new Object[0]), true);
                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
                    if (!this.dualRaceActive) {
                        this.handleConnectViaBridgeFailed(roomState);
                    }
                    this.notifyDualVoxlinkBridge(false);
                }
            });
        }
    }

    public void handleConnectViaBridgeFailed(RoomManager.RoomState roomState) {
        this.leaveRoomOnFailure(roomState);
    }

    public void startFallbackMonitor(ConnectionFallback connectionFallback, RoomManager.RoomState roomState) {
        Thread thread = new Thread(() -> {
            String string;
            int n = 0;
            while (!connectionFallback.isSettled() && !connectionFallback.isCancelled()) {
                if (n >= 200) {
                    VoxLinkMod.LOGGER.warn("Fallback monitor timeout (about 60s)");
                    break;
                }
                String string2 = connectionFallback.getStatusText();
                if (string2 != null) {
                    roomState.roomInfo.setConnectionMode(string2);
                }
                ++n;
                try {
                    Thread.sleep(300L);
                }
                catch (InterruptedException interruptedException) {
                    return;
                }
            }
            if ((string = connectionFallback.getStatusText()) != null) {
                roomState.roomInfo.setConnectionMode(string);
            }
        }, "VoxLink-FallbackMonitor");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean enterContinuousRetryRound(RoomManager.RoomState roomState) {
        if (this.connectionWon.get()) {
            return false;
        }
        if (!this.shouldContinuousRetry(roomState)) {
            return false;
        }
        if (this.savedConnectionState != roomState) {
            VoxLinkMod.LOGGER.warn("[Connection] Persistent retry aborted: saved params mismatch current room");
            return false;
        }
        int n = this.continuousRetryRound.incrementAndGet();
        this.escalateProfileForRound(n);
        VoxLinkMod.LOGGER.info("[Connection] Enter persistent retry round={}, level={}, reset cycle from 0", (Object)n, (Object)this.punchProfile().describeInstance());
        ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "\u6301\u7eed\u91cd\u8bd5 round " + n);
        if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
            this.connectionTimeoutFuture = null;
        }
        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.retry_round", (Object[])new Object[]{n}));
        int n2 = this.getEffectiveMaxCycles();
        this.tryConnectionStep(roomState, this.savedConnectionFrom, this.savedConnectionHostIpv6, this.savedConnectionHostIp, this.savedConnectionHostPort, this.savedConnectionHostMappedIp, this.savedConnectionHostMappedPort, 0, 1, n2, 0);
        return true;
    }

    public void showConnectFailed(RoomManager.RoomState roomState) {
        this.showConnectFailed(roomState, "voxlink.connection.all_failed");
    }

    public void showConnectFailed(RoomManager.RoomState roomState, String string) {
        if (this.roomManager.currentRoom.get() == roomState && roomState != RoomManager.PENDING && !this.connectionWon.get()) {
            if (string != null && string.contains("relay")) {
                Minecraft minecraft;
                if (roomState != null && roomState.roomInfo != null) {
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)string, (Object[])new Object[0]), true);
                }
                if ((minecraft = Minecraft.func_71410_x()) != null) {
                    minecraft.func_152344_a(() -> {
                        if (minecraft.field_71439_g != null) {
                            VoxLinkChat.print(new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.error_prefix", (Object[])new Object[0])).func_150257_a((ITextComponent)new TextComponentString(I18n.func_135052_a((String)string, (Object[])new Object[0]))));
                        }
                    });
                }
            }
            if (!this.enterContinuousRetryRound(roomState)) {
                this.showConnectFailedFinal(roomState, string);
            }
        }
    }

    private boolean hasRelayCandidateAvailable(RoomManager.RoomState roomState) {
        if (!VoxLinkMod.getConfig().isRelayEnabled()) {
            return false;
        }
        if (roomState != null && roomState.roomInfo != null) {
            for (RoomInfo.PeerInfo peerInfo : roomState.roomInfo.getPeers()) {
                String string = peerInfo.natType;
                if (string == null || peerInfo.mappedIp == null || peerInfo.mappedPort <= 0 || this.isRelayPeerFailed(peerInfo.clientId) || string.contains("sym") || string.contains("strict") || string.equals("unknown") || !ProtocolNegotiator.supportsRelay(peerInfo)) continue;
                return true;
            }
            return false;
        }
        return false;
    }

    private void tryRelay(RoomManager.RoomState roomState) {
        if (!VoxLinkMod.getConfig().isRelayEnabled()) {
            VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, skip tryRelay");
            this.notifyRelayFailed();
            this.showConnectFailed(roomState, "voxlink.connection.relay_unavailable");
        } else if (this.currentRelayPeer.get() != null) {
            VoxLinkMod.LOGGER.info("[Relay] Relay already trying (current={}), skip duplicate", (Object)this.currentRelayPeer.get());
        } else if (!roomState.roomInfo.isHost()) {
            if (this.currentRelayPeer.get() != null) {
                return;
            }
            this.currentRelayPeer.set("joiner_requesting");
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("clientId", roomState.roomInfo.getClientId());
            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_request", jsonObject, "host");
            this.relayFailoverTask = this.scheduler.schedule(() -> {
                if (!this.connectionWon.get()) {
                    this.currentRelayPeer.set(null);
                    this.relayFailoverTask = null;
                    this.notifyRelayFailed();
                    this.showConnectFailed(roomState, "voxlink.connection.relay_failed");
                }
            }, 20L, TimeUnit.SECONDS);
        } else {
            String string;
            Object object;
            ArrayList<RoomInfo.PeerInfo> arrayList = new ArrayList<RoomInfo.PeerInfo>();
            for (RoomInfo.PeerInfo peerInfo3 : roomState.roomInfo.getPeers()) {
                object = peerInfo3.natType;
                if (object == null || peerInfo3.mappedIp == null || peerInfo3.mappedPort <= 0 || this.isRelayPeerFailed(peerInfo3.clientId) || this.activeUdpTransports.get(peerInfo3.clientId) == null || ((String)object).contains("sym") || ((String)object).contains("strict") || ((String)object).equals("unknown")) continue;
                if (!ProtocolNegotiator.supportsRelay(peerInfo3)) {
                    VoxLinkMod.LOGGER.info("[Relay] Candidate {} is legacy, skip", (Object)peerInfo3.clientId);
                    continue;
                }
                arrayList.add(peerInfo3);
            }
            if (arrayList.isEmpty()) {
                VoxLinkMod.LOGGER.info("[Relay] No available Cone relay node (excluded failed={})", (Object)this.failedRelayPeers.size());
                this.failedRelayPeers.clear();
                this.notifyRelayFailed();
                this.showConnectFailed(roomState, "voxlink.connection.relay_unavailable");
                return;
            }
            RelayBridge relayBridge = RelayBridge.getInstance(this.scheduler);
            arrayList.sort((peerInfo, peerInfo2) -> {
                int n;
                int n2 = relayBridge.getRelayCountForPeer(peerInfo.clientId);
                if (n2 != (n = relayBridge.getRelayCountForPeer(peerInfo2.clientId))) {
                    return Integer.compare(n2, n);
                }
                ReliableUdpTransport reliableUdpTransport = this.activeUdpTransports.get(peerInfo.clientId);
                ReliableUdpTransport reliableUdpTransport2 = this.activeUdpTransports.get(peerInfo2.clientId);
                return Long.compare(reliableUdpTransport != null ? reliableUdpTransport.getRtoMs() : Long.MAX_VALUE, reliableUdpTransport2 != null ? reliableUdpTransport2.getRtoMs() : Long.MAX_VALUE);
            });
            int n = Math.min(3, arrayList.size());
            object = arrayList.subList(0, n);
            RoomInfo.PeerInfo peerInfo4 = null;
            for (RoomInfo.PeerInfo peerInfo5 : roomState.roomInfo.getPeers()) {
                string = peerInfo5.natType;
                if (string == null || !string.contains("sym") && !string.contains("strict") && !string.equals("unknown")) continue;
                if (!ProtocolNegotiator.supportsRelay(peerInfo5)) {
                    VoxLinkMod.LOGGER.info("[Relay] Target Sym player {} is legacy, skip relay", (Object)peerInfo5.clientId);
                    continue;
                }
                peerInfo4 = peerInfo5;
                break;
            }
            if (peerInfo4 == null || peerInfo4.mappedIp == null) {
                VoxLinkMod.LOGGER.warn("[Relay] No symmetric NAT player needs relay");
                this.notifyRelayFailed();
                this.showConnectFailed(roomState, "voxlink.connection.relay_unavailable");
                return;
            }
            this.currentRelayPeer.set(((RoomInfo.PeerInfo)object.get((int)0)).clientId);
            VoxLinkMod.LOGGER.info("[Relay] Parallel try {} Cone relays, target Sym={}", (Object)n, (Object)peerInfo4.clientId);
            for (int i = 0; i < n; ++i) {
                RoomInfo.PeerInfo peerInfo5;
                peerInfo5 = (RoomInfo.PeerInfo)object.get(i);
                string = new JsonObject();
                string.addProperty("targetClientId", peerInfo4.clientId);
                string.addProperty("targetIp", peerInfo4.mappedIp);
                string.addProperty("targetPort", (Number)peerInfo4.mappedPort);
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "relay_setup", (JsonObject)string, peerInfo5.clientId);
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("relayIp", peerInfo5.mappedIp);
                jsonObject.addProperty("relayPort", (Number)peerInfo5.mappedPort);
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "relay_notify", jsonObject, peerInfo4.clientId);
                VoxLinkMod.LOGGER.info("[Relay] Concurrent #{} relay={} (load={}) {}:{}", new Object[]{i + 1, peerInfo5.clientId, relayBridge.getRelayCountForPeer(peerInfo5.clientId), peerInfo5.mappedIp, peerInfo5.mappedPort});
            }
            this.relayFailoverTask = this.scheduler.schedule(() -> this.lambda$tryRelay$107(roomState, n, (List)object), 8L, TimeUnit.SECONDS);
        }
    }

    private void clearRelayTracking() {
        this.currentRelayPeer.set(null);
        if (this.relayFailoverTask != null) {
            this.relayFailoverTask.cancel(false);
            this.relayFailoverTask = null;
        }
    }

    public void handleRelayRequest(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            String string2 = jsonObject.has("clientId") ? jsonObject.get("clientId").getAsString() : string;
            VoxLinkMod.LOGGER.info("[Relay] Received relay_request, requester={}", (Object)string2);
            RoomInfo.PeerInfo peerInfo = roomState.roomInfo.getPeer(string2);
            if (peerInfo != null && !ProtocolNegotiator.supportsRelay(peerInfo)) {
                VoxLinkMod.LOGGER.info("[Relay] Requester {} is legacy, no relay_request response", (Object)string2);
            } else {
                RoomInfo.PeerInfo peerInfo2;
                Minecraft minecraft = Minecraft.func_71410_x();
                if (minecraft.field_71439_g != null) {
                    VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.relay.host_notice", (Object[])new Object[0])));
                }
                if ((peerInfo2 = roomState.roomInfo.getPeer(string2)) != null && peerInfo2.mappedIp != null && peerInfo2.mappedPort > 0) {
                    ArrayList<RoomInfo.PeerInfo> arrayList = new ArrayList<RoomInfo.PeerInfo>();
                    for (RoomInfo.PeerInfo peerInfo3 : roomState.roomInfo.getPeers()) {
                        String string3;
                        if (peerInfo3.clientId.equals(string2) || peerInfo3.mappedIp == null || peerInfo3.mappedPort <= 0 || this.activeUdpTransports.get(peerInfo3.clientId) == null || this.isRelayPeerFailed(peerInfo3.clientId) || (string3 = peerInfo3.natType) == null || string3.contains("sym") || string3.contains("strict") || string3.equals("unknown") || !ProtocolNegotiator.supportsRelay(peerInfo3)) continue;
                        arrayList.add(peerInfo3);
                    }
                    if (arrayList.isEmpty()) {
                        this.fetchGlobalRelayCandidates(roomState, string2, peerInfo2);
                    } else {
                        this.dispatchRelaySetup(roomState, arrayList, string2, peerInfo2);
                    }
                } else {
                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_declined", new JsonObject(), string2);
                }
            }
        }
    }

    private void fetchGlobalRelayCandidates(RoomManager.RoomState roomState, String string, RoomInfo.PeerInfo peerInfo) {
        ((CompletableFuture)this.signalingClient.getRelayCandidates().thenAccept(apiResponse -> {
            if (this.roomManager.currentRoom.get() != roomState || this.connectionWon.get()) {
                return;
            }
            if (apiResponse == null || !apiResponse.success || apiResponse.data == null || !apiResponse.data.has("candidates")) {
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_declined", new JsonObject(), string);
                return;
            }
            JsonArray jsonArray = apiResponse.data.getAsJsonArray("candidates");
            ArrayList<RoomInfo.PeerInfo> arrayList = new ArrayList<RoomInfo.PeerInfo>();
            for (int i = 0; i < jsonArray.size(); ++i) {
                JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
                String string2 = jsonObject.has("clientId") ? jsonObject.get("clientId").getAsString() : null;
                String string3 = jsonObject.has("mappedIp") ? jsonObject.get("mappedIp").getAsString() : null;
                int n = jsonObject.has("mappedPort") ? jsonObject.get("mappedPort").getAsInt() : 0;
                String string4 = jsonObject.has("natType") ? jsonObject.get("natType").getAsString() : "unknown";
                String string5 = string4;
                if (string2 == null || string2.equals(string) || string2.equals(roomState.roomInfo.getClientId()) || string3 == null || n <= 0 || string4.contains("sym") || string4.contains("strict") || string4.equals("unknown") || this.isRelayPeerFailed(string2)) continue;
                RoomInfo.PeerInfo peerInfo2 = new RoomInfo.PeerInfo(string2);
                peerInfo2.mappedIp = string3;
                peerInfo2.mappedPort = n;
                peerInfo2.natType = string4;
                peerInfo2.capabilities = Collections.singleton("relay");
                arrayList.add(peerInfo2);
            }
            if (arrayList.isEmpty()) {
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_declined", new JsonObject(), string);
            } else {
                this.dispatchRelaySetup(roomState, arrayList, string, peerInfo);
            }
        })).exceptionally(object -> {
            VoxLinkMod.LOGGER.debug("[Relay] Fetch global candidates failed: {}", (Object)((Throwable)object).getMessage());
            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_declined", new JsonObject(), string);
            return null;
        });
    }

    private void dispatchRelaySetup(RoomManager.RoomState roomState, List<RoomInfo.PeerInfo> list, String string, RoomInfo.PeerInfo peerInfo3) {
        RelayBridge relayBridge = RelayBridge.getInstance(this.scheduler);
        list.sort((peerInfo, peerInfo2) -> {
            int n = relayBridge.getRelayCountForPeer(peerInfo.clientId);
            int n2 = relayBridge.getRelayCountForPeer(peerInfo2.clientId);
            return Integer.compare(n, n2);
        });
        RoomInfo.PeerInfo peerInfo4 = list.get(0);
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("targetClientId", string);
        jsonObject.addProperty("targetIp", peerInfo3.mappedIp);
        jsonObject.addProperty("targetPort", (Number)peerInfo3.mappedPort);
        this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "relay_setup", jsonObject, peerInfo4.clientId);
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("relayIp", peerInfo4.mappedIp);
        jsonObject2.addProperty("relayPort", (Number)peerInfo4.mappedPort);
        this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "relay_notify", jsonObject2, string);
    }

    public void handleRelayAccept(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            String string2 = jsonObject.has("forClientId") ? jsonObject.get("forClientId").getAsString() : null;
            String string3 = string2;
            if (string2 != null) {
                JsonObject jsonObject2 = new JsonObject();
                jsonObject2.addProperty("connected", Boolean.valueOf(true));
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "relay_notify", jsonObject2, string2);
            }
        }
    }

    public void handleRelayNotify(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            if (jsonObject.has("connected") && jsonObject.get("connected").getAsBoolean()) {
                VoxLinkMod.LOGGER.info("[Relay] Received relay_notify(connected), relay ready");
                this.relayConnectedSignaled = true;
                this.clearRelayTracking();
                this.connectionWon.set(true);
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.relay.connected_via", (Object[])new Object[0]));
                roomState.roomInfo.setUsingRelay(true);
            } else if (!VoxLinkMod.getConfig().isRelayEnabled()) {
                VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, reject relay_notify");
            } else {
                String string2 = jsonObject.has("relayIp") ? jsonObject.get("relayIp").getAsString() : null;
                int n = jsonObject.has("relayPort") ? jsonObject.get("relayPort").getAsInt() : 0;
                int n2 = n;
                if (string2 != null && n > 0) {
                    VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", (Object)string2, (Object)n);
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.relay.trying", (Object[])new Object[0]));
                    ArrayList<UdpHolePuncher> arrayList = new ArrayList<UdpHolePuncher>();
                    AtomicBoolean atomicBoolean = new AtomicBoolean(false);
                    String string3 = string2;
                    int n3 = n;
                    for (int i = 0; i < this.punchProfile().relaySocketCount; ++i) {
                        UdpHolePuncher udpHolePuncher = new UdpHolePuncher();
                        this.applyPunchTemplate(udpHolePuncher);
                        try {
                            udpHolePuncher.createSocket();
                        }
                        catch (Exception exception) {
                            continue;
                        }
                        arrayList.add(udpHolePuncher);
                        this.activeHolePunchers.put("relay_to_cone_" + i, udpHolePuncher);
                        int n4 = i;
                        ((CompletableFuture)ConnectionManager.delayedFail(udpHolePuncher.punch(string3, n3), 15L).thenAccept(punchResult -> {
                            if (!punchResult.isSuccess()) {
                                PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                                this.lastPunchResult = punchResult.withReason(failureReason);
                                this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
                                VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                            } else {
                                DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                                if (!atomicBoolean.compareAndSet(false, true)) {
                                    try {
                                        udpHolePuncher.close();
                                    }
                                    catch (Exception exception) {}
                                } else if (this.connectionWon.compareAndSet(false, true) || this.relayConnectedSignaled && !this.activeUdpTransports.containsKey("relay_cone")) {
                                    UdpHolePuncher udpHolePuncher22;
                                    VoxLinkMod.LOGGER.info("[Relay] Sym->Cone socket#{} punch success", (Object)n4);
                                    udpHolePuncher.markSocketTransferred();
                                    this.stopAllPunchingAfterHostBridge();
                                    udpHolePuncher.stopPunch();
                                    for (UdpHolePuncher udpHolePuncher22 : arrayList) {
                                        if (udpHolePuncher22 == udpHolePuncher) continue;
                                        try {
                                            udpHolePuncher22.cancel();
                                            udpHolePuncher22.close();
                                        }
                                        catch (Exception exception) {}
                                    }
                                    ReliableUdpTransport reliableUdpTransport = new ReliableUdpTransport(datagramSocket, new InetSocketAddress(string3, n3));
                                    this.activeUdpTransports.put("relay_cone", reliableUdpTransport);
                                    reliableUdpTransport.start();
                                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.relay.connected_via", (Object[])new Object[0]));
                                    roomState.roomInfo.setUsingRelay(true);
                                    this.startUdpPunchBridge(roomState, reliableUdpTransport);
                                    udpHolePuncher22 = new JsonObject();
                                    udpHolePuncher22.addProperty("clientId", roomState.roomInfo.getClientId());
                                    this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_ready", (JsonObject)udpHolePuncher22, "host").exceptionally(throwable -> {
                                        VoxLinkMod.LOGGER.debug("relay_ready send failed: {}", (Object)throwable.getMessage());
                                        return null;
                                    });
                                    this.scheduler.schedule(() -> {
                                        Minecraft minecraft = Minecraft.func_71410_x();
                                        if (minecraft.field_71439_g != null) {
                                            VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.relay.connected_via", (Object[])new Object[0])));
                                        }
                                    }, 2L, TimeUnit.SECONDS);
                                } else {
                                    try {
                                        udpHolePuncher.close();
                                    }
                                    catch (Exception exception) {
                                        // empty catch block
                                    }
                                }
                            }
                        })).exceptionally(throwable -> {
                            if (!atomicBoolean.get()) {
                                try {
                                    udpHolePuncher.close();
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                                this.activeHolePunchers.remove("relay_to_cone_" + n4);
                            }
                            return null;
                        });
                    }
                    this.scheduler.schedule(() -> {
                        if (!atomicBoolean.get() && !this.connectionWon.get()) {
                            VoxLinkMod.LOGGER.warn("[Relay] Sym->Cone relay punch timeout (8s)");
                            for (UdpHolePuncher udpHolePuncher : arrayList) {
                                try {
                                    udpHolePuncher.cancel();
                                    udpHolePuncher.close();
                                }
                                catch (Exception exception) {}
                            }
                            this.activeHolePunchers.entrySet().removeIf(entry -> ((String)entry.getKey()).startsWith("relay_to_cone_"));
                            this.showConnectFailed(roomState, "voxlink.connection.relay_failed");
                        }
                    }, 8L, TimeUnit.SECONDS);
                }
            }
        }
    }

    public void handleRelayDeclined(String string, JsonObject jsonObject) {
        this.clearRelayTracking();
        this.notifyRelayFailed();
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            this.showConnectFailed(roomState, "voxlink.connection.relay_failed");
        }
    }

    private void scheduleRelayRegistrationRenewal(RoomManager.RoomState roomState, String string, String string2, int n) {
        this.scheduler.schedule(() -> {
            if (this.roomManager.currentRoom.get() == roomState && !this.connectionWon.get() && roomState.roomInfo.getClientId() != null && string2 != null && n > 0) {
                boolean bl = VoxLinkMod.getConfig().isRelayEnabled();
                ((CompletableFuture)this.signalingClient.registerRelayPeer(roomState.roomInfo.getClientId(), roomState.roomInfo.getCode(), string, string2, n, bl).thenRun(() -> this.scheduleRelayRegistrationRenewal(roomState, string, string2, n))).exceptionally(object -> {
                    VoxLinkMod.LOGGER.debug("relay renew failed: {}", (Object)((Throwable)object).getMessage());
                    return null;
                });
            }
        }, 60L, TimeUnit.SECONDS);
    }

    public void handleRelayReady(String string, JsonObject jsonObject) {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            VoxLinkMod.LOGGER.info("[Relay] Received relay_ready from joiner {}, mark relay connected", (Object)string);
            this.clearRelayTracking();
            this.stopAllPunchingAfterHostBridge();
            this.activeHolePunchers.entrySet().removeIf(entry -> ((String)entry.getKey()).startsWith("host_"));
            if (this.connectionWon.compareAndSet(false, true)) {
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.relay.connected_via", (Object[])new Object[0]));
                roomState.roomInfo.setUsingRelay(true);
            }
        }
    }

    public void handleRelaySetup(String string, JsonObject jsonObject) {
        if (!VoxLinkMod.getConfig().isRelayEnabled()) {
            RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
            if (roomState != null && roomState != RoomManager.PENDING) {
                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_declined", new JsonObject(), string);
            }
        } else {
            RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
            if (roomState != null && roomState != RoomManager.PENDING) {
                String string2 = jsonObject.has("targetClientId") ? jsonObject.get("targetClientId").getAsString() : null;
                String string3 = jsonObject.has("targetIp") ? jsonObject.get("targetIp").getAsString() : null;
                int n = jsonObject.has("targetPort") ? jsonObject.get("targetPort").getAsInt() : 0;
                int n2 = n;
                if (string3 != null && n > 0) {
                    RoomManager.RoomState roomState2 = this.roomManager.currentRoom.get();
                    ReliableUdpTransport reliableUdpTransport = this.activeUdpTransports.get("joiner");
                    if (reliableUdpTransport == null || !reliableUdpTransport.isConnected()) {
                        for (Map.Entry object : this.activeUdpTransports.entrySet()) {
                            if (!((ReliableUdpTransport)object.getValue()).isConnected()) continue;
                            reliableUdpTransport = (ReliableUdpTransport)object.getValue();
                            break;
                        }
                    }
                    if (reliableUdpTransport != null) {
                        ArrayList arrayList = new ArrayList();
                        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
                        String string4 = string3;
                        int n3 = n;
                        String string5 = string2;
                        ReliableUdpTransport reliableUdpTransport2 = reliableUdpTransport;
                        for (int i = 0; i < this.punchProfile().relaySocketCount; ++i) {
                            UdpHolePuncher udpHolePuncher = new UdpHolePuncher();
                            this.applyPunchTemplate(udpHolePuncher);
                            try {
                                udpHolePuncher.createSocket();
                            }
                            catch (Exception n4) {
                                continue;
                            }
                            arrayList.add(udpHolePuncher);
                            this.activeHolePunchers.put("relay_to_sym_" + i, udpHolePuncher);
                            int n4 = i;
                            ((CompletableFuture)ConnectionManager.delayedFail(udpHolePuncher.punchWithPortPrediction(string4, n3, this.punchProfile().coneBackupPortRange), 15L).thenAccept(punchResult -> {
                                if (!punchResult.isSuccess()) {
                                    PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
                                    this.lastPunchResult = punchResult.withReason(failureReason);
                                    this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
                                    VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
                                } else {
                                    DatagramSocket datagramSocket = punchResult.getSuccessSocket();
                                    if (!atomicBoolean.compareAndSet(false, true)) {
                                        try {
                                            udpHolePuncher.close();
                                        }
                                        catch (Exception exception) {}
                                    } else {
                                        UdpHolePuncher udpHolePuncher22;
                                        VoxLinkMod.LOGGER.info("[Relay] Cone->Sym socket#{} punch success", (Object)n4);
                                        udpHolePuncher.markSocketTransferred();
                                        for (UdpHolePuncher udpHolePuncher22 : arrayList) {
                                            if (udpHolePuncher22 == udpHolePuncher) continue;
                                            try {
                                                udpHolePuncher22.cancel();
                                                udpHolePuncher22.close();
                                            }
                                            catch (Exception exception) {}
                                        }
                                        ReliableUdpTransport reliableUdpTransport2 = new ReliableUdpTransport(datagramSocket, new InetSocketAddress(string4, n3));
                                        reliableUdpTransport2.start();
                                        this.activeUdpTransports.put(string5 != null ? string5 : "sym_relayed", reliableUdpTransport2);
                                        RelayBridge.getInstance(this.scheduler).startRelay("host", string5 != null ? string5 : "sym", reliableUdpTransport2, reliableUdpTransport2);
                                        udpHolePuncher22 = new JsonObject();
                                        udpHolePuncher22.addProperty("forClientId", string5 != null ? string5 : "sym");
                                        this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_accept", (JsonObject)udpHolePuncher22, "host");
                                    }
                                }
                            })).exceptionally(throwable -> {
                                if (!atomicBoolean.get()) {
                                    try {
                                        udpHolePuncher.close();
                                    }
                                    catch (Exception exception) {
                                        // empty catch block
                                    }
                                    this.activeHolePunchers.remove("relay_to_sym_" + n4);
                                }
                                return null;
                            });
                        }
                        this.scheduler.schedule(() -> {
                            if (!atomicBoolean.get()) {
                                VoxLinkMod.LOGGER.warn("[Relay] Cone->Sym relay punch timeout (8s)");
                                for (UdpHolePuncher udpHolePuncher : arrayList) {
                                    try {
                                        udpHolePuncher.cancel();
                                        udpHolePuncher.close();
                                    }
                                    catch (Exception exception) {}
                                }
                                this.activeHolePunchers.entrySet().removeIf(entry -> ((String)entry.getKey()).startsWith("relay_to_sym_"));
                                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "relay_declined", new JsonObject(), "host");
                            }
                        }, 8L, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    public void onRelayDisconnected(String string, String string2) {
        VoxLinkMod.LOGGER.warn("[Relay] Relay disconnect notify: {}<->{}", (Object)string, (Object)string2);
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && !this.connectionWon.get()) {
            if (this.currentRelayPeer.get() != null) {
                this.failedRelayPeers.put(this.currentRelayPeer.get(), System.currentTimeMillis());
            }
            this.clearRelayTracking();
            this.scheduler.schedule(() -> {
                if (this.roomManager.currentRoom.get() == roomState && !this.connectionWon.get()) {
                    VoxLinkMod.LOGGER.info("[Relay] Auto switch to backup relay...");
                    this.tryRelay(roomState);
                }
            }, 500L, TimeUnit.MILLISECONDS);
        }
    }

    public void showConnectFailedFinal(RoomManager.RoomState roomState) {
        this.showConnectFailedFinal(roomState, "voxlink.connection.all_failed");
    }

    public void showConnectFailedFinal(RoomManager.RoomState roomState, String string) {
        this.clearRelayTracking();
        this.failedRelayPeers.clear();
        this.cancelConnectionCycleSafety();
        int n = roomState != null && roomState.roomInfo != null ? roomState.roomInfo.getLocalBridgePort() : -1;
        int n2 = n;
        if (this.connectionWon.get() || this.voxlinkWon || this.terracottaWon || n > 0) {
            VoxLinkMod.LOGGER.info("[Connection] Bridge established/won, ignore showConnectFailedFinal (connWon={} voxlinkWon={} terracottaWon={} bridgePort={})", new Object[]{this.connectionWon.get(), this.voxlinkWon, this.terracottaWon, n});
        } else if (this.continuousRetryRound.get() > 0 && !this.continuousRetryCancelled.get()) {
            if (roomState != null && roomState.roomInfo != null) {
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.retrying", (Object[])new Object[0]));
            }
        } else if (this.dualRaceActive && !this.terracottaWon) {
            if (roomState != null && roomState.roomInfo != null) {
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.cannot_establish", (Object[])new Object[0]));
            }
            this.voxlinkSideDisabled = true;
            if (this.dualResultRef != null && this.dualFailedCount.incrementAndGet() >= 2) {
                this.dualResultRef.completeExceptionally(new RuntimeException("\u6240\u6709\u8fde\u63a5\u65b9\u5f0f\u5931\u8d25"));
            }
        } else if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, ignore showConnectFailedFinal");
        } else {
            this.connectionCycleActive.set(false);
            this.connectionWon.set(false);
            ConnectionState.transitionTo(ConnectionState.FAILED, "\u6240\u6709\u8fde\u63a5\u65b9\u5f0f\u5931\u8d25");
            if (this.connectionTimeoutFuture != null) {
                this.connectionTimeoutFuture.cancel(false);
                this.connectionTimeoutFuture = null;
            }
            ConnectionHelper.resetConnecting();
            roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)string, (Object[])new Object[0]), true);
            this.sendDisconnectOnFailure(roomState);
            P2PBridge.disconnect();
            P2PBridge.cancelPendingUdpTimeouts();
            for (UdpHolePuncher object : this.activeHolePunchers.values()) {
                object.cancel();
                object.close();
            }
            this.activeHolePunchers.clear();
            for (ReliableUdpTransport reliableUdpTransport : this.activeUdpTransports.values()) {
                try {
                    reliableUdpTransport.close();
                }
                catch (Exception exception) {}
            }
            this.activeUdpTransports.clear();
            Iterator<Object> iterator = Minecraft.func_71410_x();
            if (iterator != null) {
                iterator.func_152344_a(() -> {
                    if (minecraft.field_71439_g != null) {
                        VoxLinkChat.print(new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.error_prefix", (Object[])new Object[0])).func_150257_a((ITextComponent)new TextComponentString(I18n.func_135052_a((String)string, (Object[])new Object[0]))));
                        if (roomState.roomInfo.isSameCgnat()) {
                            VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.same_cgnat_warning", (Object[])new Object[0])));
                        }
                    }
                    this.leaveRoomOnFailure(roomState);
                });
            }
        }
    }

    private void leaveRoomOnFailure(RoomManager.RoomState roomState) {
        if (this.roomManager.currentRoom.get() == roomState && roomState != RoomManager.PENDING && roomState.roomInfo != null && roomState.roomInfo.isHost()) {
            try {
                this.scheduler.execute(() -> this.roomManager.leaveRoom("\u8fde\u63a5\u5931\u8d25"));
            }
            catch (RejectedExecutionException rejectedExecutionException) {
                VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute leaveRoom");
                this.roomManager.leaveRoom("\u8fde\u63a5\u5931\u8d25");
            }
        }
    }

    public void killAllConnectionAttempts() {
        boolean bl = this.connectionWon.get();
        if (!bl && !this.continuousRetryCancelled.getAndSet(true) && this.continuousRetryRound.get() > 0) {
            this.sendCancelConnectionSignal();
        } else {
            this.continuousRetryCancelled.set(true);
        }
        this.connectionCycleActive.set(false);
        this.connectionWon.set(true);
        if (!bl) {
            ConnectionState.reset();
        }
        if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
            this.connectionTimeoutFuture = null;
        }
        this.cancelConnectionCycleSafety();
        for (UdpHolePuncher object : this.activeHolePunchers.values()) {
            try {
                object.cancel();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                object.stopPunch();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                object.close();
            }
            catch (Exception exception) {}
        }
        this.activeHolePunchers.clear();
        for (ReliableUdpTransport reliableUdpTransport : this.activeUdpTransports.values()) {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {}
        }
        this.activeUdpTransports.clear();
        for (ReliableUdpTransport reliableUdpTransport : this.oldUdpTransports.values()) {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {}
        }
        this.oldUdpTransports.clear();
        this.cancelAllFallbacks();
        this.hostPunching = false;
        this.lastPunchInfoId = "";
        this.closeVoiceForwardBridges();
    }

    public void killAllConnectionAttempts(String string) {
        if (string == null) {
            this.killAllConnectionAttempts();
        } else {
            switch (string) {
                case "voxlink": {
                    this.killAllConnectionAttempts();
                    P2PBridge.disconnect();
                    break;
                }
                default: {
                    this.killAllConnectionAttempts();
                }
            }
            VoxLinkMod.LOGGER.info("[DualP2P] Abort {} side connection attempt", (Object)string);
        }
    }

    public void killDualRace() {
        this.dualRaceActive = false;
        this.voxlinkSideDisabled = true;
        this.killAllConnectionAttempts("voxlink");
        this.killAllConnectionAttempts("terracotta");
        CompletableFuture<Void> completableFuture = this.dualVoxlinkBridgeFuture;
        if (completableFuture != null && !completableFuture.isDone()) {
            completableFuture.completeExceptionally(new RuntimeException("\u7528\u6237\u53d6\u6d88"));
        }
        this.dualVoxlinkBridgeFuture = null;
        this.dualResultRef = null;
        this.dualFailedCount.set(0);
        this.terracottaWon = false;
        this.voxlinkWon = false;
        this.dualRaceWon.set(false);
    }

    public void resetDualRaceState() {
        this.dualRaceActive = false;
        this.terracottaWon = false;
        this.voxlinkWon = false;
        this.voxlinkSideDisabled = false;
        this.dualFailedCount.set(0);
        this.dualResultRef = null;
        this.dualVoxlinkBridgeFuture = null;
        this.dualRaceWon.set(false);
    }

    public void resetContinuousRetryState() {
        this.continuousRetryCancelled.set(false);
        this.continuousRetryRound.set(0);
        this.lastFailureReason = null;
        this.consecutiveFailureCount.set(0);
    }

    private void resetConnectionStateForNextP2P() {
        this.localNatClass = NatClass.UNKNOWN;
        this.remoteNatClass = NatClass.UNKNOWN;
        this.scenarioTier = ScenarioTier.Tier.NORMAL;
        this.activePunchParams = null;
        this.lastPunchResult = null;
        this.lastHostMappedPorts = null;
        for (UdpHolePuncher udpHolePuncher : this.activeHolePunchers.values()) {
            try {
                udpHolePuncher.cancel();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception) {}
        }
        this.activeHolePunchers.clear();
        PunchProfile.switchToDefault("new_connection");
        PunchProfile.clearDynamicParams();
        StunDetector.clearLocalCaches();
    }

    private boolean shouldContinuousRetry(RoomManager.RoomState roomState) {
        if (this.continuousRetryCancelled.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Player cancelled persistent retry");
            return false;
        }
        if (roomState == null || roomState == RoomManager.PENDING || roomState.roomInfo == null) {
            return false;
        }
        if (roomState.roomInfo.isHost()) {
            return roomState.roomInfo.getPeers().isEmpty() ? false : roomState.roomInfo.getPeers().stream().allMatch(ProtocolNegotiator::supportsContinuousRetry);
        }
        return roomState.roomInfo.isHostLegacy() ? false : roomState.roomInfo.getHostCapabilities().contains("continuous_retry");
    }

    private void escalateProfileForRound(int n) {
        PunchProfile punchProfile = this.punchProfile();
        if (punchProfile != PunchProfile.HARDSYM) {
            PunchProfile punchProfile2 = punchProfile == PunchProfile.AGGRESSIVE ? PunchProfile.HARDSYM : PunchProfile.AGGRESSIVE;
            this.switchPunchProfile(punchProfile2, "continuous_retry_round_" + n);
        }
    }

    public void handleCancelConnection(String string, JsonObject jsonObject) {
        VoxLinkMod.LOGGER.info("[Connection] Received cancel_connection signal from peer ({}), abort persistent retry", (Object)string);
        this.continuousRetryCancelled.set(true);
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING) {
            roomState.roomInfo.removePeer(string);
            this.showConnectFailedFinal(roomState, "voxlink.connection.peer_cancelled");
            Minecraft minecraft = Minecraft.func_71410_x();
            if (minecraft != null && minecraft.field_71439_g != null) {
                minecraft.func_152344_a(() -> VoxLinkChat.print(new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.error_prefix", (Object[])new Object[0])).func_150257_a((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.connection.peer_cancelled", (Object[])new Object[0])))));
            }
        }
    }

    public void notifyAllPeersGone() {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo.isHost()) {
            if (roomState.roomInfo.hasRecentPeer(60000L)) {
                VoxLinkMod.LOGGER.info("[Connection] Peers still active in signaling, skip all-peers-gone");
            } else if (!roomState.roomInfo.hasEverHadPeer()) {
                VoxLinkMod.LOGGER.info("[Connection] No peer ever joined, keep waiting");
            } else if (ConnectionState.getCurrent() == ConnectionState.IDLE) {
                VoxLinkMod.LOGGER.info("[Connection] IDLE state, clear stale flags and keep waiting");
                this.connectionCycleActive.set(false);
                this.continuousRetryRound.set(0);
            } else {
                boolean bl = this.connectionCycleActive.get() || this.continuousRetryRound.get() > 0;
                this.continuousRetryCancelled.set(true);
                VoxLinkMod.LOGGER.info("[Connection] All peers gone (host alone), inConnection={}, abort persistent retry", (Object)bl);
                roomState.roomInfo.clearPeers();
                if (!bl) {
                    this.connectionCycleActive.set(false);
                    ConnectionState.transitionTo(ConnectionState.IDLE, "\u5bf9\u65b9\u79bb\u5f00,\u56de\u7b49\u5f85");
                } else {
                    this.showConnectFailedFinal(roomState, "voxlink.connection.peer_left");
                }
            }
        }
    }

    private void sendCancelConnectionSignal() {
        try {
            RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
            if (roomState == null || roomState == RoomManager.PENDING || roomState.roomInfo == null) {
                return;
            }
            String string = roomState.roomInfo.isHost() ? "all" : "host";
            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "cancel_connection", new JsonObject(), string);
            VoxLinkMod.LOGGER.info("[Connection] Sent cancel_connection signal to peer ({})", (Object)string);
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[Connection] Send cancel_connection signal failed: {}", (Object)exception.getMessage());
        }
    }

    public void resetIceRestartState() {
        this.iceRestartAttempts.set(0);
        this.lastIceRestartTimeMs.set(0L);
        this.savedConnectionState = null;
    }

    public void handleIceRestart(String string, JsonObject jsonObject) {
        VoxLinkMod.LOGGER.info("[Connection] Received ice_restart signal from peer ({})", (Object)string);
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState != null && roomState != RoomManager.PENDING && roomState.roomInfo != null) {
            this.performIceRestart(roomState, "\u6536\u5230\u5bf9\u7aefice_restart\u4fe1\u53f7");
        } else {
            VoxLinkMod.LOGGER.info("[Connection] Received ice_restart but left room, ignore");
        }
    }

    public void triggerIceRestart() {
        RoomManager.RoomState roomState = this.roomManager.currentRoom.get();
        if (roomState == null || roomState == RoomManager.PENDING || roomState.roomInfo == null) {
            VoxLinkMod.LOGGER.info("[Connection] Transport disconnected but left room, no ICE Restart");
        } else if (!this.shouldIceRestart(roomState)) {
            VoxLinkMod.LOGGER.info("[Connection] Peer does not support ice_restart, go original disconnect logic");
        } else {
            this.performIceRestart(roomState, "\u4f20\u8f93\u5c42\u65ad\u5f00");
            this.sendIceRestartSignal(roomState);
        }
    }

    private boolean shouldIceRestart(RoomManager.RoomState roomState) {
        if (roomState == null || roomState == RoomManager.PENDING || roomState.roomInfo == null) {
            return false;
        }
        if (roomState.roomInfo.isHost()) {
            return roomState.roomInfo.getPeers().isEmpty() ? false : roomState.roomInfo.getPeers().stream().allMatch(ProtocolNegotiator::supportsIceRestart);
        }
        return roomState.roomInfo.isHostLegacy() ? false : roomState.roomInfo.getHostCapabilities().contains("ice_restart");
    }

    private void performIceRestart(RoomManager.RoomState roomState, String string) {
        long l;
        long l2 = System.currentTimeMillis();
        if (l2 - (l = this.lastIceRestartTimeMs.get()) < 5000L) {
            VoxLinkMod.LOGGER.info("[Connection] ICE Restart cooldown (within {}ms), ignore trigger ({})", (Object)5000L, (Object)string);
        } else {
            int n = this.iceRestartAttempts.incrementAndGet();
            if (n > 3) {
                VoxLinkMod.LOGGER.warn("[Connection] ICE Restart reached max {}, give up", (Object)3);
            } else {
                RoomManager.RoomState roomState2;
                this.lastIceRestartTimeMs.set(l2);
                VoxLinkMod.LOGGER.info("[Connection] ICE Restart trigger ({}/{}): {}", new Object[]{n, 3, string});
                this.connectionWon.set(false);
                this.connectionCycleActive.set(false);
                this.reversePunchAttempted.set(false);
                this.dualRaceActive = false;
                this.terracottaWon = false;
                this.voxlinkWon = false;
                this.voxlinkSideDisabled = false;
                this.dualVoxlinkBridgeFuture = null;
                if (this.connectionTimeoutFuture != null) {
                    this.connectionTimeoutFuture.cancel(false);
                    this.connectionTimeoutFuture = null;
                }
                this.stopAllConnectionWork();
                this.clearRelayTracking();
                ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "ICE Restart " + n + "/3");
                Minecraft minecraft = Minecraft.func_71410_x();
                if (minecraft != null && minecraft.field_71439_g != null) {
                    minecraft.func_152344_a(() -> VoxLinkChat.print(new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.error_prefix", (Object[])new Object[0])).func_150257_a((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.connection.ice_restart", (Object[])new Object[0])))));
                }
                if ((roomState2 = this.savedConnectionState) != null && roomState2 == roomState && this.savedConnectionHostIp != null) {
                    int n2;
                    this.connectionStartTimeMs = System.currentTimeMillis();
                    this.connectionTimeoutSec = n2 = this.punchProfile().connectionTimeoutSec;
                    roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.connecting", (Object[])new Object[0]));
                    this.scheduleConnectionTimeout(roomState, n2);
                    this.scheduler.schedule(() -> {
                        if (this.roomManager.currentRoom.get() == roomState2) {
                            this.runConnectionCycle(roomState2, this.savedConnectionFrom, this.savedConnectionHostIpv6, this.savedConnectionHostIp, this.savedConnectionHostPort, this.savedConnectionHostMappedIp, this.savedConnectionHostMappedPort, 0);
                        }
                    }, 500L, TimeUnit.MILLISECONDS);
                } else {
                    VoxLinkMod.LOGGER.warn("[Connection] ICE Restart no saved params, cannot re-trigger");
                }
            }
        }
    }

    private void sendIceRestartSignal(RoomManager.RoomState roomState) {
        try {
            if (roomState.roomInfo == null) {
                return;
            }
            String string = roomState.roomInfo.isHost() ? "all" : "host";
            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "ice_restart", new JsonObject(), string);
            VoxLinkMod.LOGGER.info("[Connection] Sent ice_restart signal to peer ({})", (Object)string);
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[Connection] Send ice_restart signal failed: {}", (Object)exception.getMessage());
        }
    }

    public void markConnectionEstablished() {
        this.connectionEstablishedAtMs = System.currentTimeMillis();
    }

    public boolean isConnectionInHandoff() {
        return this.connectionEstablishedAtMs > 0L && System.currentTimeMillis() - this.connectionEstablishedAtMs < 5000L && !ConnectionHelper.isMcTrulyConnected();
    }

    public void notifyDualVoxlinkBridge(boolean bl) {
        CompletableFuture<Void> completableFuture = this.dualVoxlinkBridgeFuture;
        if (completableFuture != null) {
            if (bl) {
                completableFuture.complete(null);
            } else {
                completableFuture.completeExceptionally(new RuntimeException("VoxLink\u6865\u5efa\u7acb\u5931\u8d25"));
            }
        }
    }

    private boolean claimVoxlinkDualWin() {
        if (!this.dualRaceActive) {
            return true;
        }
        if (this.dualRaceWon.compareAndSet(false, true)) {
            this.voxlinkWon = true;
            return true;
        }
        return false;
    }

    public CompletableFuture<Void> startDualP2P(String string, String string2, String string3, BiConsumer<String, String> biConsumer) {
        this.resetConnectionStateForNextP2P();
        this.resetDualRaceState();
        this.resetContinuousRetryState();
        this.connectionWon.set(false);
        VoxLinkMod.LOGGER.info("[DualP2P] startDualP2P roomCode={} (VoxLink only)", (Object)string);
        if (biConsumer != null) {
            biConsumer.accept("voxlink", "voxlink.connection.joining");
        }
        return this.startVoxLinkP2P(string, string3);
    }

    private static <T> CompletableFuture<T> delayedFail(CompletableFuture<T> completableFuture, long l) {
        CompletableFuture completableFuture2 = new CompletableFuture();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voxlink-cm-timeout");
            thread.setDaemon(true);
            return thread;
        });
        scheduledExecutorService.schedule(() -> {
            completableFuture2.completeExceptionally(new TimeoutException("timed out after " + l + "s"));
            scheduledExecutorService.shutdown();
        }, l, TimeUnit.SECONDS);
        return completableFuture.applyToEither((CompletionStage)completableFuture2, Function.identity());
    }

    private CompletableFuture<Void> startVoxLinkP2P(String string, String string2) {
        return this.roomManager.joinRoom(string, string2).thenAccept(roomInfo -> {});
    }

    public void sendDisconnectOnFailure(RoomManager.RoomState roomState) {
        try {
            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("Send disconnect failed on connection failure: {}", (Object)exception.getMessage());
        }
    }

    public void startUdpPunchBridge(RoomManager.RoomState roomState, ReliableUdpTransport reliableUdpTransport) {
        if (!this.terracottaWon && !this.voxlinkSideDisabled) {
            int n = P2PBridge.startUdpJoinerBridge(reliableUdpTransport);
            if (n > 0) {
                if (this.dualRaceActive) {
                    this.claimVoxlinkDualWin();
                }
                this.stopAllPunchingAfterHostBridge();
                this.connectionCycleActive.set(false);
                ConnectionState.transitionTo(ConnectionState.CONNECTED, "Joiner\u6865\u63a5\u5efa\u7acb port=" + n);
                this.markConnectionEstablished();
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.bridge_setup", (Object[])new Object[0]));
                ConnectionHelper.connectToServer(n, roomState.roomInfo);
                this.notifyDualVoxlinkBridge(true);
            } else {
                this.connectionCycleActive.set(false);
                ConnectionHelper.resetConnecting();
                ConnectionState.transitionTo(ConnectionState.FAILED, "\u6865\u63a5\u542f\u52a8\u5931\u8d25");
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.bridge_start_failed", (Object[])new Object[0]), true);
                this.sendDisconnectOnFailure(roomState);
                this.notifyDualVoxlinkBridge(false);
                if (!this.dualRaceActive) {
                    this.leaveRoomOnFailure(roomState);
                }
            }
        } else {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
            this.notifyDualVoxlinkBridge(false);
        }
    }

    public void startHostUdpPunchBridge(RoomManager.RoomState roomState, String string, ReliableUdpTransport reliableUdpTransport) {
        int n = roomState.roomInfo.getHostPort();
        ConnectionState.transitionTo(ConnectionState.CONNECTED, "Host\u6865\u63a5\u5efa\u7acb client=" + string);
        roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.connected", (Object[])new Object[0]));
        this.stopAllPunchingAfterHostBridge();
        P2PBridge.startUdpHostBridgeForClient(string, reliableUdpTransport, n, () -> {
            ReliableUdpTransport reliableUdpTransport = this.activeUdpTransports.remove(string);
            if (reliableUdpTransport != null) {
                try {
                    reliableUdpTransport.close();
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
            if (this.roomManager.currentRoom.get() == roomState && roomState != RoomManager.PENDING) {
                VoxLinkMod.LOGGER.warn("[HostBridge] client={} bridge disconnected, reset connectionWon for host re-punch", (Object)string);
                LogUploadManager.onDisconnected();
                this.connectionWon.set(false);
                this.connectionCycleActive.set(false);
                this.hostPunching = false;
                this.lastPunchInfoId = "";
                ConnectionState.transitionTo(ConnectionState.IDLE, "Host\u6865\u65ad\u5f00 client=" + string);
                roomState.roomInfo.setConnectionMode(I18n.func_135052_a((String)"voxlink.connection.punching", (Object[])new Object[0]));
            }
        });
    }

    public void putTransportWithIcePool(String string, ReliableUdpTransport reliableUdpTransport) {
        ReliableUdpTransport reliableUdpTransport2 = this.activeUdpTransports.put(string, reliableUdpTransport);
        if (reliableUdpTransport2 != null) {
            String string2 = string + "_old";
            this.oldUdpTransports.put(string2, reliableUdpTransport2);
            this.scheduler.schedule(() -> {
                ReliableUdpTransport reliableUdpTransport = this.oldUdpTransports.remove(string2);
                if (reliableUdpTransport != null) {
                    try {
                        reliableUdpTransport.close();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
            }, 12L, TimeUnit.SECONDS);
        }
    }

    public void sendConfirmPackets(DatagramSocket datagramSocket, InetSocketAddress inetSocketAddress) {
        try {
            byte[] byArray = new byte[]{86, 76, 2};
            DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, inetSocketAddress.getAddress(), inetSocketAddress.getPort());
            datagramSocket.send(datagramPacket);
            datagramSocket.send(datagramPacket);
            datagramSocket.send(datagramPacket);
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[Transport] Ack packet send failed: {}", (Object)exception.getMessage());
        }
    }

    public void establishUdpTransport(RoomManager.RoomState roomState, DatagramSocket datagramSocket, UdpHolePuncher udpHolePuncher, InetSocketAddress inetSocketAddress, String string, boolean bl, String string2) throws Exception {
        udpHolePuncher.waitForRecvThreadExit();
        InetSocketAddress inetSocketAddress2 = udpHolePuncher.getActualRemoteAddress();
        if (inetSocketAddress2 == null) {
            inetSocketAddress2 = inetSocketAddress;
        }
        this.sendConfirmPackets(datagramSocket, inetSocketAddress2);
        ReliableUdpTransport reliableUdpTransport = new ReliableUdpTransport(datagramSocket, inetSocketAddress2);
        reliableUdpTransport.setOnIceRestartRequested(this::triggerIceRestart);
        this.putTransportWithIcePool(string, reliableUdpTransport);
        LogUploadManager.onConnected();
        if (!bl) {
            this.connectionCycleActive.set(false);
            ConnectionHelper.resetConnecting();
        }
        reliableUdpTransport.start();
        if (bl) {
            ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Host ReliableUdp\u542f\u52a8 client=" + string2);
            this.startHostUdpPunchBridge(roomState, string2, reliableUdpTransport);
        } else {
            ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Joiner ReliableUdp\u542f\u52a8");
            this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "connected", new JsonObject(), "host");
            this.startUdpPunchBridge(roomState, reliableUdpTransport);
        }
        this.startVoiceForwardBridge(reliableUdpTransport, bl);
    }

    private void startVoiceForwardBridge(ReliableUdpTransport reliableUdpTransport, boolean bl) {
        try {
            UdpForwardBridge udpForwardBridge = new UdpForwardBridge(reliableUdpTransport, bl, 24454, 25565);
            if (udpForwardBridge.isActive()) {
                this.udpForwardBridges.add(udpForwardBridge);
            } else {
                udpForwardBridge.close();
            }
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[UdpForward] voice relay failed to start: {}", (Object)exception.getMessage());
        }
    }

    private void closeVoiceForwardBridges() {
        for (UdpForwardBridge udpForwardBridge : this.udpForwardBridges) {
            try {
                udpForwardBridge.close();
            }
            catch (Exception exception) {}
        }
        this.udpForwardBridges.clear();
    }

    public void shutdown() {
        if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
            this.connectionTimeoutFuture = null;
        }
        if (this.cachedUdpArray != null) {
            try {
                this.cachedUdpArray.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
            this.cachedUdpArray = null;
        }
        this.stopAllConnectionWork();
        if (this.punchExecutor != null && !this.punchExecutor.isShutdown()) {
            this.punchExecutor.shutdown();
            try {
                this.punchExecutor.awaitTermination(2L, TimeUnit.SECONDS);
            }
            catch (InterruptedException interruptedException) {
                // empty catch block
            }
        }
        this.clearRelayTracking();
        this.failedRelayPeers.clear();
    }

    private /* synthetic */ void lambda$tryRelay$107(RoomManager.RoomState roomState, int n, List list) {
        if (!this.connectionWon.get() && this.roomManager.currentRoom.get() == roomState) {
            VoxLinkMod.LOGGER.warn("[Relay] Parallel relay all timeout, mark {} failed", (Object)n);
            for (RoomInfo.PeerInfo peerInfo : list) {
                this.failedRelayPeers.put(peerInfo.clientId, System.currentTimeMillis());
            }
            this.currentRelayPeer.set(null);
            this.relayFailoverTask = null;
            this.tryRelay(roomState);
        }
    }

    private /* synthetic */ void lambda$tryConnectionStep$53(RoomManager.RoomState roomState, String string, AtomicBoolean atomicBoolean, String string2, int n, ConnectionFallback.ConnectResult connectResult) {
        if (this.roomManager.currentRoom.get() == roomState && connectResult.success && this.connectionWon.compareAndSet(false, true)) {
            VoxLinkMod.LOGGER.info("[Connection] Wave 1: {} direct connect won", (Object)string);
            atomicBoolean.set(true);
            this.connectViaBridge(roomState, connectResult);
        } else if (this.roomManager.currentRoom.get() == roomState && connectResult != null && !connectResult.success) {
            this.addressBlacklist.recordDirectFailure(new InetSocketAddress(string2, n));
        }
    }

    private /* synthetic */ Object lambda$handleReverseHolepunchOfferDelayed$40(ScheduledFuture scheduledFuture, String string, UdpHolePuncher udpHolePuncher, Object object) {
        scheduledFuture.cancel(false);
        VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch failed {}: {}", (Object)string, (Object)((Throwable)object).getMessage());
        udpHolePuncher.cancel();
        udpHolePuncher.close();
        this.activeHolePunchers.remove("hostRev");
        return null;
    }

    private /* synthetic */ void lambda$handleReverseHolepunchOfferDelayed$39(RoomManager.RoomState roomState, String string, int n, UdpHolePuncher udpHolePuncher, String string2, PunchResult punchResult) {
        if (!punchResult.isSuccess()) {
            PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
            this.lastPunchResult = punchResult.withReason(failureReason);
            this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
            VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
        } else {
            DatagramSocket datagramSocket = punchResult.getSuccessSocket();
            if (this.roomManager.currentRoom.get() == roomState && this.connectionWon.compareAndSet(false, true)) {
                VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse punch success, connected to joiner {}:{}", (Object)string, (Object)n);
                udpHolePuncher.markSocketTransferred();
                this.stopAllPunchingAfterHostBridge();
                udpHolePuncher.stopPunch();
                DatagramSocket datagramSocket2 = datagramSocket;
                UdpHolePuncher udpHolePuncher2 = udpHolePuncher;
                this.scheduler.submit(() -> {
                    try {
                        this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher2, new InetSocketAddress(string, n), string2, true, string2);
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.error("[ReversePunch] Host UDP transport create failed: {}", (Object)exception.getMessage());
                        udpHolePuncher2.close();
                    }
                });
            } else {
                udpHolePuncher.close();
            }
        }
    }

    private /* synthetic */ void lambda$handleReverseHolepunchOfferDelayed$37(UdpHolePuncher udpHolePuncher, String string) {
        if (this.activeHolePunchers.get("hostRev") == udpHolePuncher) {
            VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch timeout: {}", (Object)string);
            udpHolePuncher.cancel();
            udpHolePuncher.close();
            this.activeHolePunchers.remove("hostRev");
        }
    }

    private /* synthetic */ void lambda$handleHostPunchInfo$30(AtomicBoolean atomicBoolean, UdpHolePuncher udpHolePuncher, RoomManager.RoomState roomState, int n, String string, int n2, String string2, PunchResult punchResult) {
        if (!punchResult.isSuccess()) {
            PunchFailureClassifier.FailureReason failureReason = PunchFailureClassifier.classify(punchResult);
            this.lastPunchResult = punchResult.withReason(failureReason);
            this.activePunchParams = PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, failureReason, this.lastPunchResult);
            VoxLinkMod.LOGGER.info("[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}", new Object[]{failureReason, punchResult.socketsReceivedPunch, punchResult.socketsReceivedAck});
        } else {
            DatagramSocket datagramSocket = punchResult.getSuccessSocket();
            if (!atomicBoolean.compareAndSet(false, true)) {
                try {
                    udpHolePuncher.close();
                }
                catch (Exception exception) {}
            } else if (this.roomManager.currentRoom.get() == roomState && this.connectionWon.compareAndSet(false, true)) {
                VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} punch success!", (Object)n);
                udpHolePuncher.markSocketTransferred();
                this.stopAllPunchingAfterHostBridge();
                udpHolePuncher.stopPunch();
                DatagramSocket datagramSocket2 = datagramSocket;
                UdpHolePuncher udpHolePuncher2 = udpHolePuncher;
                this.scheduler.submit(() -> {
                    try {
                        this.establishUdpTransport(roomState, datagramSocket2, udpHolePuncher2, new InetSocketAddress(string, n2), string2, true, string2);
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.error("[HostPunchInfo] Transport create failed: {}", (Object)exception.getMessage());
                        udpHolePuncher2.close();
                    }
                });
            } else {
                try {
                    udpHolePuncher.close();
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
    }

    private /* synthetic */ void lambda$handleHostPunchInfo$27(String string, ArrayList arrayList) {
        if (this.hostPunching) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] 120s fallback cleanup host socket client={}", (Object)string);
            this.hostPunching = false;
            this.lastPunchInfoId = "";
            this.activeHolePunchers.remove("host");
            this.activeHolePunchers.entrySet().removeIf(entry -> ((String)entry.getKey()).startsWith("host_"));
            for (UdpHolePuncher udpHolePuncher : arrayList) {
                try {
                    udpHolePuncher.cancel();
                    udpHolePuncher.close();
                }
                catch (Exception exception) {}
            }
        }
    }

    private /* synthetic */ void lambda$handleHolePunchOffer$14(RoomManager.RoomState roomState, String string, StunProbe.ProbeResult probeResult) {
        this.stunProbeResult = probeResult;
        this.applyProbeResultToActiveConnection(roomState, string);
        this.extendConnectionTimeoutIfNeeded(roomState);
    }

    private /* synthetic */ void lambda$sendHolepunchOffer$13(boolean bl, String string, JsonObject jsonObject, UdpHolePuncher udpHolePuncher, RoomManager.RoomState roomState, String string2, int n, Object[] objectArray) {
        StunProbe.PublicMappedAddress publicMappedAddress = (StunProbe.PublicMappedAddress)objectArray[0];
        StunProbe.PublicMappedAddress publicMappedAddress2 = (StunProbe.PublicMappedAddress)objectArray[1];
        List list = (List)objectArray[2];
        StunProbe.PublicMappedAddress publicMappedAddress3 = null;
        boolean bl2 = false;
        boolean bl3 = bl;
        if (publicMappedAddress != null && publicMappedAddress2 != null) {
            if (publicMappedAddress.port() != publicMappedAddress2.port()) {
                bl2 = true;
                VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: symmetric NAT ({} vs {})", (Object)publicMappedAddress.port(), (Object)publicMappedAddress2.port());
            } else if (bl3 && !StunDetector.isNatTypeSymmetric(string)) {
                VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: same port {}, override isSymmetricOrUnknown (was {})", (Object)publicMappedAddress.port(), (Object)string);
                bl3 = false;
            }
            publicMappedAddress3 = publicMappedAddress2;
        } else {
            publicMappedAddress3 = publicMappedAddress != null ? publicMappedAddress : publicMappedAddress2;
            StunProbe.PublicMappedAddress publicMappedAddress4 = publicMappedAddress3;
        }
        if (bl2) {
            bl3 = true;
        }
        if (publicMappedAddress3 != null) {
            jsonObject.addProperty("hostMappedIp", publicMappedAddress3.ip());
            jsonObject.addProperty("hostMappedPort", (Number)publicMappedAddress3.port());
        } else if (this.stunProbeResult != null && !this.stunProbeResult.serverResults.isEmpty()) {
            for (StunProbe.StunServerResult object2 : this.stunProbeResult.serverResults) {
                if (!object2.reachable || object2.mappedIp == null || object2.mappedPort <= 0) continue;
                jsonObject.addProperty("hostMappedIp", object2.mappedIp);
                jsonObject.addProperty("hostMappedPort", (Number)object2.mappedPort);
                publicMappedAddress3 = new StunProbe.PublicMappedAddress(object2.mappedIp, object2.mappedPort);
                VoxLinkMod.LOGGER.info("[RoomManager] MC port STUN failed, fallback to NAT probe mapped address: {}:{}", (Object)object2.mappedIp, (Object)object2.mappedPort);
                break;
            }
        }
        if (publicMappedAddress3 != null) {
            if (bl3) {
                jsonObject.addProperty("hostSymmetric", Boolean.valueOf(true));
            }
            boolean bl4 = bl2 && publicMappedAddress != null && publicMappedAddress2 != null && Math.abs(publicMappedAddress2.port() - publicMappedAddress.port()) <= 100;
            boolean bl5 = bl4;
            if (bl4) {
                jsonObject.addProperty("hostEasySym", Boolean.valueOf(true));
            }
            if (publicMappedAddress != null && publicMappedAddress2 != null && publicMappedAddress.port() != publicMappedAddress2.port()) {
                int publicMappedAddress5 = publicMappedAddress2.port() - publicMappedAddress.port();
                int n2 = this.punchProfile().maxPortRange;
                if (udpHolePuncher != null && udpHolePuncher.getSocket() != null) {
                    List<Integer> list2 = StunProbe.samplePortsSequential(udpHolePuncher.getSocket(), StunDetector.getAllStunUrls(), 10, 100);
                    if (list2.size() >= 5) {
                        PortPredictor.PredictResult predictResult = PortPredictor.predict(list2);
                        int n3 = PortPredictor.deltaPredict(list2) - list2.get(list2.size() - 1);
                        if (n3 <= 0) {
                            n3 = ConnectionManager.calculatePortDelta(list2);
                        }
                        n2 = predictResult.range;
                        VoxLinkMod.LOGGER.info("[RoomManager] P-PRE samples: {} times -> sequence={}, combined predict port={}, range=\u00b1{}, delta={}", new Object[]{list2.size(), list2, predictResult.predictedPort, predictResult.range, n3});
                        publicMappedAddress5 = n3;
                    } else {
                        VoxLinkMod.LOGGER.warn("[RoomManager] P-PRE insufficient samples ({}), fallback to 2-sample delta={}", (Object)list2.size(), (Object)publicMappedAddress5);
                    }
                }
                jsonObject.addProperty("hostMappedPortDelta", (Number)publicMappedAddress5);
                jsonObject.addProperty("hostMappedPortRange", (Number)n2);
                VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT port delta: delta={}, range=\u00b1{}", (Object)publicMappedAddress5, (Object)n2);
            }
            roomState.roomInfo.setHostMappedAddress(publicMappedAddress3.ip(), publicMappedAddress3.port());
            roomState.roomInfo.setHostEasySym(bl4);
            VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: ip={}, port={}, symmetric={}, easySym={}", new Object[]{publicMappedAddress3.ip(), publicMappedAddress3.port(), bl3, bl4});
        }
        if (list != null && !list.isEmpty()) {
            JsonArray jsonArray = new JsonArray();
            for (StunProbe.PublicMappedAddress string3 : list) {
                jsonArray.add((Number)string3.port());
            }
            jsonObject.add("hostBirthdayPorts", (JsonElement)jsonArray);
            VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer with {} birthday ports", (Object)list.size());
        }
        long l = System.currentTimeMillis() + 3000L;
        jsonObject.addProperty("punchSyncTimeMs", (Number)l);
        jsonObject.addProperty("punchSyncSentAtMs", (Number)System.currentTimeMillis());
        roomState.roomInfo.setPunchSyncSentAtMs(System.currentTimeMillis());
        roomState.roomInfo.setPunchSyncTimeMs(l);
        VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={}", (Object)l);
        VoxLinkMod.LOGGER.info("[RoomManager] Send holepunch_offer to {} (hostIp={}, hostIpv6={}, port={}, mappedIp={}, mappedPort={})", new Object[]{string2, roomState.roomInfo.getHostIp() != null ? roomState.roomInfo.getHostIp() : "none", roomState.roomInfo.getHostIpv6() != null ? roomState.roomInfo.getHostIpv6() : "none", n, publicMappedAddress3 != null ? publicMappedAddress3.ip() : "none", publicMappedAddress3 != null ? publicMappedAddress3.port() : 0});
        ((CompletableFuture)this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true, "holepunch_offer", jsonObject, string2).thenAccept(apiResponse -> {
            if (!apiResponse.success) {
                VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer failed: {} - {}", (Object)apiResponse.error, (Object)apiResponse.message);
            }
        })).exceptionally(object -> {
            VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer network error: {}", (Object)((Throwable)object).getMessage());
            return null;
        });
        String string3 = string2;
        this.scheduler.schedule(() -> {
            if (!this.hostPunching && this.activeHolePunchers.containsKey("host")) {
                UdpHolePuncher udpHolePuncher = this.activeHolePunchers.remove("host");
                if (udpHolePuncher != null) {
                    try {
                        udpHolePuncher.close();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
                this.activeHolePunchers.entrySet().removeIf(entry -> ((String)entry.getKey()).startsWith("host_"));
                this.lastPunchInfoId = "";
                VoxLinkMod.LOGGER.info("[RoomManager] Wait punch_info timeout ({}s), cleanup host socket client={}", (Object)15, (Object)string3);
            }
        }, 15L, TimeUnit.SECONDS);
    }

    private /* synthetic */ Object[] lambda$sendHolepunchOffer$8(UdpHolePuncher udpHolePuncher, String string, boolean bl) {
        StunProbe.PublicMappedAddress publicMappedAddress = null;
        StunProbe.PublicMappedAddress publicMappedAddress2 = null;
        ArrayList<StunProbe.PublicMappedAddress> arrayList = null;
        if (udpHolePuncher != null) {
            Object object;
            try {
                List<String> list = StunDetector.getAllStunUrls();
                VoxLinkMod.LOGGER.info("[RoomManager] Host NAT: {} \u2014 8 concurrent STUN ({} servers)", (Object)(string != null ? string : "null"), (Object)list.size());
                object = StunProbe.discoverMappedAddressRace(udpHolePuncher.getSocket(), list, 2);
                publicMappedAddress = object[0];
                publicMappedAddress2 = object[1];
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[RoomManager] Punch socket STUN failed: {}", (Object)exception.getMessage());
            }
            if (bl && publicMappedAddress != null && publicMappedAddress2 != null) {
                int n = PunchProfile.HARDSYM.hardSymSocketCount;
                VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT, pre-create {} birthday sockets into holepunch_offer", (Object)n);
                arrayList = new ArrayList<StunProbe.PublicMappedAddress>();
                object = new ArrayList();
                int n2 = 0;
                while (n2 < n) {
                    int n3 = n2++;
                    ((ArrayList)object).add(CompletableFuture.supplyAsync(() -> {
                        UdpHolePuncher udpHolePuncher = new UdpHolePuncher();
                        this.applyPunchTemplate(udpHolePuncher);
                        try {
                            udpHolePuncher.createSocket();
                        }
                        catch (Exception exception) {
                            return null;
                        }
                        StunProbe.PublicMappedAddress[] publicMappedAddressArray = StunProbe.discoverMappedAddressRace(udpHolePuncher.getSocket(), StunDetector.getAllStunUrls(), 1);
                        StunProbe.PublicMappedAddress publicMappedAddress = publicMappedAddressArray[0];
                        if (publicMappedAddress != null) {
                            String string = "host_birthday_" + n3;
                            this.activeHolePunchers.put(string, udpHolePuncher);
                            return publicMappedAddress;
                        }
                        try {
                            udpHolePuncher.close();
                        }
                        catch (Exception exception) {
                            // empty catch block
                        }
                        return null;
                    }));
                }
                try {
                    CompletableFuture.allOf(((ArrayList)object).toArray(new CompletableFuture[0])).get(5L, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    VoxLinkMod.LOGGER.warn("[RoomManager] Birthday socket create partial timeout: {}", (Object)exception.getMessage());
                }
                Iterator iterator = ((ArrayList)object).iterator();
                while (iterator.hasNext()) {
                    CompletableFuture completableFuture = (CompletableFuture)iterator.next();
                    try {
                        StunProbe.PublicMappedAddress publicMappedAddress3 = completableFuture.getNow(null);
                        if (publicMappedAddress3 == null) continue;
                        arrayList.add(publicMappedAddress3);
                    }
                    catch (Exception exception) {}
                }
                VoxLinkMod.LOGGER.info("[RoomManager] Pre-create {} birthday sockets done, {} valid", (Object)n, (Object)arrayList.size());
            }
        }
        return new Object[]{publicMappedAddress, publicMappedAddress2, arrayList};
    }

    private static /* synthetic */ void lambda$getOrCreateUdpArray$1(AtomicInteger atomicInteger, AtomicInteger atomicInteger2, int n, ArrayList arrayList, CompletableFuture completableFuture, Object object, Object object2) {
        atomicInteger.incrementAndGet();
        if (object != null) {
            atomicInteger2.incrementAndGet();
        }
        if (atomicInteger2.get() >= n || atomicInteger.get() == arrayList.size()) {
            completableFuture.complete(null);
        }
    }

    private static /* synthetic */ Object[] lambda$getOrCreateUdpArray$0(UdpHolePuncher udpHolePuncher, List list) {
        try {
            StunProbe.PublicMappedAddress[] publicMappedAddressArray = StunProbe.discoverMappedAddressRace(udpHolePuncher.getSocket(), list, 1);
            StunProbe.PublicMappedAddress publicMappedAddress = publicMappedAddressArray != null && publicMappedAddressArray.length > 0 ? publicMappedAddressArray[0] : null;
            StunProbe.PublicMappedAddress publicMappedAddress2 = publicMappedAddress;
            if (publicMappedAddress != null) {
                return new Object[]{udpHolePuncher, publicMappedAddress};
            }
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
            return null;
        }
        catch (Exception exception) {
            try {
                udpHolePuncher.close();
            }
            catch (Exception exception2) {
                // empty catch block
            }
            return null;
        }
    }

    private static class UdpSocketArray {
        final List<UdpHolePuncher> punchers;
        final List<StunProbe.PublicMappedAddress> mappedAddrs;
        final long createTime;
        final boolean isEasySym;

        UdpSocketArray(List<UdpHolePuncher> list, List<StunProbe.PublicMappedAddress> list2, boolean bl) {
            this.punchers = new ArrayList<UdpHolePuncher>(list);
            this.mappedAddrs = new ArrayList<StunProbe.PublicMappedAddress>(list2);
            this.createTime = System.currentTimeMillis();
            this.isEasySym = bl;
        }

        boolean isReusable(int n, boolean bl, long l) {
            if (l - this.createTime >= 30000L) {
                return false;
            }
            if (this.isEasySym != bl) {
                return false;
            }
            if (this.punchers.size() < n) {
                return false;
            }
            for (UdpHolePuncher udpHolePuncher : this.punchers) {
                if (udpHolePuncher.getSocket() == null || udpHolePuncher.getSocket().isClosed()) {
                    return false;
                }
                if (!udpHolePuncher.isPunching()) continue;
                return false;
            }
            return true;
        }

        void close() {
            for (UdpHolePuncher udpHolePuncher : this.punchers) {
                try {
                    udpHolePuncher.close();
                }
                catch (Exception exception) {}
            }
            this.punchers.clear();
            this.mappedAddrs.clear();
        }
    }
}

