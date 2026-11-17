package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Modrinth 免 key 客户端（Forge 1.12.2 / JDK8 适配版）。
 * GET /project/{id}/version、搜索、sha1 反查版本、CDN 文件下载（sha512/sha1 校验）。
 * 26.3 原版基于 JDK11 HttpClient；本类用 HttpURLConnection 复刻镜像优先 / 官方兜底、
 * 429 退避、流式下载校验、原子改名与取消监视语义。
 */
public final class ModrinthClient {
   private static final String API = "https://api.modrinth.com/v2";
   /** MCIM 镜像（PCL 同款）：国内直连 MR 慢且不稳，镜像短超时优先、官方兜底。 */
   private static final String MIRROR_API = "https://mod.mcimirror.top/modrinth/v2";
   private static final String MIRROR_PREFIX = "https://mod.mcimirror.top";
   private static final int MIRROR_TIMEOUT_MS = 8000;
   private static final String USER_AGENT = "VoxLink/" + voxlinkVersion() + " (modsync)";
   private static final int CHUNK = 64;
   private static final int TIMEOUT_MS = 20000;
   /** 等待响应头（含重定向）的最长时间。 */
   private static final long HEADER_TIMEOUT_MS = 15000L;
   private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
   /** 弱网/国内网络下载退化：最大尝试次数。顺序为 镜像URL、官方原 URL。 */
   public static final int MAX_ATTEMPTS = 2;
   /** 尝试间退避：第 1 次后 1s、第 2 次后 3s。 */
   private static final long BACKOFF_MS_1 = 1000L;
   private static final long BACKOFF_MS_2 = 3000L;

   private ModrinthClient() {
   }

   private static String voxlinkVersion() {
      try {
         return icu.wuhui.voxlink.VoxLinkMod.MOD_VERSION;
      } catch (Exception e) {
         return "?";
      }
   }

   /** sha1 → version JSON；MR 不认识的哈希在返回 map 中缺席。 */
   public static Map<String, JsonObject> versionsFromSha1(List<String> sha1List) throws IOException, InterruptedException {
      Map<String, JsonObject> out = new HashMap<String, JsonObject>();
      for (int from = 0; from < sha1List.size(); from += CHUNK) {
         List<String> chunk = sha1List.subList(from, Math.min(sha1List.size(), from + CHUNK));
         JsonObject body = new JsonObject();
         JsonArray arr = new JsonArray();
         for (String h : chunk) {
            arr.add(h);
         }
         body.add("hashes", arr);
         body.addProperty("algorithm", "sha1");
         JsonObject resp;
         try {
            resp = postJson(apiUrls("/version_files"), body.toString());
         } catch (InterruptedException ie) {
            throw ie;
         } catch (Exception e) {
            // 分块网络失败只损失该块（落入 unknownMods），不作废整张清单
            ModSyncLog.warn("version_files chunk failed ({} hashes): {}", chunk.size(), e.getMessage());
            continue;
         }
         if (resp == null) {
            continue;
         }

         for (Map.Entry<String, JsonElement> e : resp.entrySet()) {
            if (e.getValue().isJsonObject()) {
               out.put(e.getKey(), e.getValue().getAsJsonObject());
            }
         }
      }

      return out;
   }

   /** projectId → project JSON。 */
   public static Map<String, JsonObject> projectsByIds(Set<String> ids) throws IOException, InterruptedException {
      Map<String, JsonObject> out = new HashMap<String, JsonObject>();
      List<String> list = new ArrayList<String>(ids);
      for (int from = 0; from < list.size(); from += CHUNK) {
         List<String> chunk = list.subList(from, Math.min(list.size(), from + CHUNK));
         StringBuilder sb = new StringBuilder("[");
         for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
               sb.append(',');
            }
            sb.append('"').append(chunk.get(i)).append('"');
         }
         sb.append(']');
         List<String> urls = apiUrls("/projects?ids=" + URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8.name()));
         JsonArray arr;
         try {
            arr = getJsonArray(urls);
         } catch (InterruptedException ie) {
            throw ie;
         } catch (Exception e) {
            ModSyncLog.warn("projects chunk failed ({} ids): {}", chunk.size(), e.getMessage());
            continue;
         }
         if (arr == null) {
            continue;
         }

         for (JsonElement el : arr) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
               out.put(el.getAsJsonObject().get("id").getAsString(), el.getAsJsonObject());
            }
         }
      }

      return out;
   }

   /** 指定项目在本端 MC 版本+加载器下的最新版本；无兼容版本返回 null。 */
   public static JsonObject latestVersionFor(String projectId, String gameVersion, String loader) throws IOException, InterruptedException {
      String gv = URLEncoder.encode("[\"" + escape(gameVersion) + "\"]", StandardCharsets.UTF_8.name());
      String ld = URLEncoder.encode("[\"" + escape(loader) + "\"]", StandardCharsets.UTF_8.name());
      JsonArray arr = getJsonArray(apiUrls("/project/" + escape(projectId) + "/version?game_versions=" + gv + "&loaders=" + ld));
      return arr != null && arr.size() > 0 && arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
   }

   /** GET /v2/search：名称+加载器搜项目；gameVersion 非空时加版本 facet（空=放宽搜索）。 */
   public static JsonArray search(String query, String loader, String gameVersion) throws IOException, InterruptedException {
      String facets = "[[\"project_type:mod\"],[\"categories:" + escape(loader) + "\"]";
      if (gameVersion != null && !gameVersion.trim().isEmpty()) {
         facets += ",[\"versions:" + escape(gameVersion) + "\"]";
      }
      facets += "]";
      List<String> urls = apiUrls("/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
         + "&facets=" + URLEncoder.encode(facets, StandardCharsets.UTF_8.name()) + "&limit=5");
      JsonObject o = getJsonObject(urls, false);
      return o != null && o.has("hits") && o.get("hits").isJsonArray() ? o.getAsJsonArray("hits") : new JsonArray();
   }

   /** GET /v2/project/{id|slug}；项目不存在(404)返回 null。 */
   public static JsonObject project(String idOrSlug) throws IOException, InterruptedException {
      return getJsonObject(apiUrls("/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8.name())), true);
   }

   /** API 请求链：镜像短超时在前，官方兜底在后。 */
   private static List<String> apiUrls(String pathAndQuery) {
      List<String> out = new ArrayList<String>();
      out.add(MIRROR_API + pathAndQuery);
      out.add(API + pathAndQuery);
      return out;
   }

   /**
    * 下载到 mods 目录：先写 .part，校验哈希后原子改名。
    * 弱网策略：最多 {@link #MAX_ATTEMPTS} 次尝试，镜像优先、官方兜底。
    * 任一阶段命中 shouldAbort 立即上抛 AbortedException。
    *
    * @param expectedSha512 Modrinth 文件的 sha512；非空时必须匹配
    * @param expectedSha1   回退校验：sha512 缺失但 sha1 存在时使用
    * @return 实际写入并通过校验的字节数
    */
   public static long downloadVerified(String url, String expectedSha512, String expectedSha1, String fileName, Path modsDir,
                                       BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      ensureSafeFileName(fileName);

      StringBuilder errors = new StringBuilder();

      for (int i = 0; i < MAX_ATTEMPTS; i++) {
         if (shouldAbort.getAsBoolean()) {
            throw new AbortedException(fileName);
         }

         String attemptUrl = i == 0 ? mirrorDownloadUrl(url) : url;
         try {
            long bytes = doDownload(attemptUrl, expectedSha512, expectedSha1, fileName, modsDir, shouldAbort);
            return bytes;
         } catch (AbortedException ae) {
            throw ae;
         } catch (PermanentHttpException pe) {
            if (i < MAX_ATTEMPTS - 1 && !attemptUrl.equals(url)) {
               continue;
            }
            throw pe;
         } catch (IOException e) {
            if (shouldAbort.getAsBoolean()) {
               throw new AbortedException(fileName);
            }
            if (errors.length() > 0) {
               errors.append(" | ");
            }
            errors.append(e.getMessage());
         }

         if (i < MAX_ATTEMPTS - 1) {
            long backoff = (i == 0) ? BACKOFF_MS_1 : BACKOFF_MS_2;
            try {
               Thread.sleep(backoff);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               if (shouldAbort.getAsBoolean()) {
                  throw new AbortedException(fileName);
               }
            }
         }
      }

      String msg = errors.length() > 0 ? errors.toString() : "all attempts failed";
      throw new IOException("MODRINTH: " + msg);
   }

   /** 旧 5 参重载：无 sha1 期望，保留二进制兼容。 */
   public static long downloadVerified(String url, String expectedSha512, String fileName, Path modsDir,
                                       BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      return downloadVerified(url, expectedSha512, null, fileName, modsDir, shouldAbort);
   }

   /**
    * 单次下载尝试：先写 .part，校验哈希后原子改名。
    * 取消监视：read() 阻塞时也能在 150ms 内通过 disconnect 掐断底层连接。
    */
   private static long doDownload(String url, String expectedSha512, String expectedSha1, String fileName, Path modsDir,
                                  BooleanSupplier shouldAbort) throws IOException, InterruptedException {
      ensureSafeFileName(fileName);

      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      final HttpURLConnection fConn = conn;
      conn.setConnectTimeout((int) Math.min(HEADER_TIMEOUT_MS, 15000L));
      conn.setReadTimeout(TIMEOUT_MS);
      conn.setInstanceFollowRedirects(true);
      conn.setRequestProperty("User-Agent", USER_AGENT);

      final AtomicBoolean done = new AtomicBoolean(false);
      Thread cancelWatch = new Thread(() -> {
         while (!done.get()) {
            if (shouldAbort.getAsBoolean()) {
               fConn.disconnect();
               return;
            }
            try {
               Thread.sleep(150L);
            } catch (InterruptedException ie) {
               return;
            }
         }
      }, "VoxLink-DL-CancelWatch");
      cancelWatch.setDaemon(true);
      cancelWatch.start();

      int code;
      try {
         code = conn.getResponseCode();
      } catch (IOException e) {
         done.set(true);
         cancelWatch.interrupt();
         conn.disconnect();
         throw e;
      }

      if (code == 429) {
         done.set(true);
         cancelWatch.interrupt();
         conn.disconnect();
         throw new IOException("HTTP 429 for " + fileName);
      }

      if (code / 100 == 4) {
         done.set(true);
         cancelWatch.interrupt();
         conn.disconnect();
         throw new PermanentHttpException("HTTP " + code + " for " + fileName);
      }

      if (code / 100 != 2) {
         done.set(true);
         cancelWatch.interrupt();
         conn.disconnect();
         throw new IOException("HTTP " + code + " for " + fileName);
      }

      String clen = conn.getHeaderField("Content-Length");
      if (clen != null) {
         try {
            long declared = Long.parseLong(clen.trim());
            if (declared > MAX_DOWNLOAD_BYTES) {
               done.set(true);
               cancelWatch.interrupt();
               conn.disconnect();
               throw new IOException("declared Content-Length " + declared + " exceeds limit for " + fileName);
            }
         } catch (NumberFormatException nfe) {
            // 解析失败忽略，按流式上限兜底
         }
      }

      Path target = modsDir.resolve(fileName);
      Path part = modsDir.resolve(fileName + "." + System.nanoTime() + ".part");
      MessageDigest sha512;
      MessageDigest sha1;
      try {
         sha512 = MessageDigest.getInstance("SHA-512");
         sha1 = MessageDigest.getInstance("SHA-1");
      } catch (Exception e) {
         done.set(true);
         cancelWatch.interrupt();
         conn.disconnect();
         throw new IOException("hash algorithm unavailable", e);
      }

      long total = 0L;
      boolean abortedFlag = false;
      IOException ioFailure = null;
      InputStream in = null;
      OutputStream out = null;
      try {
         in = conn.getInputStream();
         out = Files.newOutputStream(part);
         byte[] buf = new byte[64 * 1024];
         int n;
         while ((n = in.read(buf)) > 0) {
            if (shouldAbort.getAsBoolean()) {
               abortedFlag = true;
               break;
            }
            total += n;
            if (total > MAX_DOWNLOAD_BYTES) {
               throw new IOException("file too large: " + fileName);
            }
            sha512.update(buf, 0, n);
            sha1.update(buf, 0, n);
            out.write(buf, 0, n);
         }
      } catch (IOException e) {
         if (shouldAbort.getAsBoolean()) {
            abortedFlag = true;
         } else {
            ioFailure = e;
         }
      } finally {
         done.set(true);
         cancelWatch.interrupt();
         if (out != null) {
            try { out.close(); } catch (IOException ignored) {}
         }
         if (in != null) {
            try { in.close(); } catch (IOException ignored) {}
         }
         conn.disconnect();
      }

      if (abortedFlag || shouldAbort.getAsBoolean()) {
         quietDelete(part);
         throw new AbortedException(fileName);
      }

      if (ioFailure != null) {
         quietDelete(part);
         throw ioFailure;
      }

      // 校验：sha512 优先；缺失则回退到 sha1；都缺失放行（兼容老调用方）
      String actualSha512 = hex(sha512);
      String actualSha1 = hex(sha1);
      boolean sha512Wanted = expectedSha512 != null && !expectedSha512.trim().isEmpty();
      boolean sha1Wanted = expectedSha1 != null && !expectedSha1.trim().isEmpty();
      if (sha512Wanted) {
         if (!actualSha512.equalsIgnoreCase(expectedSha512)) {
            quietDelete(part);
            throw new IOException("sha512 mismatch: " + fileName);
         }
      } else if (sha1Wanted) {
         if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
            quietDelete(part);
            throw new IOException("sha1 mismatch: " + fileName);
         }
      } else {
         ModSyncLog.warn("no expected hash for {}; skipping verification", fileName);
      }

      // 原子改名：优先 ATOMIC_MOVE；失败短退避重试 3 次（杀软/占用锁瞬态）
      IOException lastMove = null;
      for (int mv = 0; mv < 3; mv++) {
         try {
            try {
               Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amns) {
               Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return total;
         } catch (IOException moveEx) {
            lastMove = moveEx;
            try {
               Thread.sleep(150L << mv);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               break;
            }
         }
      }

      quietDelete(part);
      throw lastMove != null ? lastMove : new IOException("move failed: " + fileName);
   }

   /** 用户主动取消专用异常：调用方按"静默回到选择界面"处理，不当失败刷红。 */
   public static final class AbortedException extends IOException {
      public AbortedException(String name) {
         super("aborted: " + name);
      }
   }

   /** 4xx（非 429）等"不要重试"的永久失败；外层重试循环识别后直接上抛。 */
   private static final class PermanentHttpException extends IOException {
      PermanentHttpException(String msg) {
         super(msg);
      }
   }

   /** 项目是否为"纯服务端"模组（房客无需安装）。 */
   public static boolean isServerOnly(JsonObject project) {
      if (project == null) {
         return false;
      }

      if (project.has("client_side") && !project.get("client_side").isJsonNull()) {
         return "unsupported".equalsIgnoreCase(project.get("client_side").getAsString());
      }

      if (project.has("environment") && project.get("environment").isJsonArray()) {
         for (JsonElement el : project.getAsJsonObject().getAsJsonArray("environment")) {
            String v = el.isJsonPrimitive() ? el.getAsString() : "";
            if ("server_only".equalsIgnoreCase(v)) {
               return true;
            }
         }
      }

      return false;
   }

   private static JsonObject postJson(List<String> urls, String json) throws IOException, InterruptedException {
      ModSyncHttp.Response resp = sendAny(urls, "Modrinth POST", true, json);
      if (resp.statusCode / 100 != 2) {
         throw new IOException("Modrinth POST failed: HTTP " + resp.statusCode);
      }
      JsonElement el = new JsonParser().parse(resp.body);
      return el.isJsonObject() ? el.getAsJsonObject() : null;
   }

   private static JsonArray getJsonArray(List<String> urls) throws IOException, InterruptedException {
      ModSyncHttp.Response resp = sendAny(urls, "Modrinth GET", false, null);
      if (resp.statusCode / 100 != 2) {
         throw new IOException("Modrinth GET failed: HTTP " + resp.statusCode);
      }
      JsonElement el = new JsonParser().parse(resp.body);
      return el.isJsonArray() ? el.getAsJsonArray() : null;
   }

   private static JsonObject getJsonObject(List<String> urls, boolean nullOn404) throws IOException, InterruptedException {
      ModSyncHttp.Response resp;
      try {
         resp = sendAny(urls, "Modrinth GET", false, null);
      } catch (IOException e) {
         if (nullOn404 && e.getMessage() != null && e.getMessage().endsWith("404")) {
            return null;
         }
         throw e;
      }

      if (resp.statusCode / 100 != 2) {
         throw new IOException("Modrinth GET failed: HTTP " + resp.statusCode);
      }

      JsonElement el = new JsonParser().parse(resp.body);
      return el.isJsonObject() ? el.getAsJsonObject() : null;
   }

   /**
    * 多源请求链：镜像短超时在前，官方兜底在后。
    * 429 按小退避换源；4xx/5xx/网络失败换下一源，仅最后一源的 4xx 判永久失败。
    */
   private static ModSyncHttp.Response sendAny(List<String> urls, String label, boolean post, String json) throws IOException, InterruptedException {
      IOException last = null;
      for (int i = 0; i < urls.size(); i++) {
         boolean isLast = i == urls.size() - 1;
         boolean mirror = urls.get(i).startsWith(MIRROR_PREFIX);
         int timeout = mirror ? MIRROR_TIMEOUT_MS : TIMEOUT_MS;
         try {
            ModSyncHttp.Response resp = post
               ? ModSyncHttp.postJson(urls.get(i), json, USER_AGENT, timeout)
               : ModSyncHttp.get(urls.get(i), USER_AGENT, timeout);
            int code = resp.statusCode;
            if (code == 429) {
               if (!isLast) {
                  Thread.sleep(Math.min(3L, resp.retryAfterSeconds) * 1000L);
                  continue;
               }
               throw new IOException(label + " HTTP 429 (exhausted)");
            }

            if (code / 100 == 4) {
               if (!isLast) {
                  continue;
               }
               throw new PermanentHttpException(label + " HTTP " + code);
            }

            if (code / 100 == 5) {
               if (!isLast) {
                  continue;
               }
               throw new IOException(label + " HTTP " + code);
            }

            return resp;
         } catch (PermanentHttpException pe) {
            throw pe;
         } catch (IOException ioe) {
            last = ioe;
            if (isLast) {
               throw ioe;
            }
         }
      }

      throw last != null ? last : new IOException(label + ": no endpoint answered");
   }

   /** 从 version JSON 取 primary 文件对象；无 primary 时取第一个。 */
   public static JsonObject primaryFile(JsonObject version) {
      return ModSyncEntry.primaryFile(version);
   }

   public static Set<String> distinctProjectIds(List<JsonObject> versions) {
      LinkedHashSet<String> ids = new LinkedHashSet<String>();
      for (JsonObject v : versions) {
         if (v.has("project_id") && !v.get("project_id").isJsonNull()) {
            ids.add(v.get("project_id").getAsString());
         }
      }
      return ids;
   }

   /** MR CDN / forgecdn → MCIM 镜像；其余原样返回（PCL 同款映射）。 */
   private static String mirrorDownloadUrl(String url) {
      if (url == null) {
         return url;
      }

      if (url.startsWith("https://cdn.modrinth.com/")) {
         return MIRROR_PREFIX + "/" + url.substring("https://cdn.modrinth.com/".length());
      }

      try {
         URI u = URI.create(url);
         String host = u.getHost() == null ? "" : u.getHost();
         boolean cfCdn = host.equals("edge.forgecdn.net") || host.equals("mediafilez.forgecdn.net")
            || host.equals("media.forgecdn.net") || host.endsWith("-service.overwolf.wtf");
         if (cfCdn) {
            return MIRROR_PREFIX + (u.getPath() == null ? "" : u.getPath())
               + (u.getQuery() == null || u.getQuery().isEmpty() ? "" : "?" + u.getQuery());
         }
      } catch (Exception ignored) {
      }

      return url;
   }

   private static String escape(String s) {
      return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
   }

   private static String hex(MessageDigest digest) {
      StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);
      for (byte b : digest.digest()) {
         sb.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
      }
      return sb.toString();
   }

   private static void quietDelete(Path p) {
      try {
         Files.deleteIfExists(p);
      } catch (IOException ignored) {
      }
   }

   /** 防御性 fileName 消毒：主要由调用方负责，这里兜一道防路径穿越/非法字符。 */
   private static void ensureSafeFileName(String fileName) throws IOException {
      if (fileName == null || fileName.trim().isEmpty()) {
         throw new IOException("unsafe fileName: empty");
      }

      if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")) {
         throw new IOException("unsafe fileName: " + fileName);
      }

      for (int i = 0; i < fileName.length(); i++) {
         char c = fileName.charAt(i);
         if (c < 0x20 || c == 0x7F) {
            throw new IOException("unsafe fileName (control char): " + fileName);
         }
      }
   }
}
