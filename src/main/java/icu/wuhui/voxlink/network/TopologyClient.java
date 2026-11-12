/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.network.P2POverlayManager;
import icu.wuhui.voxlink.network.SignalingClient;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TopologyClient
implements P2POverlayManager.PacketHandler {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-topology");
    private static final int SCHEDULE_DELAY_MS = 1000;
    private static final int SCHEDULE_PERIOD_MS = 500;
    private static final int UUID_PREFIX_LEN = 8;
    private static final ScheduledExecutorService TOPOLOGY_DELAY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VoxLink-Topology-Delay");
        thread.setDaemon(true);
        return thread;
    });
    private final P2POverlayManager overlayManager;
    private final SignalingClient signalingClient;
    private final AtomicInteger knownGeneration = new AtomicInteger(0);
    private final AtomicBoolean optimizing = new AtomicBoolean(false);
    private final AtomicBoolean reportedReady = new AtomicBoolean(false);
    private volatile String roomCode;
    private volatile String token;
    private volatile boolean isHost;
    private volatile String nodeId;
    private volatile Consumer<Boolean> onOptimizingChanged;
    private volatile Consumer<JsonObject> onDataReceived;
    private volatile String cachedUpstreamId;
    private volatile String cachedDownstreamId;

    public static void shutdown() {
        TOPOLOGY_DELAY_EXECUTOR.shutdown();
        try {
            if (!TOPOLOGY_DELAY_EXECUTOR.awaitTermination(2L, TimeUnit.SECONDS)) {
                TOPOLOGY_DELAY_EXECUTOR.shutdownNow();
            }
        }
        catch (InterruptedException interruptedException) {
            TOPOLOGY_DELAY_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public TopologyClient(SignalingClient signalingClient) {
        this.signalingClient = signalingClient;
        this.overlayManager = new P2POverlayManager(TopologyClient.getOrCreateNodeId(), 0);
    }

    public CompletableFuture<Void> onRoomJoined(String string, String string2, boolean bl, String string3, int n) {
        this.roomCode = string;
        this.token = string2;
        this.isHost = bl;
        this.nodeId = bl ? "host_" + string : "client_" + string3;
        this.knownGeneration.set(n);
        this.reportedReady.set(false);
        try {
            this.overlayManager.start(this);
            this.overlayManager.setNodeId(this.nodeId);
            LOGGER.info("P2P overlay started, node {}", (Object)this.nodeId);
        }
        catch (IOException iOException) {
            LOGGER.warn("P2P overlay start failed, keep direct: {}", (Object)iOException.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    public JsonObject pollAndGetPeerLatency() {
        JsonObject jsonObject = new JsonObject();
        if (this.overlayManager.getRole() == P2POverlayManager.Role.NONE) {
            return jsonObject;
        }
        int n = this.overlayManager.getUpstreamLatency();
        int n2 = this.overlayManager.getDownstreamLatency();
        if (n > 0 && this.cachedUpstreamId != null) {
            jsonObject.addProperty(this.cachedUpstreamId, (Number)n);
        }
        if (n2 > 0 && this.cachedDownstreamId != null) {
            jsonObject.addProperty(this.cachedDownstreamId, (Number)n2);
        }
        return jsonObject;
    }

    public void handleTopologyInstruction(JsonObject jsonObject) {
        if (jsonObject != null && !jsonObject.isJsonNull()) {
            String string = jsonObject.has("action") && !jsonObject.get("action").isJsonNull() ? jsonObject.get("action").getAsString() : "";
            int n = jsonObject.has("generation") ? jsonObject.get("generation").getAsInt() : 0;
            int n2 = n;
            if (n > this.knownGeneration.get()) {
                this.knownGeneration.set(n);
                this.reportedReady.set(false);
                LOGGER.info("Process topology instruction: action={}, gen={}", (Object)string, (Object)n);
                switch (string) {
                    case "become_head": {
                        this.handleBecomeHead(jsonObject);
                        break;
                    }
                    case "connect_to": {
                        this.handleConnectTo(jsonObject);
                        break;
                    }
                    case "direct_server": {
                        this.handleDirectServer(jsonObject);
                        break;
                    }
                    default: {
                        LOGGER.warn("Unknown topology instruction: {}", (Object)string);
                    }
                }
            }
        }
    }

    public void handlePollInstructions(JsonObject jsonObject) {
        if (jsonObject != null) {
            if (jsonObject.has("optimizing") && jsonObject.get("optimizing").getAsBoolean()) {
                this.setOptimizing(true);
            }
            if (jsonObject.has("mode") && "direct_fallback".equals(jsonObject.get("mode").getAsString()) && this.overlayManager.getRole() != P2POverlayManager.Role.NONE) {
                this.overlayManager.switchToDirectMode();
            }
            if (jsonObject.has("instructions") && jsonObject.get("instructions").isJsonArray()) {
                for (JsonElement jsonElement : jsonObject.getAsJsonArray("instructions")) {
                    if (!jsonElement.isJsonObject()) continue;
                    this.handleTopologyInstruction(jsonElement.getAsJsonObject());
                }
            }
        }
    }

    private void executeOnClientThread(Runnable runnable) {
        try {
            Minecraft.func_71410_x().func_152344_a(runnable);
        }
        catch (NoClassDefFoundError noClassDefFoundError) {
            runnable.run();
        }
    }

    public void handleTopologySignal(String string, JsonObject jsonObject) {
        switch (string) {
            case "topology_optimization_done": {
                this.setOptimizing(false);
                LOGGER.info("Topology optimization completed");
                break;
            }
            case "topology_change": {
                if (this.roomCode == null || this.token == null) break;
                this.signalingClient.pollTopology(this.roomCode, this.token, this.isHost, this.knownGeneration.get()).thenAccept(apiResponse -> {
                    if (apiResponse.success && apiResponse.data != null) {
                        this.executeOnClientThread(() -> this.handlePollInstructions(apiResponse.data));
                    }
                });
            }
        }
    }

    public void onRoomLeft() {
        this.overlayManager.stop();
        this.roomCode = null;
        this.token = null;
        this.nodeId = null;
        this.cachedUpstreamId = null;
        this.cachedDownstreamId = null;
        this.knownGeneration.set(0);
        this.setOptimizing(false);
        this.reportedReady.set(false);
    }

    public boolean isOptimizing() {
        return this.optimizing.get();
    }

    public void setOnOptimizingChanged(Consumer<Boolean> consumer) {
        this.onOptimizingChanged = consumer;
    }

    public void setOnDataReceived(Consumer<JsonObject> consumer) {
        this.onDataReceived = consumer;
    }

    public int getKnownGeneration() {
        return this.knownGeneration.get();
    }

    public String getNodeId() {
        return this.nodeId;
    }

    public P2POverlayManager getOverlayManager() {
        return this.overlayManager;
    }

    private void handleBecomeHead(JsonObject jsonObject) {
        String string = jsonObject.has("downstream") && !jsonObject.get("downstream").isJsonNull() ? jsonObject.get("downstream").getAsString() : null;
        String string2 = jsonObject.has("downstream_ip") ? jsonObject.get("downstream_ip").getAsString() : null;
        int n = jsonObject.has("downstream_port") ? jsonObject.get("downstream_port").getAsInt() : 0;
        this.cachedDownstreamId = string;
        this.overlayManager.becomeHead(string, string2, n);
        this.setOptimizing(true);
        TOPOLOGY_DELAY_EXECUTOR.schedule(this::reportLinkReady, 1000L, TimeUnit.MILLISECONDS);
    }

    private void handleConnectTo(JsonObject jsonObject) {
        String string = jsonObject.has("upstream") ? jsonObject.get("upstream").getAsString() : "";
        String string2 = jsonObject.has("upstream_ip") ? jsonObject.get("upstream_ip").getAsString() : "0.0.0.0";
        int n = jsonObject.has("upstream_port") ? jsonObject.get("upstream_port").getAsInt() : 0;
        String string3 = jsonObject.has("downstream") && !jsonObject.get("downstream").isJsonNull() ? jsonObject.get("downstream").getAsString() : null;
        this.cachedUpstreamId = string;
        this.cachedDownstreamId = string3;
        this.overlayManager.connectUpstream(string, string2, n);
        if (string3 != null) {
            int n2 = jsonObject.has("downstream_port") ? jsonObject.get("downstream_port").getAsInt() : 0;
            String string4 = jsonObject.has("downstream_ip") ? jsonObject.get("downstream_ip").getAsString() : null;
            this.overlayManager.setDownstream(string3, string4, n2);
        }
        this.setOptimizing(true);
        TOPOLOGY_DELAY_EXECUTOR.schedule(this::reportLinkReady, 500L, TimeUnit.MILLISECONDS);
    }

    private void handleDirectServer(JsonObject jsonObject) {
        String string = jsonObject.has("reason") ? jsonObject.get("reason").getAsString() : "unknown";
        LOGGER.info("Switched to direct mode, reason: {}", (Object)string);
        this.overlayManager.switchToDirectMode();
        this.setOptimizing(true);
        this.reportLinkReady();
    }

    private void reportLinkReady() {
        if (!this.reportedReady.getAndSet(true) && this.roomCode != null && this.token != null) {
            this.signalingClient.reportLinkReady(this.roomCode, this.token, this.isHost).thenAccept(apiResponse -> {
                if (apiResponse.success) {
                    LOGGER.info("Link ready report success");
                } else {
                    LOGGER.warn("Link ready report failed: {}", (Object)apiResponse.error);
                }
            });
        }
    }

    private void setOptimizing(boolean bl) {
        boolean bl2 = this.optimizing.getAndSet(bl);
        if (bl2 != bl && this.onOptimizingChanged != null) {
            this.onOptimizingChanged.accept(bl);
        }
    }

    @Override
    public void onDataReceived(String string, String string2, JsonObject jsonObject) {
        LOGGER.debug("Received P2P data, from={}, priority={}", (Object)string, (Object)string2);
        if (this.onDataReceived != null) {
            this.onDataReceived.accept(jsonObject);
        }
    }

    @Override
    public void onLinkReady() {
        LOGGER.info("P2P link ready");
        this.reportLinkReady();
    }

    @Override
    public void onLinkLost(String string) {
        LOGGER.warn("P2P link broken: {}", (Object)string);
        this.overlayManager.switchToDirectMode();
    }

    private static String getOrCreateNodeId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

