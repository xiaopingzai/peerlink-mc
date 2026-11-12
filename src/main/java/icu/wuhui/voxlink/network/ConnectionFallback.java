/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ConnectionFallback {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-fallback");
    private static final ExecutorService FALLBACK_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "VoxLink-Fallback");
        thread.setDaemon(true);
        return thread;
    });
    private static final int SOCKET_TIMEOUT = 3000;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 2000;
    private static final int TCP_SIMOPEN_WINDOW_MS = 10000;
    private static final int TCP_SIMOPEN_MAX_ATTEMPTS = 5;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final AtomicBoolean won = new AtomicBoolean(false);
    private volatile String statusText = "";
    private static volatile Boolean ipv6ConnectivityCached = null;
    private static volatile long ipv6ConnectivityCheckTime = 0L;
    private static final long IPV6_CHECK_CACHE_MS = 60000L;

    public static void shutdown() {
        FALLBACK_EXECUTOR.shutdown();
        try {
            if (!FALLBACK_EXECUTOR.awaitTermination(2L, TimeUnit.SECONDS)) {
                FALLBACK_EXECUTOR.shutdownNow();
            }
        }
        catch (InterruptedException interruptedException) {
            FALLBACK_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void cancel() {
        this.cancelled.set(true);
    }

    public String getStatusText() {
        return this.statusText;
    }

    public boolean isSettled() {
        return this.settled.get();
    }

    public boolean isCancelled() {
        return this.cancelled.get();
    }

    public CompletableFuture<ConnectResult> tryIpv6Direct(String string, int n) {
        if (string != null && !string.isEmpty()) {
            this.statusText = "voxlink.connection.probing";
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("Decompilation failed");
            }, FALLBACK_EXECUTOR);
        }
        return CompletableFuture.completedFuture(ConnectResult.failed("NO_IPV6", "\u6ca1\u6709IPv6\u5730\u5740"));
    }

    public CompletableFuture<ConnectResult> tryIpv4Direct(String string, int n) {
        if (string != null && !string.isEmpty()) {
            this.statusText = "voxlink.connection.probing";
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("Decompilation failed");
            }, FALLBACK_EXECUTOR);
        }
        return CompletableFuture.completedFuture(ConnectResult.failed("NO_IPV4", "\u6ca1\u6709IPv4\u5730\u5740"));
    }

    public static synchronized boolean verifyIPv6Connectivity() {
        long l = System.currentTimeMillis();
        if (ipv6ConnectivityCached != null && l - ipv6ConnectivityCheckTime < 60000L) {
            return ipv6ConnectivityCached;
        }
        if (!ConnectionFallback.hasIPv6Connectivity()) {
            ipv6ConnectivityCached = false;
            ipv6ConnectivityCheckTime = l;
            return false;
        }
        int n = 0;
        String[] stringArray = new String[]{"2001:4860:4860::8888", "2001:4860:4860::8844"};
        String[] stringArray2 = stringArray;
        int n2 = stringArray2.length;
        if (n < n2) {
            boolean bl = false;
            String string = stringArray2[n];
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(string, 53), 2000);
                LOGGER.info("[IPv6] Connectivity check success: {}", (Object)string);
                ipv6ConnectivityCached = true;
                ipv6ConnectivityCheckTime = l;
                bl = true;
            }
            catch (Throwable throwable) {
                try {
                    try {
                        socket.close();
                    }
                    catch (Throwable throwable2) {
                        throwable.addSuppressed(throwable2);
                    }
                    throw throwable;
                }
                catch (Exception exception) {
                    LOGGER.debug("[IPv6] Connectivity check failed {}: {}", (Object)string, (Object)exception.getMessage());
                    ++n;
                }
            }
            try {
                socket.close();
            }
            catch (IOException iOException) {
                // empty catch block
            }
            return bl;
        }
        LOGGER.info("[IPv6] Connectivity check failed: all targets unreachable");
        ipv6ConnectivityCached = false;
        ipv6ConnectivityCheckTime = l;
        return false;
    }

    public static boolean hasIPv6Connectivity() {
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface networkInterface = enumeration.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                Enumeration<InetAddress> enumeration2 = networkInterface.getInetAddresses();
                while (enumeration2.hasMoreElements()) {
                    InetAddress inetAddress = enumeration2.nextElement();
                    if (!(inetAddress instanceof Inet6Address)) continue;
                    Inet6Address inet6Address = (Inet6Address)inetAddress;
                    if (inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress()) continue;
                    String string = inetAddress.getHostAddress();
                    int n = string.indexOf(37);
                    if (n >= 0) {
                        string = string.substring(0, n);
                    }
                    LOGGER.debug("[IPv6] Found IPv6 addr: {} on interface {} (loopback={}, linkLocal={}, siteLocal={}, ULA={})", new Object[]{string, networkInterface.getName(), inetAddress.isLoopbackAddress(), inetAddress.isLinkLocalAddress(), inetAddress.isSiteLocalAddress(), string.startsWith("fd") || string.startsWith("fc")});
                    if (string.startsWith("fd") || string.startsWith("fc")) continue;
                    LOGGER.info("[IPv6] Usable global IPv6 found on {}", (Object)networkInterface.getName());
                    return true;
                }
            }
            LOGGER.info("[IPv6] No usable global IPv6 address found");
        }
        catch (Exception exception) {
            LOGGER.warn("[IPv6] Error checking IPv6: {}", (Object)exception.getMessage());
        }
        return false;
    }

    public static String getLocalGlobalIpv6() {
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface networkInterface = enumeration.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                Enumeration<InetAddress> enumeration2 = networkInterface.getInetAddresses();
                while (enumeration2.hasMoreElements()) {
                    InetAddress inetAddress = enumeration2.nextElement();
                    if (!(inetAddress instanceof Inet6Address) || inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress()) continue;
                    String string = inetAddress.getHostAddress();
                    int n = string.indexOf(37);
                    if (n >= 0) {
                        string = string.substring(0, n);
                    }
                    if (string.startsWith("fd") || string.startsWith("fc")) continue;
                    return string;
                }
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return null;
    }

    public CompletableFuture<ConnectResult> tryTcpSimultaneousOpen(String string, int n, int n2) {
        if (string != null && !string.isEmpty()) {
            this.statusText = "voxlink.connection.probing";
            return CompletableFuture.supplyAsync(() -> {
                if (!(this.won.get() || this.cancelled.get() || this.settled.get())) {
                    InetAddress inetAddress;
                    try {
                        inetAddress = InetAddress.getByName(string);
                    }
                    catch (Exception exception) {
                        return ConnectResult.failed("TCP_SIMOPEN_FAILED", "Bad addr: " + exception.getMessage());
                    }
                    long l = System.currentTimeMillis() + 10000L;
                    int n3 = 0;
                    Random random = new Random();
                    boolean bl = false;
                    while (!(System.currentTimeMillis() >= l || n3 >= 5 || this.won.get() || this.cancelled.get() || this.settled.get())) {
                        ++n3;
                        Socket socket = null;
                        try {
                            socket = new Socket();
                            socket.setTcpNoDelay(true);
                            socket.setReuseAddress(true);
                            try {
                                socket.bind(new InetSocketAddress(bl ? 0 : n2));
                            }
                            catch (IOException iOException) {
                                if (!bl) {
                                    bl = true;
                                    LOGGER.info("TCP SimOpen: local port {} occupied, fallback to random port", (Object)n2);
                                    if (socket == null) continue;
                                    try {
                                        socket.close();
                                    }
                                    catch (Exception exception) {}
                                    continue;
                                }
                                LOGGER.info("TCP SimOpen: local port {} occupied, SimOpen will fail", (Object)n2);
                                if (socket != null) {
                                    try {
                                        socket.close();
                                    }
                                    catch (Exception exception) {
                                        // empty catch block
                                    }
                                }
                                return ConnectResult.failed("TCP_SIMOPEN_BIND_FAIL", "\u672c\u5730\u7aef\u53e3" + n2 + "\u88ab\u5360");
                            }
                            LOGGER.info("TCP SimOpen: attempt {}/{}, from port {} to {}:{}", new Object[]{n3, 5, socket.getLocalPort(), string, n});
                            socket.connect(new InetSocketAddress(inetAddress, n), 3000);
                            LOGGER.info("TCP SimOpen: attempt {} connected {}:{}", new Object[]{n3, string, n});
                            this.statusText = "voxlink.connection.bridge_setup";
                            if (!this.won.compareAndSet(false, true)) {
                                if (socket != null) {
                                    try {
                                        socket.close();
                                    }
                                    catch (Exception exception) {
                                        // empty catch block
                                    }
                                }
                                return ConnectResult.cancelled();
                            }
                            this.settled.set(true);
                            socket.close();
                            return ConnectResult.success("TCP-SimOpen", string, n, ConnectionMode.IPV4_DIRECT);
                        }
                        catch (Exception exception) {
                            LOGGER.info("TCP SimOpen attempt {} failed: {}:{} - {}", new Object[]{n3, string, n, exception.getMessage()});
                            if (!bl && exception instanceof IOException && String.valueOf(exception.getMessage()).contains("Cannot assign requested address")) {
                                bl = true;
                            }
                            if (socket != null) {
                                try {
                                    socket.close();
                                }
                                catch (Exception exception2) {
                                    // empty catch block
                                }
                            }
                            if (this.won.get() || this.cancelled.get() || this.settled.get() || System.currentTimeMillis() >= l || n3 >= 5) break;
                            try {
                                Thread.sleep(10 + random.nextInt(90));
                            }
                            catch (InterruptedException interruptedException) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    this.statusText = "voxlink.connection.failed";
                    return ConnectResult.failed("TCP_SIMOPEN_FAILED", "TCP SimOpen failed after " + n3 + " attempts");
                }
                return ConnectResult.cancelled();
            }, FALLBACK_EXECUTOR);
        }
        return CompletableFuture.completedFuture(ConnectResult.failed("NO_IP", "No remote IP"));
    }

    public static enum ConnectionMode {
        IPV6_DIRECT("voxlink.mode.ipv6_direct"),
        IPV4_DIRECT("voxlink.mode.ipv4_direct"),
        UDP_PUNCH("voxlink.mode.udp_punch");

        public final String translationKey;

        private ConnectionMode(String string2) {
            this.translationKey = string2;
        }
    }

    public static class ConnectResult {
        public final boolean success;
        public final String failureReason;
        public final String errorCode;
        public final String label;
        public final String remoteHost;
        public final int remotePort;
        public final ConnectionMode mode;

        private ConnectResult(boolean bl, String string, String string2, String string3, String string4, int n, ConnectionMode connectionMode) {
            this.success = bl;
            this.errorCode = string;
            this.failureReason = string2;
            this.label = string3;
            this.remoteHost = string4;
            this.remotePort = n;
            this.mode = connectionMode;
        }

        public static ConnectResult success(String string, String string2, int n, ConnectionMode connectionMode) {
            return new ConnectResult(true, null, null, string, string2, n, connectionMode);
        }

        public static ConnectResult failed(String string, String string2) {
            return new ConnectResult(false, string, string2, null, null, 0, null);
        }

        public static ConnectResult cancelled() {
            return new ConnectResult(false, "CANCELLED", "Connection cancelled", null, null, 0, null);
        }
    }
}

