/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 */
package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.SimpleWsClient;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public final class SignalingWsTransport {
    private static final Gson GSON = new Gson();
    private static final long[] BACKOFF_STEPS = new long[]{10000L, 30000L, 60000L};
    private static final long HEARTBEAT_WATCHDOG_MS = 90000L;
    private static final long CONNECT_TIMEOUT_MS = 3000L;
    private final String wsUrl;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong idGen = new AtomicLong(0L);
    private final ConcurrentHashMap<Long, CompletableFuture<SignalingClient.ApiResponse>> pending = new ConcurrentHashMap();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile SimpleWsClient webSocket;
    private volatile Consumer<JsonObject> pushListener;
    private final AtomicLong nextRetryAt = new AtomicLong(0L);
    private final AtomicLong backoffStep = new AtomicLong(0L);
    private final AtomicLong lastFrameAt = new AtomicLong(System.currentTimeMillis());
    private final Object connectLock = new Object();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile CompletableFuture<Boolean> connectPromise;

    private static <T> CompletableFuture<T> delayedFail(CompletableFuture<T> completableFuture, long l) {
        CompletableFuture completableFuture2 = new CompletableFuture();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voxlink-ws-timeout");
            thread.setDaemon(true);
            return thread;
        });
        scheduledExecutorService.schedule(() -> {
            completableFuture2.completeExceptionally(new TimeoutException("ws timeout after " + l + "ms"));
            scheduledExecutorService.shutdown();
        }, l, TimeUnit.MILLISECONDS);
        return completableFuture.applyToEither((CompletionStage)completableFuture2, Function.identity());
    }

    public SignalingWsTransport(String string) {
        this.wsUrl = SignalingWsTransport.deriveWsUrl(string);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-WS");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-WS-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::heartbeatWatchdog, 30L, 30L, TimeUnit.SECONDS);
    }

    private static String deriveWsUrl(String string) {
        int n;
        String string2;
        int n2;
        if (string == null || string.trim().isEmpty()) {
            return null;
        }
        String string3 = string.trim();
        int n3 = string3.indexOf(63);
        if (n3 >= 0) {
            string3 = string3.substring(0, n3);
        }
        if ((n2 = (string2 = (n = string3.indexOf("://")) >= 0 ? string3.substring(n + 3) : string3).indexOf(47)) >= 0) {
            string2 = string2.substring(0, n2);
        }
        if (string2.isEmpty()) {
            return null;
        }
        String string4 = string3.startsWith("https://") ? "wss://" : (string3.startsWith("http://") ? "ws://" : "wss://");
        return string4 + string2 + "/ws";
    }

    public boolean isConnected() {
        return this.connected.get() && this.webSocket != null;
    }

    public boolean canUse() {
        return !this.closed.get() && this.wsUrl != null && System.currentTimeMillis() >= this.nextRetryAt.get();
    }

    public void setPushListener(Consumer<JsonObject> consumer) {
        this.pushListener = consumer;
    }

    public CompletableFuture<SignalingClient.ApiResponse> request(String string, String string2, JsonObject jsonObject, Map<String, String> map, long l) {
        if (!this.canUse()) {
            return SignalingWsTransport.failed(new IOException("WS \u4e0d\u53ef\u7528\uff08\u9000\u907f\u4e2d\u6216\u672a\u914d\u7f6e\uff09"));
        }
        CompletableFuture<SignalingClient.ApiResponse> completableFuture = new CompletableFuture<SignalingClient.ApiResponse>();
        this.ensureConnected().whenComplete((bl, throwable) -> {
            if (throwable != null || !Boolean.TRUE.equals(bl)) {
                this.markUnavailable();
                completableFuture.completeExceptionally(throwable != null ? throwable : new IOException("WS \u8fde\u63a5\u5931\u8d25"));
                return;
            }
            try {
                this.sendFrame(completableFuture, string, string2, jsonObject, map, l);
            }
            catch (Exception exception) {
                completableFuture.completeExceptionally(exception);
            }
        });
        return completableFuture;
    }

    private void sendFrame(CompletableFuture<SignalingClient.ApiResponse> completableFuture, String string, String string2, JsonObject jsonObject, Map<String, String> map, long l) {
        block7: {
            SimpleWsClient simpleWsClient = this.webSocket;
            if (simpleWsClient == null || !simpleWsClient.isOpen()) {
                completableFuture.completeExceptionally(new IOException("WS \u5df2\u65ad\u5f00"));
                return;
            }
            long l2 = this.idGen.incrementAndGet();
            if (l2 == 0L) {
                l2 = this.idGen.incrementAndGet();
            }
            long l3 = l2;
            CompletableFuture completableFuture2 = new CompletableFuture();
            this.pending.put(l3, completableFuture2);
            SignalingWsTransport.delayedFail(completableFuture2, l);
            completableFuture2.whenComplete((object, object2) -> {
                this.pending.remove(l3);
                if (object2 != null) {
                    completableFuture.completeExceptionally(SignalingWsTransport.unwrap((Throwable)object2));
                } else {
                    completableFuture.complete((SignalingClient.ApiResponse)object);
                }
            });
            JsonObject jsonObject2 = new JsonObject();
            jsonObject2.addProperty("id", (Number)l3);
            jsonObject2.addProperty("route", string);
            jsonObject2.addProperty("method", string2 != null ? string2 : "POST");
            if (jsonObject != null) {
                jsonObject2.add("body", (JsonElement)jsonObject);
            }
            if (map != null && !map.isEmpty()) {
                JsonObject jsonObject3 = new JsonObject();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    jsonObject3.addProperty(entry.getKey(), entry.getValue());
                }
                jsonObject2.add("query", (JsonElement)jsonObject3);
            }
            long l4 = l3;
            try {
                simpleWsClient.sendText(GSON.toJson((JsonElement)jsonObject2));
            }
            catch (Exception exception) {
                CompletableFuture<SignalingClient.ApiResponse> completableFuture3 = this.pending.remove(l4);
                if (completableFuture3 == null || completableFuture3.isDone()) break block7;
                completableFuture3.completeExceptionally(SignalingWsTransport.unwrap(exception));
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private CompletableFuture<Boolean> ensureConnected() {
        Object object;
        if (this.isConnected()) {
            return CompletableFuture.completedFuture(true);
        }
        Object object2 = object = this.connectLock;
        synchronized (object2) {
            CompletableFuture<Boolean> completableFuture;
            if (this.connecting.get() && (completableFuture = this.connectPromise) != null) {
                return completableFuture;
            }
            this.connecting.set(true);
            CompletableFuture completableFuture2 = new CompletableFuture();
            this.connectPromise = completableFuture2;
            completableFuture = completableFuture2;
            try {
                SignalingWsTransport.delayedFail(SimpleWsClient.connectAsync(URI.create(this.wsUrl), new WsListener(), 3000L), 3000L).whenComplete((simpleWsClient, throwable) -> {
                    this.connecting.set(false);
                    this.connectPromise = null;
                    if (throwable != null) {
                        VoxLinkMod.LOGGER.debug("[WS] \u8fde\u63a5\u5931\u8d25: {}", (Object)throwable.getMessage());
                        completableFuture2.complete(false);
                    } else {
                        this.onConnected((SimpleWsClient)simpleWsClient);
                        VoxLinkMod.LOGGER.debug("[WS] \u8fde\u63a5\u6210\u529f: {}", (Object)this.wsUrl);
                        completableFuture2.complete(true);
                    }
                });
            }
            catch (Exception exception) {
                this.connecting.set(false);
                this.connectPromise = null;
                VoxLinkMod.LOGGER.debug("[WS] \u5efa\u7acb\u8fde\u63a5\u5f02\u5e38: {}", (Object)exception.getMessage());
                completableFuture.complete(false);
            }
            return completableFuture;
        }
    }

    private void markUnavailable() {
        long l = this.backoffStep.getAndIncrement();
        long l2 = BACKOFF_STEPS[(int)Math.min(l, (long)(BACKOFF_STEPS.length - 1))];
        this.nextRetryAt.set(System.currentTimeMillis() + l2);
        VoxLinkMod.LOGGER.debug("[WS] \u6807\u8bb0\u4e0d\u53ef\u7528\uff0c\u9000\u907f {}ms\uff08\u7b2c {} \u6863\uff09", (Object)l2, (Object)(l + 1L));
    }

    private void onConnected(SimpleWsClient simpleWsClient) {
        this.webSocket = simpleWsClient;
        this.connected.set(true);
        this.lastFrameAt.set(System.currentTimeMillis());
        this.backoffStep.set(0L);
        this.nextRetryAt.set(0L);
    }

    private void onDisconnected() {
        boolean bl = this.connected.getAndSet(false);
        this.webSocket = null;
        for (Map.Entry<Long, CompletableFuture<SignalingClient.ApiResponse>> entry : this.pending.entrySet()) {
            CompletableFuture<SignalingClient.ApiResponse> completableFuture = entry.getValue();
            if (completableFuture.isDone()) continue;
            completableFuture.completeExceptionally(new IOException("WS \u5df2\u65ad\u5f00"));
        }
        this.pending.clear();
        if (bl) {
            VoxLinkMod.LOGGER.debug("[WS] \u8fde\u63a5\u65ad\u5f00\uff0c\u7b49\u5f85\u4e0b\u6b21\u8bf7\u6c42\u89e6\u53d1\u91cd\u8fde");
        }
    }

    private void handleFrame(String string) {
        try {
            JsonObject jsonObject = (JsonObject)GSON.fromJson(string, JsonObject.class);
            if (jsonObject == null) {
                return;
            }
            long l = jsonObject.has("id") ? jsonObject.get("id").getAsLong() : -1L;
            long l2 = l;
            if (l == 0L && jsonObject.has("push")) {
                JsonObject jsonObject2 = jsonObject.has("data") && jsonObject.get("data").isJsonObject() ? jsonObject.getAsJsonObject("data") : new JsonObject();
                Consumer<JsonObject> consumer = this.pushListener;
                if (consumer != null) {
                    this.executor.execute(() -> {
                        try {
                            consumer.accept(jsonObject2);
                        }
                        catch (Exception exception) {
                            VoxLinkMod.LOGGER.warn("[WS] \u63a8\u9001\u5904\u7406\u5f02\u5e38: {}", (Object)exception.getMessage());
                        }
                    });
                }
                return;
            }
            CompletableFuture<SignalingClient.ApiResponse> completableFuture = this.pending.remove(l);
            if (completableFuture == null) {
                return;
            }
            completableFuture.complete(SignalingClient.ApiResponse.fromJson(jsonObject));
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[WS] \u5e27\u89e3\u6790\u5931\u8d25: {}", (Object)exception.getMessage());
        }
    }

    private void heartbeatWatchdog() {
        if (!this.isConnected()) {
            return;
        }
        if (System.currentTimeMillis() - this.lastFrameAt.get() > 90000L) {
            VoxLinkMod.LOGGER.debug("[WS] \u5fc3\u8df3\u770b\u95e8\u72d7\uff1a90s \u5185\u65e0\u6536\u5e27\uff0c\u4e3b\u52a8\u65ad\u5f00\u91cd\u8fde");
            SimpleWsClient simpleWsClient = this.webSocket;
            if (simpleWsClient != null) {
                try {
                    simpleWsClient.close(1000, "watchdog");
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
            this.onDisconnected();
        }
    }

    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.connected.set(false);
            this.pushListener = null;
            SimpleWsClient simpleWsClient = this.webSocket;
            if (simpleWsClient != null) {
                try {
                    simpleWsClient.close(1000, "shutdown");
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
            this.webSocket = null;
            for (Map.Entry<Long, CompletableFuture<SignalingClient.ApiResponse>> entry : this.pending.entrySet()) {
                CompletableFuture<SignalingClient.ApiResponse> completableFuture = entry.getValue();
                if (completableFuture.isDone()) continue;
                completableFuture.completeExceptionally(new IOException("WS \u5df2\u5173\u95ed"));
            }
            this.pending.clear();
            this.scheduler.shutdownNow();
            this.executor.shutdownNow();
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture completableFuture = new CompletableFuture();
        completableFuture.completeExceptionally(throwable);
        return completableFuture;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private final class WsListener
    implements SimpleWsClient.Listener {
        private final StringBuilder buffer = new StringBuilder();

        private WsListener() {
        }

        @Override
        public void onOpen() {
            SignalingWsTransport.this.onConnected(SignalingWsTransport.this.webSocket);
        }

        @Override
        public void onText(CharSequence charSequence, boolean bl) {
            SignalingWsTransport.this.lastFrameAt.set(System.currentTimeMillis());
            this.buffer.append(charSequence);
            if (bl) {
                String string = this.buffer.toString();
                this.buffer.setLength(0);
                SignalingWsTransport.this.handleFrame(string);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            VoxLinkMod.LOGGER.debug("[WS] onError: {}", (Object)throwable.getMessage());
            SignalingWsTransport.this.onDisconnected();
        }

        @Override
        public void onClose(int n, String string) {
            SignalingWsTransport.this.onDisconnected();
        }
    }
}

