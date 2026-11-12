/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 */
package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.Http8;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class LogUploadManager {
    private static final long UPLOAD_DELAY_MS = 90000L;
    private static final long STABLE_WINDOW_MS = 120000L;
    private static final long ACTIVITY_TIMEOUT_MS = 15000L;
    private static final long HOST_WAIT_TIMEOUT_MS = 300000L;
    private static final int MAX_LOG_BYTES = 0x400000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 15000L;
    private static final String ROUTE_STATUS = "/log/status";
    private static final String ROUTE_UPLOAD = "/log/upload";
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VoxLink-LogUpload");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<String> ACTIVE_CODE = new AtomicReference<Object>(null);
    private static final AtomicBoolean UPLOADED = new AtomicBoolean(false);
    private static final AtomicBoolean UPLOAD_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile ScheduledFuture<?> uploadFuture;
    private static volatile ScheduledFuture<?> pollFuture;
    private static volatile Runnable onUploaded;
    private static volatile String activeCode;
    private static volatile boolean activeIsHost;
    private static volatile long punchStartMs;
    private static volatile long connectedAtMs;
    private static volatile long lastTransportActivityMs;
    private static volatile long lastActivityReportMs;
    private static volatile String role;

    private LogUploadManager() {
    }

    public static void arm(String string, boolean bl) {
        String string2 = string == null ? "" : string.trim();
        String string3 = string2;
        if (string2.isEmpty()) {
            return;
        }
        String string4 = ACTIVE_CODE.get();
        if (string4 != null && uploadFuture != null) {
            uploadFuture.cancel(false);
        }
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }
        ACTIVE_CODE.set(string2);
        activeCode = string2;
        activeIsHost = bl;
        punchStartMs = System.currentTimeMillis();
        role = bl ? "host" : "joiner";
        UPLOADED.set(false);
        VoxLinkMod.LOGGER.info("[LogUpload] armed code={} role={}", (Object)activeCode, (Object)role);
        LogUploadManager.reportStatus();
        if (uploadFuture != null) {
            uploadFuture.cancel(false);
        }
        if (!activeIsHost) {
            uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 90000L, TimeUnit.MILLISECONDS);
        }
        if (bl) {
            pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
        }
    }

    public static void schedulePunchUpload() {
        if (!activeIsHost || UPLOADED.get()) {
            return;
        }
        if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs >= 120000L) {
            return;
        }
        if (uploadFuture != null) {
            uploadFuture.cancel(false);
        }
        punchStartMs = System.currentTimeMillis();
        uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 90000L, TimeUnit.MILLISECONDS);
        if (pollFuture != null) {
            pollFuture.cancel(false);
        }
        pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
        VoxLinkMod.LOGGER.info("[LogUpload] host punch started, upload timer re-armed from now");
    }

    public static void onConnected() {
        lastTransportActivityMs = connectedAtMs = System.currentTimeMillis();
    }

    public static void onTransportActivity() {
        long l = System.currentTimeMillis();
        if (l - lastActivityReportMs >= 1000L) {
            lastActivityReportMs = l;
            lastTransportActivityMs = l;
        }
    }

    public static void onDisconnected() {
        if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs < 120000L && !UPLOADED.get()) {
            if (uploadFuture != null) {
                uploadFuture.cancel(false);
            }
            uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 1L, TimeUnit.SECONDS);
            VoxLinkMod.LOGGER.info("[LogUpload] link dropped inside stable window, upload now");
        }
        connectedAtMs = 0L;
    }

    private static void pollOpponentUploaded() {
        if (pollFuture == null) {
            return;
        }
        String string = activeCode;
        if (string == null || UPLOADED.get()) {
            return;
        }
        if (connectedAtMs > 0L && System.currentTimeMillis() - connectedAtMs >= 120000L) {
            if (pollFuture != null) {
                pollFuture.cancel(false);
                pollFuture = null;
            }
            return;
        }
        if (System.currentTimeMillis() - punchStartMs > 300000L) {
            VoxLinkMod.LOGGER.info("[LogUpload] host wait timeout, upload anyway");
            LogUploadManager.rescheduleUploadNow();
            return;
        }
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", string);
        jsonObject.addProperty("role", role);
        jsonObject.addProperty("enabled", Boolean.valueOf(LogUploadState.isLogUploadEnabled()));
        jsonObject.addProperty("query", Boolean.valueOf(true));
        LogUploadManager.postOnce(ROUTE_STATUS, jsonObject).thenAccept(apiResponse -> {
            if (pollFuture == null) {
                return;
            }
            boolean bl = false;
            if (apiResponse != null && apiResponse.success && apiResponse.data != null && apiResponse.data.has("opponent")) {
                JsonObject jsonObject = apiResponse.data.getAsJsonObject("opponent");
                bl = jsonObject.has("uploaded") && jsonObject.get("uploaded").getAsBoolean();
                boolean bl2 = bl;
            }
            if (bl) {
                VoxLinkMod.LOGGER.info("[LogUpload] opponent uploaded, host upload now");
                LogUploadManager.rescheduleUploadNow();
            } else {
                pollFuture = SCHEDULER.schedule(LogUploadManager::pollOpponentUploaded, 5L, TimeUnit.SECONDS);
            }
        });
    }

    private static void rescheduleUploadNow() {
        if (uploadFuture != null) {
            uploadFuture.cancel(false);
        }
        uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 1L, TimeUnit.MILLISECONDS);
    }

    public static void disarm() {
        if (uploadFuture != null) {
            uploadFuture.cancel(false);
            uploadFuture = null;
        }
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }
        ACTIVE_CODE.set(null);
    }

    public static void setOnUploaded(Runnable runnable) {
        onUploaded = runnable;
    }

    public static boolean isUploadFinished() {
        return UPLOADED.get();
    }

    private static void notifyUploadedInChat() {
        Minecraft minecraft = Minecraft.func_71410_x();
        if (minecraft == null || minecraft.field_71439_g == null) {
            return;
        }
        minecraft.func_152344_a(() -> {
            if (minecraft.field_71439_g == null) {
                return;
            }
            try {
                VoxLinkChat.print((ITextComponent)new TextComponentString("voxlink.log_upload.uploaded"));
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.debug("[LogUpload] chat notify failed: {}", (Object)exception.getMessage());
            }
        });
    }

    private static void reportStatus() {
        boolean bl = LogUploadState.isLogUploadEnabled();
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("code", activeCode);
        jsonObject.addProperty("role", role);
        jsonObject.addProperty("enabled", Boolean.valueOf(bl));
        LogUploadManager.postOnce(ROUTE_STATUS, jsonObject).thenAccept(apiResponse -> {
            if (!apiResponse.success) {
                VoxLinkMod.LOGGER.warn("[LogUpload] status report failed: {}", (Object)apiResponse.error);
            }
        });
    }

    private static void runUpload() {
        String string = activeCode;
        if (string == null || !ACTIVE_CODE.get().equals(string)) {
            return;
        }
        if (!LogUploadState.isLogUploadEnabled()) {
            VoxLinkMod.LOGGER.info("[LogUpload] skipped, upload disabled by player");
            return;
        }
        if (UPLOADED.get()) {
            return;
        }
        if (connectedAtMs > 0L) {
            long l = System.currentTimeMillis();
            if (l - lastTransportActivityMs > 15000L) {
                VoxLinkMod.LOGGER.info("[LogUpload] transport silent after connect, upload now");
            } else {
                if (l - connectedAtMs < 120000L) {
                    uploadFuture = SCHEDULER.schedule(LogUploadManager::runUpload, 15L, TimeUnit.SECONDS);
                    return;
                }
                VoxLinkMod.LOGGER.info("[LogUpload] connection stable, skip upload");
                return;
            }
        }
        LogUploadManager.uploadWithRetry(string, 3);
    }

    private static void uploadWithRetry(String string, int n) {
        if (!UPLOAD_IN_FLIGHT.compareAndSet(false, true)) {
            VoxLinkMod.LOGGER.info("[LogUpload] upload already in flight, skip duplicate");
            return;
        }
        byte[] byArray = LogUploadManager.buildPayload();
        if (byArray == null) {
            UPLOAD_IN_FLIGHT.set(false);
            return;
        }
        CompletableFuture<SignalingClient.ApiResponse> completableFuture = LogUploadManager.postBytes(ROUTE_UPLOAD, byArray, string);
        completableFuture.thenAccept(apiResponse -> {
            UPLOAD_IN_FLIGHT.set(false);
            if (apiResponse.success) {
                VoxLinkMod.LOGGER.info("[LogUpload] uploaded code={} role={} size={}", new Object[]{string, role, byArray.length});
                UPLOADED.set(true);
                Runnable runnable = onUploaded;
                if (runnable != null) {
                    runnable.run();
                }
                LogUploadManager.notifyUploadedInChat();
            } else if (n > 1) {
                VoxLinkMod.LOGGER.warn("[LogUpload] upload failed ({}), retrying {} left", (Object)apiResponse.error, (Object)(n - 1));
                SCHEDULER.schedule(() -> LogUploadManager.uploadWithRetry(string, n - 1), 15000L, TimeUnit.MILLISECONDS);
            } else {
                VoxLinkMod.LOGGER.warn("[LogUpload] upload gave up, error={}", (Object)apiResponse.error);
            }
        });
    }

    private static byte[] buildPayload() {
        try {
            byte[] byArray = LogUploadManager.readLogTail();
            if (byArray == null || byArray.length == 0) {
                VoxLinkMod.LOGGER.info("[LogUpload] no log content, skip");
                return null;
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gZIPOutputStream = new GZIPOutputStream(byteArrayOutputStream);){
                gZIPOutputStream.write(byArray);
            }
            return byteArrayOutputStream.toByteArray();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[LogUpload] gzip failed: {}", (Object)exception.getMessage());
            return null;
        }
    }

    private static byte[] readLogTail() {
        byte[] byArray;
        RandomAccessFile randomAccessFile;
        File file = new File(Minecraft.func_71410_x().field_71412_D, "logs/latest.log");
        if (!file.isFile()) {
            VoxLinkMod.LOGGER.info("[LogUpload] latest.log missing, skip");
            return null;
        }
        long l = file.length();
        if (l <= 0L) {
            return new byte[0];
        }
        long l2 = Math.max(0L, l - 0x400000L);
        try {
            randomAccessFile = new RandomAccessFile(file, "r");
        }
        catch (FileNotFoundException fileNotFoundException) {
            VoxLinkMod.LOGGER.warn("[LogUpload] latest.log not found: {}", (Object)fileNotFoundException.getMessage());
            return null;
        }
        try {
            randomAccessFile.seek(l2);
            byte[] byArray2 = new byte[(int)(l - l2)];
            randomAccessFile.readFully(byArray2);
            byArray = byArray2;
        }
        catch (Throwable throwable) {
            try {
                try {
                    randomAccessFile.close();
                }
                catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                }
                throw throwable;
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[LogUpload] read latest.log failed: {}", (Object)exception.getMessage());
                return null;
            }
        }
        try {
            randomAccessFile.close();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        return byArray;
    }

    private static CompletableFuture<SignalingClient.ApiResponse> postOnce(String string, JsonObject jsonObject) {
        return LogUploadManager.postJson(string, jsonObject).thenApply(LogUploadManager::parseResponse);
    }

    private static CompletableFuture<Http8.Response> postJson(String string, JsonObject jsonObject) {
        String string2 = LogUploadManager.buildUrl(string);
        String string3 = "Mozilla/5.0 (Java) VoxLink/1.1.6";
        return Http8.postAsync(string2, jsonObject, string3, LogUploadManager.timeoutMs());
    }

    private static CompletableFuture<SignalingClient.ApiResponse> postBytes(String string, byte[] byArray, String string2) {
        String string3 = LogUploadManager.buildUrl(string);
        String string4 = LogUploadManager.sha256(byArray);
        long l = Math.max(0L, System.currentTimeMillis() - punchStartMs);
        String string5 = Minecraft.func_71410_x().func_110432_I().func_111285_a();
        HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("Content-Type", "application/octet-stream");
        hashMap.put("User-Agent", "Mozilla/5.0 (Java) VoxLink/1.1.6");
        hashMap.put("X-VoxLink-Version", "1.1.8");
        hashMap.put("X-Log-Sha256", string4);
        hashMap.put("X-Log-Role", role);
        hashMap.put("X-Log-Code", string2);
        hashMap.put("X-Log-Name", string5 == null ? "" : string5);
        hashMap.put("X-Log-Version", "1.1.8");
        hashMap.put("X-Log-Duration-Ms", String.valueOf(l));
        return Http8.postBytesAsync(string3, byArray, hashMap, LogUploadManager.timeoutMs()).thenApply(LogUploadManager::parseResponse);
    }

    private static SignalingClient.ApiResponse parseResponse(Http8.Response response) {
        int n = response.statusCode;
        if (n == 200) {
            return SignalingClient.ApiResponse.fromHttpResponse(n, response.body);
        }
        if (n == 429) {
            return new SignalingClient.ApiResponse(false, "RATE_LIMITED", "RATE_LIMITED", null, -1, 15);
        }
        SignalingClient.ApiResponse apiResponse = SignalingClient.ApiResponse.tryParseError(response.body);
        return apiResponse != null ? apiResponse : new SignalingClient.ApiResponse(false, "SERVER_" + n, "SERVER_" + n, null);
    }

    private static String buildUrl(String string) {
        VoxLinkConfig voxLinkConfig = VoxLinkMod.getConfig();
        String string2 = voxLinkConfig.getServerUrl();
        if (string2 == null || string2.trim().isEmpty()) {
            return string;
        }
        String string3 = string2.contains("route=") ? string2 : string2.replaceAll("/+$", "") + "/?route=";
        return string3 + string;
    }

    private static long timeoutMs() {
        try {
            VoxLinkConfig voxLinkConfig = VoxLinkMod.getConfig();
            return voxLinkConfig != null ? (long)voxLinkConfig.getConnectionTimeout() : 10000L;
        }
        catch (Exception exception) {
            return 10000L;
        }
    }

    private static String sha256(byte[] byArray) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] byArray2 = messageDigest.digest(byArray);
            StringBuilder stringBuilder = new StringBuilder(64);
            for (byte by : byArray2) {
                stringBuilder.append(Character.forDigit(by >> 4 & 0xF, 16)).append(Character.forDigit(by & 0xF, 16));
            }
            return stringBuilder.toString();
        }
        catch (Exception exception) {
            return "";
        }
    }

    static {
        connectedAtMs = 0L;
        lastTransportActivityMs = 0L;
        lastActivityReportMs = 0L;
    }
}

