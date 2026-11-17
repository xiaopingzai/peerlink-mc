package icu.wuhui.voxlink.modsync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * modsync 专用 JDK8 HTTP 辅助（1.12.2 适配版）。
 * 26.3 Fabric 版使用 JDK11 的 java.net.http.HttpClient；本环境为 JDK8，
 * 统一用 HttpURLConnection 实现 GET/POST JSON 与响应读取，保留状态码、Retry-After 与重定向跟随。
 */
final class ModSyncHttp {
    private ModSyncHttp() {
    }

    static Response get(String url, String userAgent, int timeoutMs) throws IOException {
        return request("GET", url, null, userAgent, timeoutMs);
    }

    static Response postJson(String url, String jsonBody, String userAgent, int timeoutMs) throws IOException {
        return request("POST", url, jsonBody, userAgent, timeoutMs);
    }

    private static Response request(String method, String url, String jsonBody, String userAgent, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(Math.min(timeoutMs, 15000));
            conn.setReadTimeout(Math.min(timeoutMs, 20000));
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            if (userAgent != null && !userAgent.isEmpty()) {
                conn.setRequestProperty("User-Agent", userAgent);
            }

            if (jsonBody != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (in != null) {
                try {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        buf.write(chunk, 0, n);
                    }
                    body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                } finally {
                    in.close();
                }
            }

            long retryAfter = 0L;
            String ra = conn.getHeaderField("Retry-After");
            if (ra != null) {
                try {
                    retryAfter = Long.parseLong(ra.trim());
                } catch (NumberFormatException ignored) {
                    retryAfter = 3L;
                }
            }

            Map<String, String> headers = new HashMap<String, String>();
            String clen = conn.getHeaderField("Content-Length");
            if (clen != null) {
                headers.put("Content-Length", clen);
            }
            return new Response(code, body, retryAfter, headers);
        } finally {
            conn.disconnect();
        }
    }

    /** 同步响应：状态码、UTF-8 正文、Retry-After 秒数与首跳响应头。 */
    static final class Response {
        final int statusCode;
        final String body;
        final long retryAfterSeconds;
        final Map<String, String> headers;

        Response(int statusCode, String body, long retryAfterSeconds, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
            this.headers = headers;
        }
    }
}
