/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 */
package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Http8 {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "voxlink-http");
        thread.setDaemon(true);
        return thread;
    });

    private Http8() {
    }

    public static CompletableFuture<Response> postAsync(String string, JsonObject jsonObject, String string2, long l) {
        CompletableFuture<Response> completableFuture = new CompletableFuture<Response>();
        EXECUTOR.execute(() -> {
            try {
                completableFuture.complete(Http8.doRequest("POST", string, jsonObject != null ? jsonObject.toString() : null, string2, l));
            }
            catch (Throwable throwable) {
                completableFuture.completeExceptionally(throwable);
            }
        });
        return completableFuture;
    }

    public static CompletableFuture<Response> postBytesAsync(String string, byte[] byArray, Map<String, String> map, long l) {
        CompletableFuture<Response> completableFuture = new CompletableFuture<Response>();
        EXECUTOR.execute(() -> {
            try {
                completableFuture.complete(Http8.doBytesRequest(string, byArray, map, l));
            }
            catch (Throwable throwable) {
                completableFuture.completeExceptionally(throwable);
            }
        });
        return completableFuture;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static Response doBytesRequest(String string, byte[] byArray, Map<String, String> map, long l) throws Exception {
        int n;
        HttpURLConnection httpURLConnection = (HttpURLConnection)new URL(string).openConnection();
        httpURLConnection.setConnectTimeout((int)Math.min(l, 15000L));
        httpURLConnection.setReadTimeout((int)Math.min(l, 20000L));
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setInstanceFollowRedirects(true);
        httpURLConnection.setRequestProperty("Accept", "application/json");
        if (map != null) {
            for (Map.Entry<String, String> object2 : map.entrySet()) {
                httpURLConnection.setRequestProperty(object2.getKey(), object2.getValue());
            }
        }
        if (byArray != null) {
            httpURLConnection.setDoOutput(true);
            httpURLConnection.setFixedLengthStreamingMode(byArray.length);
            try (OutputStream outputStream = httpURLConnection.getOutputStream();){
                outputStream.write(byArray);
            }
        }
        InputStream inputStream = (n = httpURLConnection.getResponseCode()) >= 400 ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream();
        String string2 = "";
        if (inputStream != null) {
            try {
                int string3;
                ByteArrayOutputStream l2 = new ByteArrayOutputStream();
                byte[] byArray2 = new byte[8192];
                while ((string3 = inputStream.read(byArray2)) > 0) {
                    l2.write(byArray2, 0, string3);
                }
                string2 = new String(l2.toByteArray(), StandardCharsets.UTF_8);
            }
            finally {
                inputStream.close();
            }
        }
        long l2 = 0L;
        String string3 = httpURLConnection.getHeaderField("Retry-After");
        if (string3 != null) {
            try {
                l2 = Long.parseLong(string3.trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        httpURLConnection.disconnect();
        return new Response(n, string2, l2);
    }

    public static CompletableFuture<Response> getAsync(String string, String string2, long l) {
        CompletableFuture<Response> completableFuture = new CompletableFuture<Response>();
        EXECUTOR.execute(() -> {
            try {
                completableFuture.complete(Http8.doRequest("GET", string, null, string2, l));
            }
            catch (Throwable throwable) {
                completableFuture.completeExceptionally(throwable);
            }
        });
        return completableFuture;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static Response doRequest(String string, String string2, String string3, String string4, long l) throws Exception {
        int n;
        Closeable closeable;
        HttpURLConnection httpURLConnection = (HttpURLConnection)new URL(string2).openConnection();
        httpURLConnection.setConnectTimeout((int)Math.min(l, 15000L));
        httpURLConnection.setReadTimeout((int)Math.min(l, 20000L));
        httpURLConnection.setRequestMethod(string);
        httpURLConnection.setRequestProperty("Accept", "application/json");
        httpURLConnection.setRequestProperty("User-Agent", string4);
        httpURLConnection.setRequestProperty("X-VoxLink-Version", string4 != null && string4.contains("/") ? string4.substring(string4.indexOf(47) + 1) : "unknown");
        httpURLConnection.setInstanceFollowRedirects(true);
        if (string3 != null) {
            httpURLConnection.setRequestProperty("Content-Type", "application/json");
            httpURLConnection.setDoOutput(true);
            byte[] byArray = string3.getBytes(StandardCharsets.UTF_8);
            httpURLConnection.setFixedLengthStreamingMode(byArray.length);
            closeable = httpURLConnection.getOutputStream();
            try {
                ((OutputStream)closeable).write(byArray);
            }
            finally {
                if (closeable != null) {
                    ((OutputStream)closeable).close();
                }
            }
        }
        closeable = (n = httpURLConnection.getResponseCode()) >= 400 ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream();
        String string5 = "";
        if (closeable != null) {
            try {
                int n2;
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] byArray = new byte[8192];
                while ((n2 = ((InputStream)closeable).read(byArray)) > 0) {
                    byteArrayOutputStream.write(byArray, 0, n2);
                }
                string5 = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
            }
            finally {
                ((InputStream)closeable).close();
            }
        }
        long l2 = 0L;
        String string6 = httpURLConnection.getHeaderField("Retry-After");
        if (string6 != null) {
            try {
                l2 = Long.parseLong(string6.trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        httpURLConnection.disconnect();
        return new Response(n, string5, l2);
    }

    public static final class Response {
        public final int statusCode;
        public final String body;
        public final long retryAfterSeconds;

        public Response(int n, String string, long l) {
            this.statusCode = n;
            this.body = string;
            this.retryAfterSeconds = l;
        }
    }
}

