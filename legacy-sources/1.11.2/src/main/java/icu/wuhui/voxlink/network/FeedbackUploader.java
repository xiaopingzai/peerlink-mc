package icu.wuhui.voxlink.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 站内反馈上传器（移植 26.3 FeedbackUploader 到 Forge 1.11.2 / JDK8）。
 * 描述 + 附件（含可选游戏日志）以 multipart/form-data 提交到信令服务器 /feedback/submit；
 * 文件流式写出，不整块读进内存。500MB 上限由界面预检 + 服务端硬限共同把关。
 * 结果经回调返回（HTTP 线程触发，UI 侧自行切回主线程）。
 */
public final class FeedbackUploader {
    public static final long MAX_TOTAL_BYTES = 500L * 1024L * 1024L;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "voxlink-feedback");
        thread.setDaemon(true);
        return thread;
    });

    private FeedbackUploader() {
    }

    /** 提交结果（异步回调，HTTP 线程触发；UI 侧自行切回主线程）。 */
    public static class Result {
        public final boolean success;
        /** 失败错误码：RATE_LIMITED / FEEDBACK_TOO_LARGE / FEEDBACK_EMPTY / NETWORK_ERROR / 其他服务端错误码 */
        public final String errorCode;
        /** RATE_LIMITED 时的等待秒数（服务端 Retry-After），非限频为 0 */
        public final int retryAfterSec;

        Result(boolean success, String errorCode, int retryAfterSec) {
            this.success = success;
            this.errorCode = errorCode;
            this.retryAfterSec = retryAfterSec;
        }

        static Result ok() {
            return new Result(true, null, 0);
        }
    }

    /** 收集游戏目录 logs/ 下的日志文件（latest.log / debug.log），存在才收录。 */
    public static List<Path> defaultLogFiles(Path gameDir) {
        List<Path> out = new ArrayList<>();
        Path logs = gameDir.resolve("logs");
        for (String name : new String[]{"latest.log", "debug.log"}) {
            Path p = logs.resolve(name);
            if (Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 合并附件与日志（按路径去重、保持顺序），并返回总字节数。sizeOut[0] 接收总大小。 */
    public static List<Path> mergeFiles(List<Path> attachments, List<Path> logFiles, long[] sizeOut) {
        Set<Path> seen = new LinkedHashSet<>();
        if (attachments != null) {
            seen.addAll(attachments);
        }
        if (logFiles != null) {
            seen.addAll(logFiles);
        }

        long total = 0L;
        List<Path> out = new ArrayList<>();
        for (Path p : seen) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                if (Files.size(p) <= 0L) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            // 真实可读性探测：必须能打开读通道才算数，避免锁定文件把整次提交炸成 NETWORK_ERROR。
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(p)) {
                // 只做打开验证，立即关闭
            } catch (Throwable e) {
                VoxLinkMod.LOGGER.warn("[Feedback] skip unreadable file: {} ({})", p, e.toString());
                continue;
            }
            try {
                total += Files.size(p);
                out.add(p);
            } catch (Exception ignored) {
            }
        }
        sizeOut[0] = total;
        return out;
    }

    /**
     * 提交反馈。所有文件均流式上传；结果通过 callback 返回（非主线程）。
     *
     * @param serverUrl 信令服务器地址（如 https://link.voxdev.top）
     * @param clientInfo JSON 字符串（游戏版本/加载器/mod版本/玩家名等），可为空串
     */
    public static void submit(String serverUrl, String description, String clientInfo,
                              List<Path> attachments, List<Path> logFiles, Consumer<Result> callback) {
        EXECUTOR.execute(() -> {
            try {
                String url = normalizeUrl(serverUrl);
                if (url == null) {
                    VoxLinkMod.LOGGER.warn("[Feedback] submit aborted: bad server url: {}", serverUrl);
                    callback.accept(new Result(false, "BAD_SERVER_URL", 0));
                    return;
                }

                VoxLinkMod.LOGGER.info("[Feedback] submit: {} (desc={} chars, files={}, logs={})",
                    url, description == null ? 0 : description.length(),
                    attachments == null ? 0 : attachments.size(),
                    logFiles == null ? 0 : logFiles.size());

                long[] total = new long[]{0L};
                List<Path> files = mergeFiles(attachments, logFiles, total);
                if (total[0] > MAX_TOTAL_BYTES) {
                    callback.accept(new Result(false, "FEEDBACK_TOO_LARGE", 0));
                    return;
                }

                String boundary = "----VoxLinkFB" + UUID.randomUUID().toString().replace("-", "");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30 * 60 * 1000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setChunkedStreamingMode(8192);

                try (OutputStream out = conn.getOutputStream()) {
                    writeTextPart(out, boundary, "description", description == null ? "" : description);
                    writeTextPart(out, boundary, "client", "mod");
                    writeTextPart(out, boundary, "clientInfo", clientInfo == null ? "" : clientInfo);
                    for (Path p : files) {
                        writeFilePart(out, boundary, p);
                    }
                    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = readAll(in);
                conn.disconnect();
                callback.accept(parseResponse(status, body));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                VoxLinkMod.LOGGER.warn("[Feedback] submit network error: {}", cause.toString());
                callback.accept(new Result(false, "NETWORK_ERROR", 0));
            }
        });
    }

    private static Result parseResponse(int statusCode, String body) {
        String safeBody = body == null ? "" : body;
        VoxLinkMod.LOGGER.info("[Feedback] response: HTTP {} body={}", statusCode,
            safeBody.length() > 300 ? safeBody.substring(0, 300) + "…" : safeBody);
        try {
            JsonElement root = new JsonParser().parse(safeBody);
            if (!root.isJsonObject()) {
                return new Result(false, "BAD_RESPONSE", 0);
            }

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("success") && obj.get("success").isJsonPrimitive() && obj.get("success").getAsBoolean()) {
                return Result.ok();
            }

            String code = obj.has("error") && obj.get("error").isJsonPrimitive()
                ? obj.get("error").getAsString() : "BAD_RESPONSE";
            int retry = 0;
            if (obj.has("details") && obj.get("details").isJsonObject()) {
                JsonObject details = obj.getAsJsonObject("details");
                if (details.has("retryAfter") && details.get("retryAfter").isJsonPrimitive()) {
                    retry = details.get("retryAfter").getAsInt();
                }
            }
            return new Result(false, code, retry);
        } catch (Exception ex) {
            return new Result(false, "BAD_RESPONSE", 0);
        }
    }

    /** 归一化服务器地址并拼出提交端点 URL；无效返回 null。 */
    static String normalizeUrl(String serverUrl) {
        String url = serverUrl == null ? "" : serverUrl.trim();
        if (url.isEmpty()) {
            url = "https://link.voxdev.top";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        try {
            return new URL(url + "/?route=/feedback/submit").toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static void writeTextPart(OutputStream out, String boundary, String name, String value) throws IOException {
        String head = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(OutputStream out, String boundary, Path file) throws IOException {
        String fileName = file.getFileName().toString().replace("\"", "'");
        String head = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"attachments\"; filename=\"" + fileName + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        try (InputStream fin = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fin.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
