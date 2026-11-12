/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.command.CommandHandler
 *  net.minecraft.command.ICommandSender
 *  net.minecraft.entity.player.EntityPlayerMP
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.integrated.IntegratedServer
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.Style
 *  net.minecraft.util.text.TextComponentString
 *  net.minecraft.util.text.TextFormatting
 */
package icu.wuhui.voxlink.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.room.ConnectionManager;
import icu.wuhui.voxlink.room.ConnectionState;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.StunDetector;
import icu.wuhui.voxlink.ui.AttemptingJoinScreen;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

public class RoomManager {
    private final SignalingClient signalingClient;
    private final TopologyClient topologyClient;
    private final ScheduledExecutorService scheduler;
    private final ConnectionManager connectionManager;
    final AtomicReference<RoomState> currentRoom = new AtomicReference<Object>(null);
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> signalPollFuture;
    private final AtomicBoolean signalPollInFlight = new AtomicBoolean(false);
    private final AtomicInteger heartbeatFailCount = new AtomicInteger(0);
    private static final int MAX_HEARTBEAT_FAILS = 8;
    private static final long MIN_HEARTBEAT_INTERVAL = 5L;
    private static final int CREATE_ROOM_TIMEOUT_SECONDS = 120;
    private static final int JOIN_ROOM_TIMEOUT_SECONDS = 90;
    private static final int INITIAL_SIGNAL_POLL_MS = 200;
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final int MAX_SIGNAL_POLL_MS = 10000;
    private static final int JOINER_SIGNAL_POLL_MS = 250;
    private static final int SIGNAL_POLL_JITTER_MS = 100;
    private static final int NAT_UPDATE_DELAY_SEC = 2;
    private static final int HOST_ALONE_HEARTBEATS = 3;
    private int hostAloneCount = 0;
    private volatile long currentHeartbeatInterval;
    private volatile long currentSignalPollInterval;
    private final AtomicLong signalPollTimestamp = new AtomicLong(0L);
    private final AtomicInteger heartbeatSeq = new AtomicInteger(0);
    private final AtomicInteger heartbeatGeneration = new AtomicInteger(0);
    final AtomicBoolean roomLostHandled = new AtomicBoolean(false);
    volatile boolean intentionalLeave = false;
    private volatile CompletableFuture<?> pendingCreateFuture;
    private final AtomicInteger pollCount = new AtomicInteger(0);
    static final RoomState PENDING = new RoomState(null);
    private static final Set<String> TRANSIENT_ERRORS = new HashSet<String>(Arrays.asList("NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED"));
    private static final String GAME_VERSION = "1.12x";
    private volatile String lastModerationStatus = "";
    private volatile String lastModeratedName = "";
    volatile Runnable roomLostCallback;
    private volatile String roomLostReason = "";
    private volatile String guestOpPolicyRoomCode = "";

    public RoomManager(SignalingClient signalingClient, TopologyClient topologyClient) {
        this.signalingClient = signalingClient;
        this.topologyClient = topologyClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                try {
                    runnable.run();
                }
                catch (Throwable throwable) {
                    VoxLinkMod.LOGGER.error("Scheduled task threw uncaught exception", throwable);
                }
            }, "VoxLink-RoomManager");
            thread.setDaemon(true);
            return thread;
        });
        this.connectionManager = new ConnectionManager(this, signalingClient, this.scheduler);
    }

    public void shutdown() {
        this.connectionManager.setConnectionCycleActive(false);
        ConnectionHelper.resetConnecting();
        ConnectionState.reset();
        this.connectionManager.shutdown();
        this.stopScheduledTasks();
        this.scheduler.shutdownNow();
    }

    private void cancelPendingCreate() {
        CompletableFuture<?> completableFuture = this.pendingCreateFuture;
        if (completableFuture != null && !completableFuture.isDone()) {
            completableFuture.cancel(true);
            this.pendingCreateFuture = null;
        }
    }

    private static <T> CompletableFuture<T> delayedFail(long l) {
        CompletableFuture completableFuture = new CompletableFuture();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voxlink-rm-timeout");
            thread.setDaemon(true);
            return thread;
        });
        scheduledExecutorService.schedule(() -> {
            completableFuture.completeExceptionally(new TimeoutException("timed out after " + l + "s"));
            scheduledExecutorService.shutdown();
        }, l, TimeUnit.SECONDS);
        return completableFuture;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture completableFuture = new CompletableFuture();
        completableFuture.completeExceptionally(throwable);
        return completableFuture;
    }

    public CompletableFuture<RoomInfo> createRoom(String string, String string2, int n, int n2, boolean bl, String string3, String string4) {
        if (!this.currentRoom.compareAndSet(null, PENDING)) {
            return RoomManager.failedFuture(new IllegalStateException(I18n.func_135052_a((String)"voxlink.error.already_in_room_or_pending", (Object[])new Object[0])));
        }
        int n3 = PeerServer.getPort();
        VoxLinkMod.LOGGER.info("[createRoom] Instant create (NAT probe deferred)");
        CompletableFuture<NatResult> completableFuture = CompletableFuture.completedFuture(new NatResult("unknown", n2, -1));
        CompletableFuture.runAsync(() -> {
            try {
                Object object;
                VoxLinkMod.LOGGER.info("[createRoom] Background NAT probe started");
                String string = "unknown";
                int n2 = n2;
                int n3 = -1;
                if (VoxLinkMod.getConfig().isAutoUPnP()) {
                    // empty if block
                }
                if ("unknown".equals(string) || "strict".equals(string)) {
                    try {
                        object = StunProbe.probeAsync(StunDetector.getStunServerGroups()).join();
                        if (object != null && ((StunProbe.ProbeResult)object).natType != null) {
                            string = ((StunProbe.ProbeResult)object).natType.key;
                            this.connectionManager.setStunProbeResult((StunProbe.ProbeResult)object);
                            VoxLinkMod.LOGGER.info("[createRoom] STUN probe result saved: NAT={}, reachable={}", (Object)string, (Object)((StunProbe.ProbeResult)object).reachableStunUrls.size());
                        }
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.warn("[createRoom] probeAsync failed: {}", (Object)exception.getMessage());
                    }
                }
                VoxLinkMod.LOGGER.info("[createRoom] Background NAT probe done: natType={}, port={}", (Object)string, (Object)n2);
                object = this.currentRoom.get();
                if (object != null && object != PENDING && ((RoomState)object).roomInfo != null) {
                    ((RoomState)object).roomInfo.setNatType(string);
                    try {
                        this.signalingClient.updateRoom(((RoomState)object).roomInfo.getCode(), ((RoomState)object).roomInfo.getToken(), ((RoomState)object).roomInfo.getName(), null, ((RoomState)object).roomInfo.getMaxPlayers(), ((RoomState)object).roomInfo.isVisible(), ((RoomState)object).roomInfo.getAuthType(), ((RoomState)object).roomInfo.getCategory());
                        VoxLinkMod.LOGGER.info("[createRoom] NAT type updated: {}", (Object)string);
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.warn("[createRoom] NAT type update failed: {}", (Object)exception.getMessage());
                    }
                } else {
                    VoxLinkMod.LOGGER.info("[createRoom] Room not ready, retry NAT update in 2s");
                    String string2 = string;
                    this.scheduler.schedule(() -> {
                        RoomState roomState = this.currentRoom.get();
                        if (roomState != null && roomState != PENDING && roomState.roomInfo != null) {
                            roomState.roomInfo.setNatType(string2);
                            VoxLinkMod.LOGGER.info("[createRoom] Delayed NAT type update: {}", (Object)string2);
                            try {
                                this.signalingClient.updateRoom(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), roomState.roomInfo.getName(), null, roomState.roomInfo.getMaxPlayers(), roomState.roomInfo.isVisible(), roomState.roomInfo.getAuthType(), roomState.roomInfo.getCategory());
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.warn("[createRoom] Delayed NAT type update failed: {}", (Object)exception.getMessage());
                            }
                        }
                    }, 2L, TimeUnit.SECONDS);
                }
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[createRoom] Background NAT probe failed: {}", (Object)exception.getMessage());
            }
        });
        CompletionStage completionStage = this.signalingClient.getPublicIp().exceptionally(throwable -> {
            VoxLinkMod.LOGGER.warn("[createRoom] Public IP fetch failed: {}, continue without IP", (Object)throwable.getMessage());
            return new SignalingClient.ApiResponse(false, null, null, null);
        });
        int n4 = 340;
        CompletionStage completionStage2 = ((CompletableFuture)((CompletableFuture)((CompletableFuture)((CompletableFuture)completableFuture.thenCombine(completionStage, (natResult, apiResponse) -> {
            String string = null;
            String string2 = null;
            if (apiResponse.success && apiResponse.data != null) {
                if (apiResponse.data.has("ip") && !apiResponse.data.get("ip").isJsonNull()) {
                    string = apiResponse.data.get("ip").getAsString();
                }
                if (apiResponse.data.has("ipv6") && !apiResponse.data.get("ipv6").isJsonNull()) {
                    string2 = apiResponse.data.get("ipv6").getAsString();
                }
            }
            VoxLinkMod.LOGGER.info("[createRoom] NAT+IP ready: nat={}, ip={}, ipv6={}", new Object[]{natResult.nat, string, string2});
            if ((string2 == null || string2.isEmpty()) && StunDetector.verifyIPv6Connectivity() && (string2 = ConnectionFallback.getLocalGlobalIpv6()) != null) {
                VoxLinkMod.LOGGER.info("[createRoom] Server returned no IPv6, using local global IPv6: {} (reachability unverified)", (Object)string2);
            }
            return new CreateRoomResult((NatResult)natResult, null, string, string2);
        })).thenCompose(createRoomResult -> {
            String string5 = createRoomResult.hostIp;
            String string6 = createRoomResult.hostIpv6;
            NatResult natResult = createRoomResult.natResult;
            VoxLinkMod.LOGGER.info("[createRoom] Step 2: call API to create room");
            return this.signalingClient.createRoom(string, string2 != null && !string2.isEmpty() ? string2 : null, n, natResult.port, natResult.nat, natResult.geyserPort, bl, string3, string4, n4, n3, string6, GAME_VERSION).thenApply(apiResponse -> {
                VoxLinkMod.LOGGER.info("[createRoom] Step 2 done: success={}", (Object)apiResponse.success);
                return new CreateRoomResult(natResult, (SignalingClient.ApiResponse)apiResponse, string5, string6);
            });
        })).thenApply(createRoomResult -> {
            String string4;
            NatResult natResult = createRoomResult.natResult;
            SignalingClient.ApiResponse apiResponse2 = createRoomResult.apiResponse;
            if (!apiResponse2.success) {
                String string5;
                String string6 = null;
                if (TRANSIENT_ERRORS.contains(apiResponse2.error)) {
                    this.currentRoom.compareAndSet(PENDING, null);
                    throw new TransientException(apiResponse2.error + ": " + apiResponse2.message);
                }
                this.currentRoom.compareAndSet(PENDING, null);
                String string7 = apiResponse2.error != null ? apiResponse2.error : (string5 = (string6 = apiResponse2.message != null ? apiResponse2.message : I18n.func_135052_a((String)"voxlink.error.unknown", (Object[])new Object[0])));
                if (apiResponse2.message != null && !apiResponse2.message.equals(apiResponse2.error)) {
                    string6 = apiResponse2.error + ": " + apiResponse2.message;
                }
                if ("QUEUED".equals(apiResponse2.error) && apiResponse2.queuePosition > 0) {
                    string6 = "QUEUED:" + apiResponse2.queuePosition;
                }
                throw new RuntimeException(string6);
            }
            if (apiResponse2.data == null) {
                this.currentRoom.compareAndSet(PENDING, null);
                throw new RuntimeException(I18n.func_135052_a((String)"voxlink.error.server_response_abnormal", (Object[])new Object[0]));
            }
            String string8 = apiResponse2.data.has("code") ? apiResponse2.data.get("code").getAsString() : "";
            String string9 = apiResponse2.data.has("hostToken") ? apiResponse2.data.get("hostToken").getAsString() : "";
            String string10 = string9;
            if (apiResponse2.data.has("expiresIn") && !apiResponse2.data.get("expiresIn").isJsonNull()) {
                long l = apiResponse2.data.get("expiresIn").getAsLong();
                VoxLinkMod.LOGGER.info("Room created, expires in {}s", (Object)l);
            }
            RoomInfo roomInfo = new RoomInfo(string8, string, string2 != null && !string2.isEmpty(), n, string9, true, natResult.port, natResult.nat);
            roomInfo.setHostIp(createRoomResult.hostIp);
            roomInfo.setHostIpv6(createRoomResult.hostIpv6);
            roomInfo.setBedrockPort(natResult.geyserPort > 0 ? natResult.geyserPort : -1);
            roomInfo.setCategory(string4);
            roomInfo.setVisible(bl);
            RoomState roomState = new RoomState(roomInfo);
            if (!this.currentRoom.compareAndSet(PENDING, roomState)) {
                VoxLinkMod.LOGGER.warn("[createRoom] State cleared (timeout?), discard late result");
                return null;
            }
            if (string8 != null && !string8.isEmpty()) {
                LogUploadManager.arm(string8, true);
            }
            if (apiResponse2.data.has("nameApproved") && !apiResponse2.data.get("nameApproved").isJsonNull() && !apiResponse2.data.get("nameApproved").getAsBoolean()) {
                roomInfo.setNameApproved(false);
            }
            this.intentionalLeave = false;
            this.roomLostHandled.set(false);
            this.heartbeatFailCount.set(0);
            this.startHeartbeat();
            this.startSignalPoll();
            String string11 = "host_" + string8;
            this.topologyClient.onRoomJoined(string8, string9, true, string11, 0);
            try {
                int n2 = P2PBridge.startHostBridge(natResult.port).get(5L, TimeUnit.SECONDS);
                if (n2 > 0) {
                    VoxLinkMod.LOGGER.info("Host bridge started port={}, MC port: {}", (Object)n2, (Object)natResult.port);
                } else {
                    VoxLinkMod.LOGGER.warn("Host bridge start failed, client needs direct MC port {}", (Object)natResult.port);
                }
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("Host bridge start exception: {}", (Object)exception.getMessage());
            }
            AtomicReference<Object> atomicReference = new AtomicReference<Object>(null);
            AtomicReference<Object> atomicReference2 = new AtomicReference<Object>(null);
            int n3 = 0;
            if (createRoomResult.hostIp != null && !createRoomResult.hostIp.isEmpty()) {
                ++n3;
            }
            if (createRoomResult.hostIpv6 != null && !createRoomResult.hostIpv6.isEmpty()) {
                ++n3;
            }
            int n4 = n3;
            AtomicInteger atomicInteger = new AtomicInteger(0);
            if (createRoomResult.hostIp != null && !createRoomResult.hostIp.isEmpty()) {
                roomInfo.setIpv4Status(RoomInfo.PortStatus.UNKNOWN);
                string4 = createRoomResult.hostIp;
                this.signalingClient.checkPortReachable(createRoomResult.hostIp, natResult.port).thenAccept(apiResponse -> {
                    boolean bl = apiResponse.success && apiResponse.data != null && apiResponse.data.has("reachable") && apiResponse.data.get("reachable").getAsBoolean();
                    roomInfo.setIpv4Status(bl ? RoomInfo.PortStatus.REACHABLE : RoomInfo.PortStatus.UNREACHABLE);
                    VoxLinkMod.LOGGER.info("IPv4 port check: {}:{} = {}", new Object[]{string4, natResult.port, bl});
                    atomicReference.set(bl);
                    if (atomicInteger.incrementAndGet() == n4) {
                        this.warnPortBlockedCombined((Boolean)atomicReference.get(), (Boolean)atomicReference2.get(), string4, createRoomResult.hostIpv6);
                    }
                });
            } else {
                roomInfo.setIpv4Status(RoomInfo.PortStatus.NO_ADDRESS);
            }
            if (createRoomResult.hostIpv6 != null && !createRoomResult.hostIpv6.isEmpty()) {
                roomInfo.setIpv6Status(RoomInfo.PortStatus.UNKNOWN);
                string4 = createRoomResult.hostIpv6;
                this.signalingClient.checkPortReachable(createRoomResult.hostIpv6, natResult.port).thenAccept(apiResponse -> {
                    boolean bl = apiResponse.success && apiResponse.data != null && apiResponse.data.has("reachable") && apiResponse.data.get("reachable").getAsBoolean();
                    roomInfo.setIpv6Status(bl ? RoomInfo.PortStatus.REACHABLE : RoomInfo.PortStatus.UNREACHABLE);
                    VoxLinkMod.LOGGER.info("IPv6 port check: [{}]:{} = {}", new Object[]{string4, natResult.port, bl});
                    atomicReference2.set(bl);
                    if (atomicInteger.incrementAndGet() == n4) {
                        this.warnPortBlockedCombined((Boolean)atomicReference.get(), (Boolean)atomicReference2.get(), createRoomResult.hostIp, string4);
                    }
                });
            } else {
                roomInfo.setIpv6Status(RoomInfo.PortStatus.NO_ADDRESS);
            }
            return roomInfo;
        })).applyToEither((CompletionStage)RoomManager.delayedFail(120L), Function.identity())).exceptionally(throwable -> {
            this.cleanupCreateRoomResources(n2);
            VoxLinkMod.LOGGER.error("[createRoom] failed: {}", (Object)throwable.getMessage());
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException)throwable;
            }
            throw new RuntimeException((Throwable)throwable);
        });
        this.pendingCreateFuture = (CompletableFuture)completionStage2;
        return (CompletableFuture)completionStage2;
    }

    private void cleanupCreateRoomResources(int n) {
        RoomState roomState = this.currentRoom.get();
        boolean bl = roomState == PENDING;
        boolean bl2 = bl;
        if (bl) {
            this.currentRoom.compareAndSet(PENDING, null);
        } else if (roomState != null) {
            this.currentRoom.compareAndSet(roomState, null);
        }
        this.stopScheduledTasks();
        P2PBridge.disconnect();
    }

    public CompletableFuture<RoomInfo> updateRoom(String string, String string2, String string3, String string4, int n, boolean bl, String string5, String string6) {
        RoomState roomState = this.currentRoom.get();
        return roomState != null && roomState != PENDING && roomState.roomInfo != null ? ((CompletableFuture)this.signalingClient.updateRoom(string, string2, string3, string4, n, bl, string5, string6).thenApply(apiResponse -> {
            if (!apiResponse.success) {
                String string5;
                String string6 = null;
                String string7 = apiResponse.error != null ? apiResponse.error : (string5 = (string6 = apiResponse.message != null ? apiResponse.message : I18n.func_135052_a((String)"voxlink.error.unknown", (Object[])new Object[0])));
                if (apiResponse.message != null && !apiResponse.message.equals(apiResponse.error)) {
                    string6 = apiResponse.error + ": " + apiResponse.message;
                }
                throw new RuntimeException(string6);
            }
            RoomInfo roomInfo = roomState.roomInfo;
            if (string3 != null && !string3.isEmpty()) {
                roomInfo.setName(string3);
            }
            if (string4 != null) {
                roomInfo.setPassword(string4);
            }
            roomInfo.setMaxPlayers(n);
            roomInfo.setVisible(bl);
            if (string5 != null) {
                roomInfo.setAuthType(string5);
            }
            if (string6 != null) {
                roomInfo.setCategory(string6);
            }
            if (apiResponse.data != null && apiResponse.data.has("nameApproved") && !apiResponse.data.get("nameApproved").isJsonNull() && !apiResponse.data.get("nameApproved").getAsBoolean()) {
                roomInfo.setNameApproved(false);
            } else {
                roomInfo.setNameApproved(true);
            }
            return roomInfo;
        })).exceptionally(object -> {
            if (object instanceof RuntimeException) {
                throw (RuntimeException)object;
            }
            throw new RuntimeException((Throwable)object);
        }) : RoomManager.failedFuture(new IllegalStateException(I18n.func_135052_a((String)"voxlink.error.not_in_room", (Object[])new Object[0])));
    }

    public CompletableFuture<RoomInfo> joinRoom(String string, String string2) {
        if (!this.currentRoom.compareAndSet(null, PENDING)) {
            return RoomManager.failedFuture(new IllegalStateException(I18n.func_135052_a((String)"voxlink.error.already_in_room_or_pending", (Object[])new Object[0])));
        }
        if (string != null && !string.trim().isEmpty()) {
            StunProbe.ProbeResult probeResult2;
            String string3 = string.toUpperCase();
            if (!string3.matches("^[A-HJ-NP-Z2-9]{6}$")) {
                this.currentRoom.compareAndSet(PENDING, null);
                return RoomManager.failedFuture(new IllegalArgumentException(I18n.func_135052_a((String)"voxlink.error.invalid_room_code", (Object[])new Object[0])));
            }
            this.connectionManager.setStunProbeResult(null);
            if (StunProbe.isNetworkChanged()) {
                VoxLinkMod.LOGGER.info("[joinRoom] Network changed, invalidate STUN cache");
                StunProbe.invalidateCache();
            }
            if ((probeResult2 = StunProbe.getCachedResult()) != null) {
                this.connectionManager.setStunProbeResult(probeResult2);
                this.connectionManager.getStunProbeFutureRef().set(null);
                VoxLinkMod.LOGGER.info("[joinRoom] Using cached STUN probe: NAT={}, reachable={}", (Object)probeResult2.natType.key, (Object)probeResult2.reachableStunUrls.size());
            } else {
                CompletableFuture<StunProbe.ProbeResult> completableFuture = StunProbe.probeAsync(StunDetector.getStunServerGroups());
                this.connectionManager.getStunProbeFutureRef().set(completableFuture);
                ((CompletableFuture)completableFuture.thenAccept(probeResult -> {
                    this.connectionManager.setStunProbeResult((StunProbe.ProbeResult)probeResult);
                    VoxLinkMod.LOGGER.info("[joinRoom] STUN probe done: NAT={}, reachable={}", (Object)probeResult.natType.key, (Object)probeResult.reachableStunUrls.size());
                })).exceptionally(object -> {
                    VoxLinkMod.LOGGER.warn("[joinRoom] STUN probe failed: {}", (Object)((Throwable)object).getMessage());
                    return null;
                });
            }
            return ((CompletableFuture)((CompletableFuture)this.signalingClient.joinRoom(string3, string2).thenApply(apiResponse -> {
                if (!apiResponse.success) {
                    String string2;
                    String string3 = null;
                    if (TRANSIENT_ERRORS.contains(apiResponse.error)) {
                        this.currentRoom.compareAndSet(PENDING, null);
                        throw new TransientException(apiResponse.error + ": " + apiResponse.message);
                    }
                    this.currentRoom.compareAndSet(PENDING, null);
                    String string4 = apiResponse.error != null ? apiResponse.error : (string2 = (string3 = apiResponse.message != null ? apiResponse.message : I18n.func_135052_a((String)"voxlink.error.unknown", (Object[])new Object[0])));
                    if (apiResponse.message != null && !apiResponse.message.equals(apiResponse.error)) {
                        string3 = apiResponse.error + ": " + apiResponse.message;
                    }
                    throw new RuntimeException(string3);
                }
                if (apiResponse.data == null) {
                    this.currentRoom.compareAndSet(PENDING, null);
                    throw new RuntimeException(I18n.func_135052_a((String)"voxlink.error.server_response_abnormal", (Object[])new Object[0]));
                }
                String string5 = apiResponse.data.has("clientToken") ? apiResponse.data.get("clientToken").getAsString() : "";
                String string6 = apiResponse.data.has("clientId") ? apiResponse.data.get("clientId").getAsString() : "";
                JsonObject jsonObject = apiResponse.data.has("room") && apiResponse.data.get("room").isJsonObject() ? apiResponse.data.getAsJsonObject("room") : new JsonObject();
                RoomInfo roomInfo = new RoomInfo(jsonObject.has("code") ? jsonObject.get("code").getAsString() : "", jsonObject.has("name") ? jsonObject.get("name").getAsString() : "VoxLink", jsonObject.has("hasPassword") && jsonObject.get("hasPassword").getAsBoolean(), jsonObject.has("maxPlayers") ? jsonObject.get("maxPlayers").getAsInt() : 20, string5, false, jsonObject.has("hostPort") ? jsonObject.get("hostPort").getAsInt() : 25565, jsonObject.has("natType") ? jsonObject.get("natType").getAsString() : "unknown");
                roomInfo.setClientId(string6);
                if (jsonObject.has("bedrockPort") && !jsonObject.get("bedrockPort").isJsonNull()) {
                    roomInfo.setBedrockPort(jsonObject.get("bedrockPort").getAsInt());
                }
                if (jsonObject.has("protocolVersion") && !jsonObject.get("protocolVersion").isJsonNull()) {
                    roomInfo.setServerProtocolVersion(jsonObject.get("protocolVersion").getAsInt());
                }
                if (jsonObject.has("currentPlayers") && !jsonObject.get("currentPlayers").isJsonNull()) {
                    roomInfo.setCurrentPlayers(jsonObject.get("currentPlayers").getAsInt());
                }
                if (jsonObject.has("category") && !jsonObject.get("category").isJsonNull()) {
                    roomInfo.setCategory(jsonObject.get("category").getAsString());
                }
                if (jsonObject.has("loader") && !jsonObject.get("loader").isJsonNull()) {
                    roomInfo.setLoader(jsonObject.get("loader").getAsString());
                }
                if (jsonObject.has("authType") && !jsonObject.get("authType").isJsonNull()) {
                    roomInfo.setAuthType(jsonObject.get("authType").getAsString());
                }
                if (jsonObject.has("peerPort") && !jsonObject.get("peerPort").isJsonNull()) {
                    roomInfo.setPeerPort(jsonObject.get("peerPort").getAsInt());
                }
                int n = jsonObject.has("hostProtocolVersion") && !jsonObject.get("hostProtocolVersion").isJsonNull() ? jsonObject.get("hostProtocolVersion").getAsInt() : 0;
                Set<String> set = Collections.emptySet();
                if (jsonObject.has("hostCapabilities") && jsonObject.get("hostCapabilities").isJsonArray()) {
                    set = new HashSet<String>();
                    for (JsonElement jsonElement : jsonObject.getAsJsonArray("hostCapabilities")) {
                        if (jsonElement.isJsonNull()) continue;
                        set.add(jsonElement.getAsString());
                    }
                }
                roomInfo.setHostCapabilities(n, set);
                if (n > 0) {
                    VoxLinkMod.LOGGER.info("[joinRoom] Host capability: v{} caps={}", (Object)n, set);
                } else {
                    VoxLinkMod.LOGGER.info("[joinRoom] Host is legacy version, using direct connect mode");
                }
                RoomState roomState = new RoomState(roomInfo);
                if (!this.currentRoom.compareAndSet(PENDING, roomState)) {
                    VoxLinkMod.LOGGER.warn("[joinRoom] State already cleared (timeout?), discarding late success");
                    return null;
                }
                this.intentionalLeave = false;
                this.roomLostHandled.set(false);
                this.heartbeatFailCount.set(0);
                this.startHeartbeat();
                this.startSignalPoll();
                this.topologyClient.onRoomJoined(string3, string5, false, string6, 0);
                return roomInfo;
            })).applyToEither((CompletionStage)RoomManager.delayedFail(90L), Function.identity())).exceptionally(object -> {
                if (this.currentRoom.compareAndSet(PENDING, null)) {
                    this.stopScheduledTasks();
                }
                if (object instanceof RuntimeException) {
                    throw (RuntimeException)object;
                }
                throw new RuntimeException((Throwable)object);
            });
        }
        this.currentRoom.compareAndSet(PENDING, null);
        return RoomManager.failedFuture(new IllegalArgumentException(I18n.func_135052_a((String)"voxlink.error.room_not_found", (Object[])new Object[0])));
    }

    public void leaveRoom() {
        this.leaveRoom("\u7528\u6237\u4e3b\u52a8\u79bb\u5f00");
    }

    public void leaveRoom(String string) {
        if (this.connectionManager.isConnectionInHandoff() && "\u7528\u6237\u4e3b\u52a8\u79bb\u5f00".equals(string)) {
            return;
        }
        this.intentionalLeave = true;
        this.stopScheduledTasks();
        this.roomLostHandled.set(true);
        this.connectionManager.setConnectionCycleActive(false);
        this.connectionManager.setReversePunchAttempted(false);
        ConnectionHelper.resetConnecting();
        ConnectionHelper.clearConnectInitiated();
        ConnectionState.transitionTo(ConnectionState.DISCONNECTED, string);
        this.connectionManager.setStunProbeResult(null);
        this.connectionManager.getStunProbeFutureRef().set(null);
        this.connectionManager.killDualRace();
        this.connectionManager.resetDualRaceState();
        this.connectionManager.resetContinuousRetryState();
        this.connectionManager.resetIceRestartState();
        RoomState roomState = this.currentRoom.getAndSet(null);
        if (roomState != null && roomState != PENDING) {
            this.cleanupRoomResources();
            try {
                this.performLeave(roomState);
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("Leave room error: {}", (Object)exception.getMessage());
            }
        } else {
            this.cancelPendingCreate();
        }
    }

    private void cleanupRoomResources() {
        try {
            this.connectionManager.clearActiveHolePunchers();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("cleanup punchers error: {}", (Object)exception.getMessage());
        }
        try {
            this.connectionManager.clearActiveUdpTransports();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("cleanup udp transports error: {}", (Object)exception.getMessage());
        }
        try {
            P2PBridge.disconnect();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("cleanup P2PBridge disconnect error: {}", (Object)exception.getMessage());
        }
        try {
            this.topologyClient.onRoomLeft();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("cleanup topology error: {}", (Object)exception.getMessage());
        }
    }

    public void leaveRoomSync() {
        this.intentionalLeave = true;
        this.stopScheduledTasks();
        this.roomLostHandled.set(true);
        this.connectionManager.setConnectionCycleActive(false);
        this.connectionManager.setReversePunchAttempted(false);
        ConnectionHelper.resetConnecting();
        this.connectionManager.setStunProbeResult(null);
        this.connectionManager.getStunProbeFutureRef().set(null);
        this.connectionManager.killDualRace();
        this.connectionManager.resetDualRaceState();
        this.connectionManager.resetContinuousRetryState();
        this.connectionManager.resetIceRestartState();
        RoomState roomState = this.currentRoom.getAndSet(null);
        if (roomState != null && roomState != PENDING) {
            this.cleanupRoomResources();
            try {
                this.performLeave(roomState);
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("Sync leave error: {}", (Object)exception.getMessage());
            }
        } else {
            this.cancelPendingCreate();
        }
    }

    private void performLeave(RoomState roomState) {
        CompletionStage completionStage = roomState.roomInfo.isHost() ? ((CompletableFuture)this.signalingClient.leaveRoom(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true).thenAccept(apiResponse -> {
            if (!apiResponse.success) {
                VoxLinkMod.LOGGER.warn("Server leave room failed: {}", (Object)apiResponse.error);
            }
        })).exceptionally(object -> {
            VoxLinkMod.LOGGER.warn("Server leave room failed: {}", (Object)((Throwable)object).getMessage());
            return null;
        }) : ((CompletableFuture)this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host").exceptionally(throwable -> {
            VoxLinkMod.LOGGER.warn("Send disconnect signal failed: {}", (Object)throwable.getMessage());
            return null;
        })).thenCompose(object2 -> ((CompletableFuture)this.signalingClient.leaveRoom(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false).thenAccept(apiResponse -> {
            if (!apiResponse.success) {
                VoxLinkMod.LOGGER.warn("Server leave room failed: {}", (Object)apiResponse.error);
            }
        })).exceptionally(object -> {
            VoxLinkMod.LOGGER.warn("Server leave room failed: {}", (Object)((Throwable)object).getMessage());
            return null;
        }));
        ((CompletableFuture)completionStage).whenComplete((object, object2) -> {
            P2PBridge.disconnect();
            this.topologyClient.onRoomLeft();
        });
    }

    public void closeRoom() {
        this.leaveRoom();
    }

    public void showRoomInfo() {
        RoomState roomState = this.currentRoom.get();
        if (roomState != null && roomState != PENDING) {
            RoomInfo roomInfo = roomState.roomInfo;
            VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.room_info_detail", (Object[])new Object[]{roomInfo.getName(), roomInfo.getCode(), roomInfo.getCurrentPlayers(), roomInfo.getMaxPlayers(), roomInfo.getNatType(), roomInfo.isHost() ? I18n.func_135052_a((String)"voxlink.yes", (Object[])new Object[0]) : I18n.func_135052_a((String)"voxlink.no", (Object[])new Object[0])})));
        } else {
            VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.error.not_in_room", (Object[])new Object[0]));
        }
    }

    public RoomInfo getCurrentRoom() {
        RoomState roomState = this.currentRoom.get();
        return roomState != null && roomState != PENDING ? roomState.roomInfo : null;
    }

    public boolean isInRoom() {
        RoomState roomState = this.currentRoom.get();
        return roomState != null && roomState != PENDING;
    }

    private synchronized void stopScheduledTasks() {
        if (this.heartbeatFuture != null) {
            this.heartbeatFuture.cancel(false);
            this.heartbeatFuture = null;
        }
        if (this.signalPollFuture != null) {
            this.signalPollFuture.cancel(false);
            this.signalPollFuture = null;
        }
        this.signalingClient.setSignalPushHandler(null);
        this.connectionManager.stopAllConnectionWork();
    }

    private synchronized void handleNameModerationUpdate(RoomState roomState, String string, String string2, String string3, boolean bl) {
        if (!(string == null || string.isEmpty() || string.equals(this.lastModerationStatus) && string3 != null && string3.equals(this.lastModeratedName) || string.equals(this.lastModerationStatus) && !"approved".equals(string))) {
            Minecraft minecraft;
            this.lastModerationStatus = string;
            if (string3 != null) {
                this.lastModeratedName = string3;
            }
            if ((minecraft = Minecraft.func_71410_x()) != null && minecraft.field_71439_g != null) {
                roomState.roomInfo.setNameApproved(bl);
                if (bl && string3 != null && !string3.isEmpty() && !"name_pending_review".equals(string3)) {
                    roomState.roomInfo.setName(string3);
                }
                minecraft.func_152344_a(() -> {
                    if (minecraft.field_71439_g != null) {
                        switch (string) {
                            case "approved": {
                                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.name_approved", (Object[])new Object[0])));
                                if (string3 == null || string3.isEmpty()) break;
                                VoxLinkChat.print(new TextComponentString("  " + string3).func_150255_a(new Style().func_150238_a(TextFormatting.GRAY)));
                                break;
                            }
                            case "rejected": {
                                String string4 = string2 != null && !string2.isEmpty() ? string2 : I18n.func_135052_a((String)"voxlink.chat.unknown_reason", (Object[])new Object[0]);
                                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.name_rejected_with_hint", (Object[])new Object[0])));
                                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.reason_label", (Object[])new Object[]{string4})));
                                break;
                            }
                            case "unavailable": {
                                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.name_unavailable", (Object[])new Object[0])));
                                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.please_retry", (Object[])new Object[0])));
                            }
                        }
                    }
                });
            }
        }
    }

    public void setRoomLostCallback(Runnable runnable) {
        this.roomLostCallback = runnable;
    }

    private void handleRoomLost() {
        this.handleRoomLost("HEARTBEAT_FAILED");
    }

    void handleRoomLost(String string) {
        block15: {
            if (this.roomLostHandled.compareAndSet(false, true)) {
                this.connectionManager.setConnectionCycleActive(false);
                ConnectionHelper.resetConnecting();
                this.roomLostReason = string;
                this.stopScheduledTasks();
                ConnectionState.reset();
                RoomState roomState = this.currentRoom.get();
                try {
                    this.scheduler.execute(() -> {
                        RoomState roomState2 = this.currentRoom.get();
                        if (roomState2 != null && roomState2 != PENDING && roomState2 == roomState) {
                            if (!roomState2.roomInfo.isHost()) {
                                try {
                                    this.signalingClient.sendSignal(roomState2.roomInfo.getCode(), roomState2.roomInfo.getToken(), roomState2.roomInfo.isHost(), "disconnect", new JsonObject(), "host");
                                }
                                catch (Exception exception) {
                                    VoxLinkMod.LOGGER.debug("Send disconnect signal failed on room lost: {}", (Object)exception.getMessage());
                                }
                                try {
                                    this.signalingClient.leaveRoom(roomState2.roomInfo.getCode(), roomState2.roomInfo.getToken(), false);
                                }
                                catch (Exception exception) {
                                    VoxLinkMod.LOGGER.debug("Leave failed on room lost: {}", (Object)exception.getMessage());
                                }
                            }
                            if (roomState2.roomInfo.isHost()) {
                                try {
                                    if (this.intentionalLeave) {
                                        this.signalingClient.leaveRoom(roomState2.roomInfo.getCode(), roomState2.roomInfo.getToken(), true);
                                    }
                                }
                                catch (Exception exception) {
                                    VoxLinkMod.LOGGER.debug("Leave failed on room lost (host): {}", (Object)exception.getMessage());
                                }
                            }
                            if (!roomState2.roomInfo.isHost()) {
                                P2PBridge.disconnect();
                            }
                            this.topologyClient.onRoomLeft();
                            this.currentRoom.compareAndSet(roomState, null);
                            this.heartbeatFailCount.set(0);
                            boolean bl = this.intentionalLeave;
                            if (this.roomLostCallback != null && !bl) {
                                this.roomLostCallback.run();
                            }
                            this.intentionalLeave = false;
                        }
                    });
                }
                catch (RejectedExecutionException rejectedExecutionException) {
                    VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute room lost cleanup");
                    this.currentRoom.compareAndSet(roomState, null);
                    if (roomState != null && roomState != PENDING) {
                        if (!roomState.roomInfo.isHost()) {
                            try {
                                this.signalingClient.sendSignal(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.debug("Send disconnect signal failed on room lost (sync fallback): {}", (Object)exception.getMessage());
                            }
                            try {
                                this.signalingClient.leaveRoom(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), false);
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.debug("Leave failed on room lost (sync fallback): {}", (Object)exception.getMessage());
                            }
                        }
                        if (roomState.roomInfo.isHost()) {
                            try {
                                if (this.intentionalLeave) {
                                    this.signalingClient.leaveRoom(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), true);
                                }
                            }
                            catch (Exception exception) {
                                VoxLinkMod.LOGGER.debug("Leave failed on room lost (sync fallback): {}", (Object)exception.getMessage());
                            }
                        }
                    }
                    P2PBridge.disconnect();
                    this.topologyClient.onRoomLeft();
                    if (this.intentionalLeave) break block15;
                    this.notifyRoomLostActionBar(string);
                    if (this.roomLostCallback == null) break block15;
                    try {
                        this.roomLostCallback.run();
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.debug("roomLostCallback exception (sync fallback): {}", (Object)exception.getMessage());
                    }
                }
            }
        }
    }

    private void notifyRoomLostActionBar(String string) {
        try {
            AttemptingJoinScreen attemptingJoinScreen;
            Minecraft minecraft = Minecraft.func_71410_x();
            if (minecraft == null || minecraft.field_71439_g == null) {
                return;
            }
            GuiScreen guiScreen = minecraft.field_71462_r;
            if (guiScreen instanceof AttemptingJoinScreen) {
                attemptingJoinScreen = (AttemptingJoinScreen)guiScreen;
                attemptingJoinScreen.onRoomLost();
            }
            attemptingJoinScreen = "HOST_CLOSED".equals(string) || "ROOM_CLOSED".equals(string) ? new TextComponentString(I18n.func_135052_a((String)"voxlink.room_lost.host_closed", (Object[])new Object[0])) : ("HOST_DISCONNECTED".equals(string) ? new TextComponentString(I18n.func_135052_a((String)"voxlink.room_lost.host_disconnected", (Object[])new Object[0])) : ("ROOM_NOT_FOUND".equals(string) ? new TextComponentString(I18n.func_135052_a((String)"voxlink.room_lost.host_gone", (Object[])new Object[0])) : (!"TOKEN_INVALID".equals(string) && !"INVALID_TOKEN".equals(string) ? new TextComponentString(I18n.func_135052_a((String)"voxlink.room_lost.default", (Object[])new Object[0])) : new TextComponentString(I18n.func_135052_a((String)"voxlink.room_closed", (Object[])new Object[0])))));
            VoxLinkChat.print(new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.error_prefix", (Object[])new Object[0])).func_150257_a((ITextComponent)attemptingJoinScreen));
            VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.room_lost.hint", (Object[])new Object[0])));
        }
        catch (Exception | NoClassDefFoundError throwable) {
            VoxLinkMod.LOGGER.debug("Show chat message failed: {}", (Object)throwable.getMessage());
        }
    }

    public String getRoomLostReason() {
        return this.roomLostReason;
    }

    private synchronized void startHeartbeat() {
        long l;
        ScheduledFuture<?> scheduledFuture = this.heartbeatFuture;
        this.heartbeatFuture = null;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        this.heartbeatGeneration.incrementAndGet();
        this.heartbeatFailCount.set(0);
        this.heartbeatSeq.set(0);
        this.currentHeartbeatInterval = l = Math.max((long)VoxLinkMod.getConfig().getHeartbeatInterval(), 5L);
        this.heartbeatFuture = this.scheduler.scheduleAtFixedRate(this::heartbeatTask, l, l, TimeUnit.SECONDS);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void rescheduleHeartbeat(long l) {
        if (this.currentRoom.get() != null) {
            RoomManager roomManager;
            RoomManager roomManager2 = roomManager = this;
            synchronized (roomManager2) {
                this.currentHeartbeatInterval = l;
                ScheduledFuture<?> scheduledFuture = this.heartbeatFuture;
                this.heartbeatFuture = null;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
                this.heartbeatFuture = this.scheduler.scheduleAtFixedRate(this::heartbeatTask, l, l, TimeUnit.SECONDS);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void rescheduleSignalPoll(long l) {
        if (this.currentRoom.get() != null) {
            RoomManager roomManager;
            RoomManager roomManager2 = roomManager = this;
            synchronized (roomManager2) {
                this.currentSignalPollInterval = l;
                ScheduledFuture<?> scheduledFuture = this.signalPollFuture;
                this.signalPollFuture = null;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
                this.scheduleSignalPoll();
            }
        }
    }

    private void heartbeatTask() {
        try {
            IntegratedServer integratedServer;
            RoomState roomState = this.currentRoom.get();
            if (roomState == null || roomState == PENDING) {
                return;
            }
            String string = roomState.roomInfo.getNatType() != null ? roomState.roomInfo.getNatType() : "unknown";
            JsonObject jsonObject = this.topologyClient.pollAndGetPeerLatency();
            int n = this.heartbeatSeq.incrementAndGet();
            RoomState roomState2 = roomState;
            int n2 = 0;
            if (roomState.roomInfo.isHost() && (integratedServer = Minecraft.func_71410_x().func_71401_C()) != null) {
                n2 = integratedServer.func_184103_al().func_72394_k();
            }
            ((CompletableFuture)this.signalingClient.heartbeat(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), roomState.roomInfo.isHost(), string, 0.1, jsonObject, n, this.topologyClient.getOverlayManager().getLocalPort(), n2).thenAccept(apiResponse -> {
                if (this.currentRoom.get() == roomState2) {
                    long l;
                    long l2;
                    if (!apiResponse.success) {
                        if (!"RATE_LIMITED".equals(apiResponse.error) && !"CDN_ERROR".equals(apiResponse.error)) {
                            if ("ROOM_EVICTED".equals(apiResponse.error)) {
                                VoxLinkMod.LOGGER.warn("Kicked by server (heartbeat)");
                                this.notifyRoomEvicted();
                                return;
                            }
                            if (!("ROOM_NOT_FOUND".equals(apiResponse.error) || "ROOM_EXPIRED".equals(apiResponse.error) || "INVALID_TOKEN".equals(apiResponse.error) || "ROOM_CLOSED".equals(apiResponse.error))) {
                                int n = this.heartbeatFailCount.incrementAndGet();
                                VoxLinkMod.LOGGER.warn("Heartbeat failed ({}/{}): {}", new Object[]{n, 8, apiResponse.error});
                                if ("SERVER_403".equals(apiResponse.error) || "SERVER_404".equals(apiResponse.error)) {
                                    VoxLinkMod.LOGGER.warn("Server temp error ({}), not counted as heartbeat fail", (Object)apiResponse.error);
                                    this.heartbeatFailCount.decrementAndGet();
                                }
                                if (n >= 8) {
                                    VoxLinkMod.LOGGER.error("Heartbeat failed too many times, room may be lost");
                                    this.handleRoomLost();
                                }
                                return;
                            }
                            VoxLinkMod.LOGGER.warn("Room no longer exists (heartbeat): {}", (Object)apiResponse.error);
                            this.handleRoomLost("ROOM_CLOSED".equals(apiResponse.error) ? "HOST_CLOSED" : ("INVALID_TOKEN".equals(apiResponse.error) ? "TOKEN_INVALID" : apiResponse.error));
                            return;
                        }
                        this.heartbeatFailCount.set(0);
                        long l3 = Math.min(this.currentHeartbeatInterval * 2L, 30L);
                        if (apiResponse.retryAfter > 0) {
                            l3 = Math.max(l3, (long)apiResponse.retryAfter);
                        }
                        if (l3 != this.currentHeartbeatInterval) {
                            VoxLinkMod.LOGGER.warn("Heartbeat {}/backoff to {}s", (Object)apiResponse.error, (Object)l3);
                            this.rescheduleHeartbeat(l3);
                        }
                        return;
                    }
                    this.heartbeatFailCount.set(0);
                    long l4 = Math.max((long)VoxLinkMod.getConfig().getHeartbeatInterval(), 5L);
                    if (this.currentHeartbeatInterval > l4) {
                        VoxLinkMod.LOGGER.info("Heartbeat recovered, restore interval to {}s", (Object)l4);
                        this.rescheduleHeartbeat(l4);
                    }
                    if (apiResponse.data != null && apiResponse.data.has("topology") && !apiResponse.data.get("topology").isJsonNull()) {
                        JsonObject jsonObject = apiResponse.data.getAsJsonObject("topology");
                        try {
                            Minecraft.func_71410_x().func_152344_a(() -> this.topologyClient.handleTopologyInstruction(jsonObject));
                        }
                        catch (NoClassDefFoundError noClassDefFoundError) {
                            // empty catch block
                        }
                    }
                    if (apiResponse.data != null && apiResponse.data.has("heartbeatInterval") && (l2 = Math.max(l = apiResponse.data.get("heartbeatInterval").getAsLong(), 5L)) != this.currentHeartbeatInterval) {
                        VoxLinkMod.LOGGER.info("Adjust heartbeat interval: server={}s, actual={}s", (Object)l, (Object)l2);
                        this.rescheduleHeartbeat(l2);
                    }
                    if (apiResponse.data != null && apiResponse.data.has("currentPlayers")) {
                        try {
                            int n = apiResponse.data.get("currentPlayers").getAsInt();
                            roomState.roomInfo.setCurrentPlayers(n);
                            if (roomState.roomInfo.isHost() && n <= 1 && roomState.roomInfo.getPeerCount() == 0 && roomState.roomInfo.hasEverHadPeer()) {
                                if (++this.hostAloneCount >= 3) {
                                    this.hostAloneCount = 0;
                                    this.connectionManager.notifyAllPeersGone();
                                }
                            } else {
                                this.hostAloneCount = 0;
                            }
                        }
                        catch (Exception exception) {
                            // empty catch block
                        }
                    }
                    if (apiResponse.data != null && apiResponse.data.has("nameModerationStatus")) {
                        try {
                            String string = apiResponse.data.get("nameModerationStatus").getAsString();
                            String string2 = apiResponse.data.has("name") ? apiResponse.data.get("name").getAsString() : null;
                            this.handleNameModerationUpdate(roomState2, string, apiResponse.data.has("nameModerationReason") ? apiResponse.data.get("nameModerationReason").getAsString() : null, string2, apiResponse.data.has("nameApproved") && apiResponse.data.get("nameApproved").getAsBoolean());
                        }
                        catch (Exception exception) {
                            // empty catch block
                        }
                    }
                }
            })).exceptionally(object -> {
                int n = this.heartbeatFailCount.incrementAndGet();
                VoxLinkMod.LOGGER.warn("Heartbeat exception ({}/{}): {}", new Object[]{n, 8, ((Throwable)object).getMessage()});
                if (n >= 8) {
                    this.handleRoomLost();
                }
                return null;
            });
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.error("Heartbeat task sync error", (Throwable)exception);
        }
    }

    private void startSignalPoll() {
        this.signalPollTimestamp.set(System.currentTimeMillis() - 10000L);
        RoomState roomState = this.currentRoom.get();
        this.currentSignalPollInterval = roomState != null && !roomState.roomInfo.isHost() ? (this.signalingClient.isWsConnected() ? 1000L : 200L) : (long)VoxLinkMod.getConfig().getSignalPollInterval();
        this.registerSignalPushHandler();
        this.scheduleSignalPoll();
    }

    private void registerSignalPushHandler() {
        this.signalingClient.setSignalPushHandler(jsonObject -> {
            if (jsonObject == null) {
                return;
            }
            RoomState roomState = this.currentRoom.get();
            if (roomState == null || roomState == PENDING) {
                return;
            }
            this.scheduler.execute(() -> {
                try {
                    SignalingClient.ApiResponse apiResponse = new SignalingClient.ApiResponse(true, null, null, (JsonObject)jsonObject);
                    this.handleSignalPollResponse(apiResponse);
                }
                catch (Exception exception) {
                    VoxLinkMod.LOGGER.warn("Signal push handle error: {}", (Object)exception.getMessage());
                }
            });
        });
    }

    private void scheduleSignalPoll() {
        this.scheduler.execute(this::doSignalPoll);
        long l = this.currentSignalPollInterval;
        RoomState roomState = this.currentRoom.get();
        if (roomState != null && roomState != PENDING && !roomState.roomInfo.isHost() && l <= 250L) {
            l += (long)ThreadLocalRandom.current().nextInt(100);
        }
        this.signalPollFuture = this.scheduler.scheduleAtFixedRate(this::doSignalPoll, l, l, TimeUnit.MILLISECONDS);
    }

    private void doSignalPoll() {
        try {
            RoomState roomState = this.currentRoom.get();
            if (roomState == null || roomState == PENDING) {
                return;
            }
            if (!this.signalPollInFlight.compareAndSet(false, true)) {
                return;
            }
            RoomState roomState2 = roomState;
            int n = this.pollCount.incrementAndGet();
            long l = System.currentTimeMillis();
            ((CompletableFuture)((CompletableFuture)this.signalingClient.pollSignals(roomState.roomInfo.getCode(), roomState.roomInfo.getToken(), roomState.roomInfo.isHost(), this.signalPollTimestamp.get()).thenAccept(apiResponse -> {
                if (this.currentRoom.get() != roomState2) {
                    this.signalPollInFlight.set(false);
                } else {
                    long l2 = System.currentTimeMillis() - l;
                    if (n <= 5 || l2 > 5000L || !apiResponse.success) {
                        VoxLinkMod.LOGGER.info("[RoomManager] Signal poll #{}: {}ms, success={}, hasSignals={}", new Object[]{n, l2, apiResponse.success, apiResponse.success && apiResponse.data != null && (apiResponse.data.has("s") || apiResponse.data.has("signals"))});
                    }
                    try {
                        this.handleSignalPollResponse((SignalingClient.ApiResponse)apiResponse);
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.warn("Signal poll response handle error: {}", (Object)exception.getMessage());
                    }
                }
            })).exceptionally(object -> {
                long l2 = System.currentTimeMillis() - l;
                VoxLinkMod.LOGGER.warn("Signal poll #{} error ({}ms): {}", new Object[]{n, l2, ((Throwable)object).getMessage()});
                return null;
            })).whenComplete((object, object2) -> this.signalPollInFlight.set(false));
        }
        catch (Exception exception) {
            this.signalPollInFlight.set(false);
            VoxLinkMod.LOGGER.error("Signal poll sync error", (Throwable)exception);
        }
    }

    private void handleSignalPollResponse(SignalingClient.ApiResponse apiResponse) {
        if (!apiResponse.success || apiResponse.data == null || !apiResponse.data.has("s") && !apiResponse.data.has("signals")) {
            if (apiResponse.success && apiResponse.data != null && apiResponse.data.has("ts")) {
                this.signalPollTimestamp.accumulateAndGet(apiResponse.data.get("ts").getAsLong(), Math::max);
                this.recoverSignalPollInterval();
            } else if (!apiResponse.success) {
                VoxLinkMod.LOGGER.warn("[RoomManager] Signal poll failed: {} - {}", (Object)apiResponse.error, (Object)apiResponse.message);
                if ("RATE_LIMITED".equals(apiResponse.error) || "CDN_ERROR".equals(apiResponse.error) || "SERVER_403".equals(apiResponse.error) || "SERVER_404".equals(apiResponse.error)) {
                    this.backoffSignalPollInterval();
                    return;
                }
                if ("ROOM_EVICTED".equals(apiResponse.error)) {
                    VoxLinkMod.LOGGER.warn("Kicked by server (signal poll)");
                    this.notifyRoomEvicted();
                } else if ("ROOM_NOT_FOUND".equals(apiResponse.error) || "ROOM_EXPIRED".equals(apiResponse.error) || "INVALID_TOKEN".equals(apiResponse.error) || "ROOM_CLOSED".equals(apiResponse.error)) {
                    VoxLinkMod.LOGGER.warn("Room no longer exists on server: {}", (Object)apiResponse.error);
                    this.handleRoomLost("ROOM_CLOSED".equals(apiResponse.error) ? "HOST_CLOSED" : ("INVALID_TOKEN".equals(apiResponse.error) ? "TOKEN_INVALID" : apiResponse.error));
                }
            } else if (apiResponse.success) {
                this.recoverSignalPollInterval();
            }
        } else {
            String string = apiResponse.data.has("s") ? "s" : "signals";
            String string2 = apiResponse.data.has("ts") ? "ts" : "timestamp";
            String string3 = string2;
            if (!apiResponse.data.get(string).isJsonArray()) {
                return;
            }
            JsonArray jsonArray = apiResponse.data.getAsJsonArray(string);
            VoxLinkMod.LOGGER.debug("[RoomManager] Signal poll: received {} signals", (Object)jsonArray.size());
            for (JsonElement jsonElement : jsonArray) {
                if (!jsonElement.isJsonObject()) continue;
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                String string4 = jsonObject.has("type") ? jsonObject.get("type").getAsString() : "unknown";
                VoxLinkMod.LOGGER.info("[RoomManager] Signal dispatch: type={}, from={}", (Object)string4, (Object)(jsonObject.has("from") ? jsonObject.get("from").getAsString() : "?"));
                this.handleSignal(jsonObject);
                if (!jsonObject.has("timestamp") || jsonObject.get("timestamp").isJsonNull()) continue;
                this.signalPollTimestamp.accumulateAndGet(jsonObject.get("timestamp").getAsLong(), Math::max);
            }
            if (apiResponse.data.has(string2)) {
                this.signalPollTimestamp.accumulateAndGet(apiResponse.data.get(string2).getAsLong(), Math::max);
            }
            this.recoverSignalPollInterval();
        }
    }

    private void backoffSignalPollInterval() {
        long l = Math.min(this.currentSignalPollInterval * 2L, 10000L);
        if (l != this.currentSignalPollInterval) {
            VoxLinkMod.LOGGER.warn("Signal poll rate limited/CDN error, backoff to {}ms", (Object)l);
            this.rescheduleSignalPoll(l);
        }
    }

    private void recoverSignalPollInterval() {
        long l;
        boolean bl;
        RoomState roomState = this.currentRoom.get();
        boolean bl2 = bl = roomState != null && roomState != PENDING && !roomState.roomInfo.isHost();
        long l2 = bl ? (this.signalingClient.isWsConnected() ? 1000L : 250L) : (l = (long)VoxLinkMod.getConfig().getSignalPollInterval());
        if (this.currentSignalPollInterval != l) {
            this.currentSignalPollInterval = l;
            this.rescheduleSignalPoll(l);
        }
    }

    private void handleSignal(JsonObject jsonObject) {
        block60: {
            block59: {
                if (!jsonObject.has("type") || jsonObject.get("type").isJsonNull() || !jsonObject.has("from") || jsonObject.get("from").isJsonNull()) break block59;
                String string = jsonObject.get("type").getAsString();
                String string2 = jsonObject.get("from").getAsString();
                JsonObject jsonObject2 = jsonObject.has("data") && jsonObject.get("data").isJsonObject() ? jsonObject.getAsJsonObject("data") : new JsonObject();
                VoxLinkMod.LOGGER.debug("Received signal: type={}, from={}", (Object)string, (Object)string2);
                switch (string) {
                    case "join_request": {
                        this.connectionManager.handleJoinRequest(string2, jsonObject2);
                        break;
                    }
                    case "holepunch_offer": {
                        this.connectionManager.handleHolePunchOffer(string2, jsonObject2);
                        break;
                    }
                    case "holepunch_mapped": {
                        this.connectionManager.handleHolepunchMapped(string2, jsonObject2);
                        break;
                    }
                    case "holepunch_answer": {
                        this.connectionManager.handleHolePunchAnswer(string2, jsonObject2);
                        break;
                    }
                    case "connected": {
                        this.handleConnected(string2, jsonObject2);
                        break;
                    }
                    case "disconnect": {
                        this.handleDisconnect(string2, jsonObject2);
                        break;
                    }
                    case "host_closing": {
                        this.handleHostClosing(string2, jsonObject2);
                        break;
                    }
                    case "room_evicted": {
                        this.handleRoomEvicted(string2, jsonObject2);
                        break;
                    }
                    case "punch_info": {
                        this.connectionManager.handlePunchInfo(string2, jsonObject2);
                        break;
                    }
                    case "peer_port": {
                        this.connectionManager.handlePeerPort(string2, jsonObject2);
                        break;
                    }
                    case "reverse_holepunch_offer": {
                        this.connectionManager.handleReverseHolepunchOffer(string2, jsonObject2);
                        break;
                    }
                    case "reverse_punch_info": {
                        this.connectionManager.handleReversePunchInfo(string2, jsonObject2);
                        break;
                    }
                    case "tcp_simopen_request": {
                        this.connectionManager.handleTcpSimopenRequest(string2, jsonObject2);
                        break;
                    }
                    case "relay_request": {
                        this.connectionManager.handleRelayRequest(string2, jsonObject2);
                        break;
                    }
                    case "relay_accept": {
                        this.connectionManager.handleRelayAccept(string2, jsonObject2);
                        break;
                    }
                    case "relay_declined": {
                        this.connectionManager.handleRelayDeclined(string2, jsonObject2);
                        break;
                    }
                    case "relay_setup": {
                        this.connectionManager.handleRelaySetup(string2, jsonObject2);
                        break;
                    }
                    case "relay_notify": {
                        this.connectionManager.handleRelayNotify(string2, jsonObject2);
                        break;
                    }
                    case "relay_ready": {
                        this.connectionManager.handleRelayReady(string2, jsonObject2);
                        break;
                    }
                    case "cancel_connection": {
                        this.connectionManager.handleCancelConnection(string2, jsonObject2);
                        break;
                    }
                    case "ice_restart": {
                        this.connectionManager.handleIceRestart(string2, jsonObject2);
                        break;
                    }
                    case "room_name_approved": {
                        RoomState roomState = this.currentRoom.get();
                        if (roomState != null && roomState != PENDING) {
                            String string3 = jsonObject2.has("name") ? jsonObject2.get("name").getAsString() : null;
                            this.handleNameModerationUpdate(roomState, "approved", null, string3, true);
                            break;
                        }
                        break block60;
                    }
                    case "room_name_rejected": {
                        RoomState roomState = this.currentRoom.get();
                        if (roomState != null && roomState != PENDING) {
                            String string4 = jsonObject2.has("name") ? jsonObject2.get("name").getAsString() : null;
                            String string5 = jsonObject2.has("reason") ? jsonObject2.get("reason").getAsString() : null;
                            this.handleNameModerationUpdate(roomState, "rejected", string5, string4, false);
                            break;
                        }
                        break block60;
                    }
                    case "room_name_unavailable": {
                        RoomState roomState = this.currentRoom.get();
                        if (roomState != null && roomState != PENDING) {
                            String string6 = jsonObject2.has("name") ? jsonObject2.get("name").getAsString() : null;
                            this.handleNameModerationUpdate(roomState, "unavailable", null, string6, false);
                            break;
                        }
                        break block60;
                    }
                    case "topology_optimization_done": {
                        this.topologyClient.handleTopologySignal(string, jsonObject2);
                        break;
                    }
                    case "topology_change": {
                        this.topologyClient.handleTopologySignal(string, jsonObject2);
                        break;
                    }
                    default: {
                        VoxLinkMod.LOGGER.debug("Unknown signal type: {}", (Object)string);
                        break;
                    }
                }
                break block60;
            }
            VoxLinkMod.LOGGER.warn("Skip malformed signal: missing type or from");
        }
    }

    public ConnectionManager getConnectionManager() {
        return this.connectionManager;
    }

    public boolean isConnectionCycleActive() {
        return this.connectionManager.isConnectionCycleActive();
    }

    public boolean isConnectionActive() {
        return this.connectionManager.isConnectionActive();
    }

    private void handleConnected(String string, JsonObject jsonObject) {
        VoxLinkMod.LOGGER.info("Peer connected: {}", (Object)string);
        RoomState roomState = this.currentRoom.get();
        if (roomState != null && roomState != PENDING && roomState.roomInfo.isHost()) {
            if (string != null && this.connectionManager.hasUdpTransport(string)) {
                this.connectionManager.stopAllPunchingAfterHostBridge();
            } else {
                VoxLinkMod.LOGGER.info("[RoomManager] Peer connected but host transport not ready, keep punching");
            }
            String string2 = roomState.roomInfo.getCode();
            if (string2 != null && !string2.equals(this.guestOpPolicyRoomCode)) {
                this.guestOpPolicyRoomCode = string2;
                this.scheduler.schedule(() -> {
                    try {
                        Minecraft minecraft = Minecraft.func_71410_x();
                        if (minecraft == null) {
                            return;
                        }
                        minecraft.func_152344_a(() -> this.applyGuestOpPolicy(roomState, minecraft));
                    }
                    catch (Exception exception) {
                        VoxLinkMod.LOGGER.warn("[RoomManager] handleConnected exception: {}", (Object)exception.getMessage());
                    }
                }, 2L, TimeUnit.SECONDS);
            }
        }
    }

    public void applyOpPolicy(MinecraftServer minecraftServer, boolean bl, boolean bl2) {
        String string = Minecraft.func_71410_x().func_110432_I().func_111285_a();
        minecraftServer.func_152344_a(() -> {
            try {
                for (EntityPlayerMP entityPlayerMP : minecraftServer.func_184103_al().func_181057_v()) {
                    String string2 = entityPlayerMP.func_70005_c_();
                    boolean bl3 = string2.equals(string);
                    boolean bl4 = bl3 ? bl : bl2;
                    String string3 = bl4 ? "op " + string2 : "deop " + string2;
                    ((CommandHandler)minecraftServer.func_71187_D()).func_71556_a((ICommandSender)minecraftServer, string3);
                    VoxLinkMod.LOGGER.info("[RoomManager] {}{}: {}", new Object[]{bl3 ? "Host" : "Visitor", bl4 ? "OP" : "DEOP", string2});
                }
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[RoomManager] OP policy apply failed: {}", (Object)exception.getMessage());
            }
        });
    }

    private void applyGuestOpPolicy(RoomState roomState, Minecraft minecraft) {
        try {
            IntegratedServer integratedServer = minecraft.func_71401_C();
            if (integratedServer == null) {
                return;
            }
            this.applyOpPolicy((MinecraftServer)integratedServer, roomState.roomInfo.isHostOp(), roomState.roomInfo.isGuestOp());
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[RoomManager] guest OP handle failed: {}", (Object)exception.getMessage());
        }
    }

    private void handleDisconnect(String string, JsonObject jsonObject) {
        VoxLinkMod.LOGGER.info("Peer disconnected: {}", (Object)string);
        RoomState roomState = this.currentRoom.get();
        if (roomState != null && roomState != PENDING && roomState.roomInfo.isHost() && string != null) {
            this.connectionManager.clearHostPunchingState();
            ReliableUdpTransport reliableUdpTransport = this.connectionManager.removeUdpTransport(string);
            if (reliableUdpTransport != null) {
                try {
                    reliableUdpTransport.close();
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
        if (roomState != null && roomState != PENDING && !roomState.roomInfo.isHost() && string != null && ("host".equals(string) || string.startsWith("host_"))) {
            this.handleRoomLost("HOST_DISCONNECTED");
        }
    }

    private void handleHostClosing(String string, JsonObject jsonObject) {
        VoxLinkMod.LOGGER.info("Host is closing room");
        this.handleRoomLost("HOST_CLOSED");
    }

    private void handleRoomEvicted(String string, JsonObject jsonObject) {
        VoxLinkMod.LOGGER.warn("Kicked by server");
        this.notifyRoomEvicted();
    }

    private void notifyRoomEvicted() {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft != null) {
            minecraft.func_152344_a(() -> {
                if (minecraft.field_71439_g != null) {
                    VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.evicted_notice", (Object[])new Object[0])));
                }
            });
        }
    }

    private void warnPortBlockedCombined(Boolean bl, Boolean bl2, String string, String string2) {
        Minecraft minecraft;
        boolean bl3 = bl != null && bl == false;
        boolean bl4 = bl2 != null && bl2 == false;
        boolean bl5 = bl4;
        if ((bl3 || bl4) && (minecraft = Minecraft.func_71410_x()) != null) {
            minecraft.func_152344_a(() -> {
                if (minecraft.field_71439_g != null) {
                    ITextComponent iTextComponent = new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.error_prefix", (Object[])new Object[0])).func_150255_a(new Style().func_150238_a(TextFormatting.RED));
                    TextComponentString textComponentString = bl3 && bl4 ? new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.both_unreachable", (Object[])new Object[0])) : (bl3 ? new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.ipv4_unreachable", (Object[])new Object[0])) : new TextComponentString(I18n.func_135052_a((String)"voxlink.chat.ipv6_unreachable", (Object[])new Object[0])));
                    VoxLinkChat.print(iTextComponent.func_150257_a((ITextComponent)textComponentString));
                }
            });
        }
    }

    private static final class CreateRoomResult {
        final NatResult natResult;
        final SignalingClient.ApiResponse apiResponse;
        final String hostIp;
        final String hostIpv6;

        CreateRoomResult(NatResult natResult, SignalingClient.ApiResponse apiResponse, String string, String string2) {
            this.natResult = natResult;
            this.apiResponse = apiResponse;
            this.hostIp = string;
            this.hostIpv6 = string2;
        }
    }

    private static class TransientException
    extends RuntimeException {
        TransientException(String string) {
            super(string);
        }
    }

    private static final class NatResult {
        final String nat;
        final int port;
        final int geyserPort;

        NatResult(String string, int n, int n2) {
            this.nat = string;
            this.port = n;
            this.geyserPort = n2;
        }
    }

    static class RoomState {
        final RoomInfo roomInfo;

        RoomState(RoomInfo roomInfo) {
            this.roomInfo = roomInfo;
        }
    }
}

