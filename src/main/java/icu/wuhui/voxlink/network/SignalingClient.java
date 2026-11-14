/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 */
package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.GsonFix;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.Http8;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.SignalingWsTransport;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

public class SignalingClient {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 2;
    private static final int LOG_BODY_MAX_LEN = 500;
    private static final int HEARTBEAT_TIMEOUT_MS = 8000;
    private static final int HEARTBEAT_RETRY_DELAY_SEC = 2;
    private static final String RPC_PATH = "/rpc.php";
    private static final Map<String, String> ACTION_TO_ROUTE = SignalingClient.buildActionRoutes();
    private final VoxLinkConfig config;
    private volatile SignalingWsTransport wsTransport = null;
    private final Object wsTransportLock = new Object();
    private volatile String joinIdempotencyNonce = null;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private static final int CREATE_ROOM_TIMEOUT_MS = 8000;
    private static final int POLL_SIGNALS_TIMEOUT_MS = 5000;
    private static final int GET_PUBLIC_IP_TIMEOUT_MS = 3000;
    private static final Set<String> TRANSIENT_ERRORS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED", "QUEUED", "SERVER_403")));

    private static <T> CompletableFuture<T> delayedFail(long l, TimeUnit timeUnit) {
        CompletableFuture completableFuture = new CompletableFuture();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voxlink-timeout");
            thread.setDaemon(true);
            return thread;
        });
        scheduledExecutorService.schedule(() -> {
            completableFuture.completeExceptionally(new TimeoutException("timed out after " + l + "ms"));
            scheduledExecutorService.shutdown();
        }, l, timeUnit);
        return completableFuture;
    }

    private static Map<String, String> buildActionRoutes() {
        HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("create_room", "/room/create");
        hashMap.put("update_room", "/room/update");
        hashMap.put("join_room", "/room/join");
        hashMap.put("leave_room", "/room/leave");
        hashMap.put("list_rooms", "/room/list");
        hashMap.put("heartbeat", "/room/heartbeat");
        hashMap.put("send_signal", "/signal/send");
        hashMap.put("poll_signals", "/signal/poll");
        hashMap.put("get_ip", "/stun");
        hashMap.put("check_port", "/stun/check");
        hashMap.put("get_categories", "/categories");
        hashMap.put("report_ready", "/topology/report_ready");
        hashMap.put("poll_topology", "/topology/poll");
        hashMap.put("relay_register", "/relay/register");
        hashMap.put("relay_candidates", "/relay/candidates");
        return Collections.unmodifiableMap(hashMap);
    }

    private String getRpcBaseUrl() {
        String string = this.config.getServerUrl();
        if (string != null && !string.trim().isEmpty()) {
            VoxLinkMod.LOGGER.debug("[SignalingClient] serverUrl config: {}", (Object)string);
            return string.contains("route=") ? string : string.replaceAll("/+$", "") + "/?route=";
        }
        VoxLinkMod.LOGGER.error("[SignalingClient] serverUrl is empty");
        return "";
    }

    private String buildPath(String string) {
        String string2 = this.getRpcBaseUrl();
        if (string2.contains("route=")) {
            String string3 = ACTION_TO_ROUTE.getOrDefault(string, "/" + string);
            return string2 + string3;
        }
        return string2 + RPC_PATH;
    }

    private String buildGetPath(String string, String string2) {
        String string3 = this.getRpcBaseUrl();
        if (string3.contains("route=")) {
            String string4 = ACTION_TO_ROUTE.getOrDefault(string, "/" + string);
            String string5 = string3 + string4;
            if (string2 != null && !string2.isEmpty()) {
                string5 = string5 + "&" + string2;
            }
            return string5;
        }
        String string6 = string3 + "/rpc.php?action=" + string;
        if (string2 != null && !string2.isEmpty()) {
            string6 = string6 + "&" + string2;
        }
        return string6;
    }

    public SignalingClient(VoxLinkConfig voxLinkConfig) {
        this.config = voxLinkConfig;
        String string = voxLinkConfig.getServerUrl();
        if (string != null && string.startsWith("http://")) {
            VoxLinkMod.LOGGER.warn("[SignalingClient] Using HTTP not HTTPS: {}, recommend switching to HTTPS", (Object)string);
        }
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-Signaling");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-Signaling-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
        VoxLinkMod.LOGGER.info("[SignalingClient] HTTP transport ready, connectTimeout={}ms", (Object)voxLinkConfig.getConnectionTimeout());
    }

    public CompletableFuture<ApiResponse> createRoom(String string, String string2, int n, int n2, String string3, int n3, boolean bl, String string4, String string5, int n4, int n5, String string6, String string7) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", string != null ? string : "");
        if (string2 != null && !string2.isEmpty()) {
            jsonObject.addProperty("password", string2);
        }
        jsonObject.addProperty("maxPlayers", (Number)n);
        jsonObject.addProperty("hostPort", (Number)n2);
        jsonObject.addProperty("natType", string3 != null ? string3 : "unknown");
        if (n3 > 0) {
            jsonObject.addProperty("bedrockPort", (Number)n3);
        }
        jsonObject.addProperty("visible", Boolean.valueOf(bl));
        jsonObject.addProperty("authType", string4 != null ? string4 : "OFFLINE");
        jsonObject.addProperty("category", string5 != null ? string5 : "other");
        if (n4 > 0) {
            jsonObject.addProperty("protocolVersion", (Number)n4);
        }
        if (n5 > 0) {
            jsonObject.addProperty("peerPort", (Number)n5);
        }
        if (string7 != null && !string7.isEmpty()) {
            jsonObject.addProperty("gameVersion", string7);
        }
        if (string6 != null && !string6.isEmpty()) {
            jsonObject.addProperty("hostIpv6", string6);
        }
        jsonObject.addProperty("loader", "forge");
        jsonObject.addProperty("clientProtocolVersion", (Number)7);
        JsonArray jsonArray = new JsonArray();
        for (String string8 : ProtocolNegotiator.CURRENT_CAPABILITIES) {
            GsonFix.addString(jsonArray, string8);
        }
        jsonObject.add("clientCapabilities", (JsonElement)jsonArray);
        jsonObject.addProperty("action", "create_room");
        return this.postCreateRoom(this.buildPath("create_room"), jsonObject);
    }

    private CompletableFuture<ApiResponse> postCreateRoom(String string, JsonObject jsonObject) {
        return this.postOnce(string, jsonObject, 8000L).thenCompose(apiResponse -> {
            if (!apiResponse.success && TRANSIENT_ERRORS.contains(apiResponse.error)) {
                VoxLinkMod.LOGGER.warn("POST {} failed {}, retry once after 2s (create room)", (Object)string, (Object)apiResponse.error);
                CompletableFuture<ApiResponse> completableFuture = new CompletableFuture<ApiResponse>();
                try {
                    this.scheduler.schedule(() -> this.postOnce(string, jsonObject, 8000L).whenComplete((apiResponse, throwable) -> {
                        if (throwable != null) {
                            completableFuture.completeExceptionally((Throwable)throwable);
                        } else {
                            completableFuture.complete((ApiResponse)apiResponse);
                        }
                    }), 2L, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    completableFuture.complete((ApiResponse)apiResponse);
                }
                return completableFuture;
            }
            return CompletableFuture.completedFuture(apiResponse);
        });
    }

    public CompletableFuture<ApiResponse> updateRoom(String string, String string2, String string3, String string4, int n, boolean bl, String string5, String string6) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string : "");
        jsonObject.addProperty("token", string2 != null ? string2 : "");
        jsonObject.addProperty("name", string3 != null ? string3 : "");
        if (string4 != null) {
            jsonObject.addProperty("password", string4);
        }
        jsonObject.addProperty("maxPlayers", (Number)n);
        jsonObject.addProperty("visible", Boolean.valueOf(bl));
        jsonObject.addProperty("authType", string5 != null ? string5 : "OFFLINE");
        jsonObject.addProperty("category", string6 != null ? string6 : "other");
        jsonObject.addProperty("action", "update_room");
        return this.postNoRetry(this.buildPath("update_room"), jsonObject);
    }

    public CompletableFuture<ApiResponse> updateTerracottaCode(String string, String string2, String string3) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string : "");
        jsonObject.addProperty("token", string2 != null ? string2 : "");
        jsonObject.addProperty("terracottaCode", string3 != null ? string3 : "");
        jsonObject.addProperty("action", "update_room");
        return this.postNoRetry(this.buildPath("update_room"), jsonObject);
    }

    public CompletableFuture<ApiResponse> joinRoom(String string, String string2) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string.toUpperCase() : "");
        if (string2 != null && !string2.isEmpty()) {
            jsonObject.addProperty("password", string2);
        }
        jsonObject.addProperty("action", "join_room");
        if (this.joinIdempotencyNonce == null) {
            this.joinIdempotencyNonce = UUID.randomUUID().toString().replace("-", "");
        }
        jsonObject.addProperty("idempotencyKey", this.joinIdempotencyNonce);
        jsonObject.addProperty("clientProtocolVersion", (Number)7);
        JsonArray jsonArray = new JsonArray();
        for (String string3 : ProtocolNegotiator.CURRENT_CAPABILITIES) {
            GsonFix.addString(jsonArray, string3);
        }
        jsonObject.add("clientCapabilities", (JsonElement)jsonArray);
        return this.post(this.buildPath("join_room"), jsonObject);
    }

    public CompletableFuture<ApiResponse> leaveRoom(String string, String string2, boolean bl) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string : "");
        jsonObject.addProperty("token", string2 != null ? string2 : "");
        jsonObject.addProperty("isHost", Boolean.valueOf(bl));
        jsonObject.addProperty("action", "leave_room");
        return this.postNoRetry(this.buildPath("leave_room"), jsonObject);
    }

    public CompletableFuture<ApiResponse> listRooms(int n, int n2, String string, String string2) {
        String string3 = "page=" + n + "&size=" + n2 + "&clientType=mod";
        if (string != null && !string.isEmpty()) {
            try {
                string3 = string3 + "&category=" + URLEncoder.encode(string, "UTF-8");
            }
            catch (UnsupportedEncodingException unsupportedEncodingException) {
                string3 = string3 + "&category=" + string;
            }
        }
        if (string2 != null && !string2.isEmpty()) {
            string3 = string3 + "&loader=" + string2;
        }
        return this.get(this.buildGetPath("list_rooms", string3));
    }

    public CompletableFuture<ApiResponse> heartbeat(String string, String string2, boolean bl, String string3, double d, JsonObject jsonObject, int n, int n2, int n3) {
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("code", string != null ? string : "");
        jsonObject2.addProperty("token", string2 != null ? string2 : "");
        jsonObject2.addProperty("isHost", Boolean.valueOf(bl));
        jsonObject2.addProperty("natType", string3 != null ? string3 : "unknown");
        jsonObject2.addProperty("load", (Number)d);
        jsonObject2.add("peerLatency", (JsonElement)(jsonObject != null ? jsonObject : new JsonObject()));
        jsonObject2.addProperty("seq", (Number)n);
        jsonObject2.addProperty("overlayPort", (Number)n2);
        jsonObject2.addProperty("currentInterval", (Number)VoxLinkMod.getConfig().getHeartbeatInterval());
        jsonObject2.addProperty("action", "heartbeat");
        if (bl && n3 > 0) {
            jsonObject2.addProperty("mcPlayerCount", (Number)n3);
        }
        return this.postOnce(this.buildPath("heartbeat"), jsonObject2, 8000L).thenCompose(apiResponse -> {
            if (!apiResponse.success && TRANSIENT_ERRORS.contains(apiResponse.error)) {
                CompletableFuture<ApiResponse> completableFuture = new CompletableFuture<ApiResponse>();
                try {
                    this.scheduler.schedule(() -> this.postOnce(this.buildPath("heartbeat"), jsonObject2, 8000L).whenComplete((apiResponse, throwable) -> {
                        if (throwable != null) {
                            completableFuture.completeExceptionally((Throwable)throwable);
                        } else {
                            completableFuture.complete((ApiResponse)apiResponse);
                        }
                    }), 2L, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    completableFuture.complete((ApiResponse)apiResponse);
                }
                return completableFuture;
            }
            return CompletableFuture.completedFuture(apiResponse);
        });
    }

    public CompletableFuture<ApiResponse> sendSignal(String string, String string2, boolean bl, String string3, JsonObject jsonObject) {
        return this.sendSignal(string, string2, bl, string3, jsonObject, null);
    }

    public CompletableFuture<ApiResponse> sendSignal(String string, String string2, boolean bl, String string3, JsonObject jsonObject, String string4) {
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("code", string != null ? string : "");
        jsonObject2.addProperty("token", string2 != null ? string2 : "");
        jsonObject2.addProperty("isHost", Boolean.valueOf(bl));
        jsonObject2.addProperty("type", string3 != null ? string3 : "");
        jsonObject2.add("data", (JsonElement)(jsonObject != null ? jsonObject : new JsonObject()));
        if (string4 != null && !string4.isEmpty()) {
            jsonObject2.addProperty("to", string4);
        }
        jsonObject2.addProperty("action", "send_signal");
        return this.post(this.buildPath("send_signal"), jsonObject2);
    }

    public CompletableFuture<ApiResponse> pollSignals(String string, String string2, boolean bl, long l) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string : "");
        jsonObject.addProperty("token", string2 != null ? string2 : "");
        jsonObject.addProperty("isHost", Boolean.valueOf(bl));
        jsonObject.addProperty("since", (Number)l);
        jsonObject.addProperty("action", "poll_signals");
        return this.postOnce(this.buildPath("poll_signals"), jsonObject, 5000L);
    }

    public CompletableFuture<ApiResponse> getPublicIp() {
        return this.getOnce(this.buildGetPath("get_ip", null), 3000L);
    }

    public CompletableFuture<ApiResponse> checkPortReachable(String string, int n) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("ip", string != null ? string : "");
        jsonObject.addProperty("port", (Number)n);
        jsonObject.addProperty("action", "check_port");
        return this.post(this.buildPath("check_port"), jsonObject);
    }

    public CompletableFuture<ApiResponse> registerRelayPeer(String string, String string2, String string3, String string4, int n, boolean bl) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("clientId", string);
        jsonObject.addProperty("roomCode", string2);
        jsonObject.addProperty("natType", string3);
        jsonObject.addProperty("mappedIp", string4);
        jsonObject.addProperty("mappedPort", (Number)n);
        jsonObject.addProperty("relayEnabled", Boolean.valueOf(bl));
        jsonObject.addProperty("action", "relay_register");
        return this.post(this.buildPath("relay_register"), jsonObject);
    }

    public CompletableFuture<ApiResponse> getRelayCandidates() {
        return this.get(this.buildGetPath("relay_candidates", null));
    }

    public CompletableFuture<ApiResponse> getCategories() {
        return this.get(this.buildGetPath("get_categories", null));
    }

    public CompletableFuture<ApiResponse> reportLinkReady(String string, String string2, boolean bl) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string : "");
        jsonObject.addProperty("token", string2 != null ? string2 : "");
        jsonObject.addProperty("isHost", Boolean.valueOf(bl));
        jsonObject.addProperty("action", "report_ready");
        return this.post(this.buildPath("report_ready"), jsonObject);
    }

    public CompletableFuture<ApiResponse> pollTopology(String string, String string2, boolean bl, int n) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string != null ? string : "");
        jsonObject.addProperty("token", string2 != null ? string2 : "");
        jsonObject.addProperty("isHost", Boolean.valueOf(bl));
        jsonObject.addProperty("generation", (Number)n);
        jsonObject.addProperty("action", "poll_topology");
        return this.post(this.buildPath("poll_topology"), jsonObject);
    }

    private CompletableFuture<ApiResponse> postWithRetry(String string, JsonObject jsonObject, int n) {
        return this.postOnce(string, jsonObject).thenCompose(apiResponse -> {
            if (!apiResponse.success && TRANSIENT_ERRORS.contains(apiResponse.error) && n > 0) {
                long l = apiResponse.retryAfter > 0 ? (long)apiResponse.retryAfter : 2L;
                VoxLinkMod.LOGGER.warn("POST {} failed {}, retry after {}s ({} left)", new Object[]{string, apiResponse.error, l, n});
                CompletableFuture<ApiResponse> completableFuture = new CompletableFuture<ApiResponse>();
                try {
                    this.scheduler.schedule(() -> this.postWithRetry(string, jsonObject, n - 1).whenComplete((apiResponse, throwable) -> {
                        if (throwable != null) {
                            completableFuture.completeExceptionally((Throwable)throwable);
                        } else {
                            completableFuture.complete((ApiResponse)apiResponse);
                        }
                    }), l, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    completableFuture.complete((ApiResponse)apiResponse);
                }
                return completableFuture;
            }
            return CompletableFuture.completedFuture(apiResponse);
        });
    }

    private CompletableFuture<ApiResponse> getWithRetry(String string, int n) {
        return this.getOnce(string).thenCompose(apiResponse -> {
            if (!apiResponse.success && TRANSIENT_ERRORS.contains(apiResponse.error) && n > 0) {
                long l = apiResponse.retryAfter > 0 ? (long)apiResponse.retryAfter : 2L;
                VoxLinkMod.LOGGER.warn("GET {} failed {}, retry after {}s ({} left)", new Object[]{string, apiResponse.error, l, n});
                CompletableFuture<ApiResponse> completableFuture = new CompletableFuture<ApiResponse>();
                try {
                    this.scheduler.schedule(() -> this.getWithRetry(string, n - 1).whenComplete((apiResponse, throwable) -> {
                        if (throwable != null) {
                            completableFuture.completeExceptionally((Throwable)throwable);
                        } else {
                            completableFuture.complete((ApiResponse)apiResponse);
                        }
                    }), l, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    completableFuture.complete((ApiResponse)apiResponse);
                }
                return completableFuture;
            }
            return CompletableFuture.completedFuture(apiResponse);
        });
    }

    private CompletableFuture<ApiResponse> post(String string, JsonObject jsonObject) {
        return this.postWithRetry(string, jsonObject, 2);
    }

    private CompletableFuture<ApiResponse> postNoRetry(String string, JsonObject jsonObject) {
        return this.postOnce(string, jsonObject);
    }

    private CompletableFuture<ApiResponse> postOnce(String string, JsonObject jsonObject) {
        return this.postOnce(string, jsonObject, this.config.getConnectionTimeout());
    }

    private CompletableFuture<ApiResponse> postOnce(String string, JsonObject jsonObject, long l) {
        WsRouteInfo wsRouteInfo;
        if (this.wsEnabled() && (wsRouteInfo = this.parseWsRoute(string)) != null) {
            return this.getWsTransport().request(wsRouteInfo.route, "POST", jsonObject, wsRouteInfo.query, l).handle((apiResponse, throwable) -> throwable != null ? this.httpPostOnce(string, jsonObject, l).join() : apiResponse);
        }
        return this.httpPostOnce(string, jsonObject, l);
    }

    private CompletableFuture<ApiResponse> httpPostOnce(String string, JsonObject jsonObject, long l) {
        String string2 = string.startsWith("http") ? string : this.getRpcBaseUrl() + string;
        VoxLinkMod.LOGGER.debug("[SignalingClient] POST {}", (Object)string2);
        long l2 = System.currentTimeMillis();
        return ((CompletableFuture)((CompletableFuture)Http8.postAsync(string2, jsonObject, "Mozilla/5.0 (Java) VoxLink/1.1.9", l).applyToEither((CompletionStage)SignalingClient.delayedFail(l, TimeUnit.MILLISECONDS), Function.identity())).thenApply(response -> {
            long l2 = System.currentTimeMillis() - l2;
            VoxLinkMod.LOGGER.debug("[SignalingClient] POST {} completed in {}ms, status={}", new Object[]{string2, l2, response.statusCode});
            int n = response.statusCode;
            if (n == 200) {
                return ApiResponse.fromHttpResponse(n, response.body);
            }
            if (n == 429) {
                int n2 = SignalingClient.parseRetryAfter(response);
                return new ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, n2);
            }
            if (n != 502 && n != 503 && n != 504) {
                ApiResponse apiResponse = ApiResponse.tryParseError(response.body);
                return apiResponse != null ? apiResponse : new ApiResponse(false, "SERVER_" + n, "SERVER_" + n, null);
            }
            ApiResponse apiResponse = ApiResponse.tryParseError(response.body);
            return apiResponse != null ? apiResponse : new ApiResponse(false, "CDN_ERROR", "CDN_ERROR", null, -1, 5);
        })).exceptionally(throwable -> {
            long l2 = System.currentTimeMillis() - l2;
            String string2 = throwable == null ? null : throwable.getMessage();
            Throwable throwable2 = throwable != null ? throwable.getCause() : null;
            VoxLinkMod.LOGGER.warn("[SignalingClient] POST {} failed after {}ms: {} (reason: {})", new Object[]{string2, l2, string2, throwable2 != null ? throwable2.getClass().getSimpleName() : "none"});
            if (!(throwable instanceof TimeoutException || string2 != null && (string2.contains("timed out") || string2.contains("Timeout") || string2.contains("timed_out")))) {
                if (string2 == null || !string2.contains("refused") && !string2.contains("Connection reset")) {
                    if (string2 != null && string2.contains("SSL")) {
                        return new ApiResponse(false, "NETWORK_ERROR", "SSL_ERROR", null);
                    }
                    return string2 == null || !string2.contains("UnknownHost") && !string2.contains("nodename") ? new ApiResponse(false, "NETWORK_ERROR", "NETWORK_ERROR", null) : new ApiResponse(false, "NETWORK_ERROR", "UNKNOWN_HOST", null);
                }
                return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_REFUSED", null);
            }
            return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_TIMEOUT", null);
        });
    }

    private CompletableFuture<ApiResponse> get(String string) {
        return this.getWithRetry(string, 2);
    }

    private CompletableFuture<ApiResponse> getOnce(String string) {
        return this.getOnce(string, this.config.getConnectionTimeout());
    }

    private CompletableFuture<ApiResponse> getOnce(String string, long l) {
        WsRouteInfo wsRouteInfo;
        if (this.wsEnabled() && (wsRouteInfo = this.parseWsRoute(string)) != null) {
            return this.getWsTransport().request(wsRouteInfo.route, "GET", null, wsRouteInfo.query, l).handle((apiResponse, throwable) -> throwable != null ? this.httpGetOnce(string, l).join() : apiResponse);
        }
        return this.httpGetOnce(string, l);
    }

    private CompletableFuture<ApiResponse> httpGetOnce(String string, long l) {
        String string2 = string.startsWith("http") ? string : this.getRpcBaseUrl() + string;
        VoxLinkMod.LOGGER.debug("[SignalingClient] GET {}", (Object)string2);
        long l2 = System.currentTimeMillis();
        return ((CompletableFuture)((CompletableFuture)Http8.getAsync(string2, "Mozilla/5.0 (Java) VoxLink/1.1.9", l).applyToEither((CompletionStage)SignalingClient.delayedFail(l, TimeUnit.MILLISECONDS), Function.identity())).thenApply(response -> {
            long l2 = System.currentTimeMillis() - l2;
            VoxLinkMod.LOGGER.debug("[SignalingClient] GET {} completed in {}ms, status={}", new Object[]{string2, l2, response.statusCode});
            int n = response.statusCode;
            if (n == 200) {
                return ApiResponse.fromHttpResponse(n, response.body);
            }
            if (n == 429) {
                int n2 = SignalingClient.parseRetryAfter(response);
                return new ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, n2);
            }
            if (n != 502 && n != 503 && n != 504) {
                ApiResponse apiResponse = ApiResponse.tryParseError(response.body);
                return apiResponse != null ? apiResponse : new ApiResponse(false, "SERVER_" + n, "SERVER_" + n, null);
            }
            ApiResponse apiResponse = ApiResponse.tryParseError(response.body);
            return apiResponse != null ? apiResponse : new ApiResponse(false, "CDN_ERROR", "CDN_ERROR", null, -1, 5);
        })).exceptionally(throwable -> {
            long l2 = System.currentTimeMillis() - l2;
            String string2 = throwable == null ? null : throwable.getMessage();
            Throwable throwable2 = throwable != null ? throwable.getCause() : null;
            VoxLinkMod.LOGGER.warn("[SignalingClient] GET {} failed after {}ms: {} (reason: {})", new Object[]{string2, l2, string2, throwable2 != null ? throwable2.getClass().getSimpleName() : "none"});
            if (!(throwable instanceof TimeoutException || string2 != null && (string2.contains("timed out") || string2.contains("Timeout")))) {
                if (string2 == null || !string2.contains("refused") && !string2.contains("Connection reset")) {
                    if (string2 != null && string2.contains("SSL")) {
                        return new ApiResponse(false, "NETWORK_ERROR", "SSL_ERROR", null);
                    }
                    return string2 == null || !string2.contains("UnknownHost") && !string2.contains("nodename") ? new ApiResponse(false, "NETWORK_ERROR", "NETWORK_ERROR", null) : new ApiResponse(false, "NETWORK_ERROR", "UNKNOWN_HOST", null);
                }
                return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_REFUSED", null);
            }
            return new ApiResponse(false, "NETWORK_ERROR", "CONNECTION_TIMEOUT", null);
        });
    }

    private static int parseRetryAfter(Http8.Response response) {
        if (response.retryAfterSeconds > 0L) {
            try {
                return Math.max((int)response.retryAfterSeconds, 1);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return 0;
    }

    public void shutdown() {
        if (this.wsTransport != null) {
            this.wsTransport.close();
            this.wsTransport = null;
        }
        this.executor.shutdownNow();
        this.scheduler.shutdownNow();
    }

    private boolean wsEnabled() {
        if (!this.config.isUseWebSocket()) {
            return false;
        }
        SignalingWsTransport signalingWsTransport = this.getWsTransport();
        return signalingWsTransport != null && signalingWsTransport.canUse();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private SignalingWsTransport getWsTransport() {
        if (this.wsTransport == null) {
            Object object;
            Object object2 = object = this.wsTransportLock;
            synchronized (object2) {
                if (this.wsTransport == null) {
                    this.wsTransport = new SignalingWsTransport(this.config.getServerUrl());
                }
            }
        }
        return this.wsTransport;
    }

    public boolean isWsConnected() {
        return this.wsTransport != null && this.wsTransport.isConnected();
    }

    public void setSignalPushHandler(Consumer<JsonObject> consumer) {
        if (!this.config.isUseWebSocket()) {
            return;
        }
        if (consumer != null) {
            this.getWsTransport().setPushListener(consumer);
        } else if (this.wsTransport != null) {
            this.wsTransport.setPushListener(null);
        }
    }

    private WsRouteInfo parseWsRoute(String string) {
        if (string == null || !string.startsWith("http")) {
            return null;
        }
        try {
            URI uRI = URI.create(string);
            String string2 = uRI.getQuery();
            if (string2 == null || string2.isEmpty()) {
                return null;
            }
            String string3 = null;
            LinkedHashMap<String, String> linkedHashMap = new LinkedHashMap<String, String>();
            for (String string4 : string2.split("&")) {
                int n = string4.indexOf(61);
                String string5 = n < 0 ? string4 : string4.substring(0, n);
                String string6 = n < 0 ? "" : string4.substring(n + 1);
                String string7 = string6;
                if ("route".equals(string5)) {
                    string3 = string6;
                    continue;
                }
                linkedHashMap.put(string5, string6);
            }
            if (string3 == null || string3.isEmpty()) {
                return null;
            }
            WsRouteInfo wsRouteInfo = new WsRouteInfo();
            wsRouteInfo.route = string3;
            wsRouteInfo.query = linkedHashMap.isEmpty() ? null : linkedHashMap;
            return wsRouteInfo;
        }
        catch (Exception exception) {
            return null;
        }
    }

    public static class ApiResponse {
        public final boolean success;
        public final String error;
        public final String message;
        public final JsonObject data;
        public final int queuePosition;
        public final int retryAfter;

        public ApiResponse(boolean bl, String string, String string2, JsonObject jsonObject) {
            this(bl, string, string2, jsonObject, -1, 0);
        }

        public ApiResponse(boolean bl, String string, String string2, JsonObject jsonObject, int n) {
            this(bl, string, string2, jsonObject, n, 0);
        }

        public ApiResponse(boolean bl, String string, String string2, JsonObject jsonObject, int n, int n2) {
            this.success = bl;
            this.error = string;
            this.message = string2;
            this.data = jsonObject;
            this.queuePosition = n;
            this.retryAfter = n2;
        }

        public static ApiResponse fromHttpResponse(int n, String string) {
            try {
                JsonObject jsonObject = (JsonObject)GSON.fromJson(string, JsonObject.class);
                if (jsonObject == null) {
                    return new ApiResponse(false, "PARSE_ERROR", "\u7a7a\u54cd\u5e94", null);
                }
                return ApiResponse.fromJson(jsonObject);
            }
            catch (Exception exception) {
                String string2 = string != null ? string.substring(0, Math.min(string.length(), 500)) : "null";
                VoxLinkMod.LOGGER.error("API response parse failed (status {}): {}", (Object)n, (Object)string2);
                VoxLinkMod.LOGGER.error("Parse error: {}", (Object)exception.getMessage());
                return new ApiResponse(false, "PARSE_ERROR", "\u54cd\u5e94\u89e3\u6790\u5931\u8d25", null);
            }
        }

        public static ApiResponse fromJson(JsonObject jsonObject) {
            if (jsonObject == null) {
                return new ApiResponse(false, "PARSE_ERROR", "\u7a7a\u54cd\u5e94", null);
            }
            boolean bl = jsonObject.has("success") && jsonObject.get("success").getAsBoolean();
            String string = jsonObject.has("error") ? jsonObject.get("error").getAsString() : null;
            String string2 = jsonObject.has("message") ? jsonObject.get("message").getAsString() : null;
            JsonObject jsonObject2 = jsonObject.has("data") && jsonObject.get("data").isJsonObject() ? jsonObject.getAsJsonObject("data") : null;
            int n = jsonObject.has("position") ? jsonObject.get("position").getAsInt() : -1;
            int n2 = jsonObject.has("retryAfter") ? jsonObject.get("retryAfter").getAsInt() : 0;
            return new ApiResponse(bl, string, string2, jsonObject2, n, n2);
        }

        public static ApiResponse tryParseError(String string) {
            if (string != null && !string.isEmpty()) {
                try {
                    JsonObject jsonObject = (JsonObject)GSON.fromJson(string, JsonObject.class);
                    if (jsonObject == null) {
                        return null;
                    }
                    String string2 = jsonObject.has("error") ? jsonObject.get("error").getAsString() : null;
                    String string3 = jsonObject.has("message") ? jsonObject.get("message").getAsString() : null;
                    int n = jsonObject.has("position") ? jsonObject.get("position").getAsInt() : -1;
                    int n2 = jsonObject.has("retryAfter") ? jsonObject.get("retryAfter").getAsInt() : 0;
                    int n3 = n2;
                    if (string2 != null) {
                        return new ApiResponse(false, string2, string3, null, n, n2);
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
                return null;
            }
            return null;
        }
    }

    private static final class WsRouteInfo {
        String route;
        Map<String, String> query;

        private WsRouteInfo() {
        }
    }
}

