package icu.wuhui.voxlink.modsync;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import javax.imageio.ImageIO;

/**
 * MR 模组图标缓存：后台下载/解码 → 16x16 像素横段（graphics.fill 渲染，
 * 零纹理 API 依赖，全版本模板同构）。磁盘缓存 mods/voxlink/icon_cache/，
 * 内存 LRU；失败会话内不重试，界面回退色块图标。
 */
public final class ModSyncIconCache {
   private static final int SIZE = 16;
   private static final long MAX_BYTES = 1024L * 1024;
   private static final int MAX_MEM = 192;
   private static final Object LOCK = new Object();
   /** url → 渲染段 {x, y, 宽, argb}，访问序 LRU。 */
   private static final Map<String, int[][]> RUNS = new LinkedHashMap<String, int[][]>(64, 0.75f, true);
   private static final Set<String> INFLIGHT = new HashSet<String>();
   private static final Set<String> FAILED = new HashSet<String>();
   private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<String>();
   private static volatile Thread worker;

   private ModSyncIconCache() {
   }

   /** 请求加载（幂等）；渲染线程每帧可安全调用。 */
   public static void prefetch(String url) {
      if (isBlank(url)) {
         return;
      }

      synchronized (LOCK) {
         if (RUNS.containsKey(url) || INFLIGHT.contains(url) || FAILED.contains(url)) {
            return;
         }

         INFLIGHT.add(url);
      }

      QUEUE.offer(url);
      ensureWorker();
   }

   /** 已就绪返回像素段，未就绪/失败返回 null。 */
   public static int[][] runs(String url) {
      if (isBlank(url)) {
         return null;
      }

      synchronized (LOCK) {
         return RUNS.get(url);
      }
   }

   private static void ensureWorker() {
      Thread t = worker;
      if (t != null && t.isAlive()) {
         return;
      }

      synchronized (ModSyncIconCache.class) {
         t = worker;
         if (t == null || !t.isAlive()) {
            t = new Thread(ModSyncIconCache::drainLoop, "VoxLink-ModSync-Icon");
            t.setDaemon(true);
            worker = t;
            t.start();
         }
      }
   }

   private static void drainLoop() {
      while (true) {
         try {
            load(QUEUE.take());
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
         } catch (Throwable t) {
            ModSyncLog.warn("icon worker: {}", t.toString());
         }
      }
   }

   private static void load(String url) {
      try {
         byte[] data = readOrFetch(url);
         BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
         if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            throw new IOException("undecodable icon");
         }

         int[][] runs = toRuns(downscale(img));
         synchronized (LOCK) {
            INFLIGHT.remove(url);
            FAILED.remove(url);
            RUNS.put(url, runs);
            trimLocked();
         }
      } catch (Throwable t) {
         synchronized (LOCK) {
            INFLIGHT.remove(url);
            FAILED.add(url);
         }

         ModSyncLog.warn("icon load failed: {}", t.toString());
      }
   }

   private static byte[] readOrFetch(String url) throws Exception {
      Path cache = cacheFileFor(url);
      if (Files.isRegularFile(cache)) {
         try {
            byte[] d = Files.readAllBytes(cache);
            if (d.length > 0 && d.length <= MAX_BYTES) {
               return d;
            }
         } catch (Exception ignored) {
         }
      }

      Exception last = null;
      for (String u : mirrorChain(url)) {
         try {
            byte[] body = fetch(u);
            try {
               Files.createDirectories(cache.getParent());
               Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
               Files.write(tmp, body);
               Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
            }

            return body;
         } catch (Exception e) {
            last = e;
         }
      }

      throw last != null ? last : new IOException("icon fetch failed");
   }

   /** 下载链：MR CDN/forgecdn 先走 MCIM 镜像（PCL 同款），失败回源站。 */
   private static List<String> mirrorChain(String url) {
      String mapped = url;
      try {
         URI u = URI.create(url);
         String host = u.getHost() == null ? "" : u.getHost();
         if (host.equals("cdn.modrinth.com") || host.endsWith(".forgecdn.net") || host.equals("forgecdn.net")
            || host.endsWith("-service.overwolf.wtf")) {
            mapped = "https://mod.mcimirror.top" + (u.getPath() == null ? "" : u.getPath())
               + (u.getQuery() == null || u.getQuery().isEmpty() ? "" : "?" + u.getQuery());
         }
      } catch (Exception ignored) {
      }

      return mapped.equals(url) ? Collections.singletonList(url) : Arrays.asList(mapped, url);
   }

   private static byte[] fetch(String url) throws Exception {
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      try {
         conn.setConnectTimeout(10000);
         conn.setReadTimeout(15000);
         conn.setInstanceFollowRedirects(true);
         conn.setRequestProperty("User-Agent", "VoxLink/modsync");
         int code = conn.getResponseCode();
         if (code / 100 != 2) {
            throw new IOException("HTTP " + code);
         }

         InputStream in = conn.getInputStream();
         ByteArrayOutputStream buf = new ByteArrayOutputStream();
         byte[] chunk = new byte[8192];
         int n;
         while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) {
               throw new IOException("bad icon size");
            }
         }
         byte[] body = buf.toByteArray();
         if (body.length == 0 || body.length > MAX_BYTES) {
            throw new IOException("bad icon size");
         }
         return body;
      } finally {
         conn.disconnect();
      }
   }

   private static Path cacheFileFor(String url) {
      String name = ModSyncFileHasher.sha1OfBytes(url.getBytes(StandardCharsets.UTF_8)) + ".img";
      return ModSyncEnv.getModsDir().resolve("voxlink").resolve("icon_cache").resolve(name);
   }

   /** 源图盒采样缩到 SIZE×SIZE（预乘 alpha 平均，避免透边缘发黑）。 */
   private static int[][] downscale(BufferedImage img) {
      int w = img.getWidth();
      int h = img.getHeight();
      int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
      int[][] out = new int[SIZE][SIZE];
      for (int ty = 0; ty < SIZE; ty++) {
         for (int tx = 0; tx < SIZE; tx++) {
            int x0 = tx * w / SIZE;
            int x1 = Math.max(x0 + 1, (tx + 1) * w / SIZE);
            int y0 = ty * h / SIZE;
            int y1 = Math.max(y0 + 1, (ty + 1) * h / SIZE);
            long a = 0L;
            long r = 0L;
            long g = 0L;
            long b = 0L;
            long n = 0L;
            for (int y = y0; y < y1 && y < h; y++) {
               for (int x = x0; x < x1 && x < w; x++) {
                  int c = argb[y * w + x];
                  int ca = (c >>> 24) & 0xFF;
                  a += ca;
                  r += ((c >>> 16) & 0xFF) * (long) ca;
                  g += ((c >>> 8) & 0xFF) * (long) ca;
                  b += (c & 0xFF) * (long) ca;
                  n++;
               }
            }

            if (n == 0L || a == 0L) {
               out[ty][tx] = 0;
               continue;
            }

            int aa = (int) (a / n);
            if (aa < 8) {
               out[ty][tx] = 0;
               continue;
            }

            int rr = (int) (r / a);
            int gg = (int) (g / a);
            int bb = (int) (b / a);
            out[ty][tx] = ((aa & 0xFF) << 24) | ((rr & 0xFF) << 16) | ((gg & 0xFF) << 8) | (bb & 0xFF);
         }
      }

      return out;
   }

   /** 像素 → 同色横段列表，渲染时每段一次 fill。 */
   private static int[][] toRuns(int[][] px) {
      List<int[]> list = new ArrayList<int[]>(SIZE * 4);
      for (int y = 0; y < SIZE; y++) {
         int x = 0;
         while (x < SIZE) {
            int c = px[y][x];
            if (c == 0) {
               x++;
               continue;
            }

            int x0 = x;
            while (x < SIZE && px[y][x] == c) {
               x++;
            }

            list.add(new int[]{x0, y, x - x0, c});
         }
      }

      return list.toArray(new int[0][]);
   }

   private static void trimLocked() {
      if (RUNS.size() <= MAX_MEM) {
         return;
      }

      Iterator<Map.Entry<String, int[][]>> it = RUNS.entrySet().iterator();
      while (it.hasNext() && RUNS.size() > MAX_MEM) {
         it.next();
         it.remove();
      }
   }

   private static boolean isBlank(String s) {
      return s == null || s.trim().isEmpty();
   }
}
