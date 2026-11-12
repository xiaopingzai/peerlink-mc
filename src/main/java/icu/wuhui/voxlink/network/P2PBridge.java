/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.integrated.IntegratedServer
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.hook.ViaForgeHook;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class P2PBridge {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-p2p");
    private static final int BUFFER_SIZE = 32768;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int MAX_RETRY = 3;
    private static final int PUNCH_SOCKET_TIMEOUT_MS = 1000;
    private static final int JOINER_ACCEPT_TIMEOUT_MS = 30000;
    private static final int KEEPALIVE_SOCKET_TIMEOUT_MS = 60000;
    private static final int MAX_THREADS = 16;
    private static final long KEEPALIVE_TIME_SEC = 60L;
    private static final int AWAIT_SEC = 3;
    private static final int AWAIT_FINAL_SEC = 1;
    private static final int RETRY_DELAY_MS = 500;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);
    private static volatile ExecutorService bridgeExecutor;
    private static volatile ServerSocket hostServer;
    private static volatile ServerSocket joinerServer;
    private static volatile int joinerPort;
    private static volatile int hostPort;
    private static final List<BridgePair> activePairs;
    private static final CopyOnWriteArrayList<ScheduledFuture<?>> pendingUdpTimeouts;
    private static volatile String currentHostIp;
    private static volatile int currentHostPort;
    private static volatile boolean trafficDetected;
    private static volatile long firstPacketDeadlineMs;
    private static final long LAZY_P2P_TIMEOUT_MS = 5000L;
    private static final AtomicBoolean tcpJoinerBridgeConnectedV4;
    private static final AtomicBoolean tcpJoinerBridgeConnectedV6;
    private static final CopyOnWriteArrayList<ReliableUdpTransport> activeUdpTransports;
    private static final AtomicBoolean currentJoinerMcSocketLock;
    private static volatile Socket currentJoinerMcSocket;
    private static final AtomicBoolean joinerBridgeConnected;

    public static synchronized CompletableFuture<Integer> startHostBridge(int n) {
        ViaForgeHook.setupLocalTranslation();
        cancelled.set(false);
        tcpJoinerBridgeConnectedV4.set(false);
        tcpJoinerBridgeConnectedV6.set(false);
        if (running.get()) {
            ServerSocket serverSocket = hostServer;
            if (serverSocket != null && !serverSocket.isClosed()) {
                return CompletableFuture.completedFuture(serverSocket.getLocalPort());
            }
            P2PBridge.disconnect();
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
                int n2 = serverSocket.getLocalPort();
                LOGGER.info("Host bridge listening on port {}, forwarding to MC port {}", (Object)n2, (Object)n);
                ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                executorService.submit(() -> P2PBridge.acceptHostConnections(serverSocket, n));
                Class<P2PBridge> clazz = P2PBridge.class;
                Class<P2PBridge> clazz2 = P2PBridge.class;
                synchronized (P2PBridge.class) {
                    running.set(true);
                    hostServer = serverSocket;
                    hostPort = n2;
                    // ** MonitorExit[var5_6] (shouldn't be in output)
                    return n2;
                }
            }
            catch (IOException iOException) {
                LOGGER.error("Failed to start host bridge: {}", (Object)iOException.getMessage());
                running.set(false);
                return -1;
            }
        }, P2PBridge.getOrCreateExecutor());
    }

    private static void acceptHostConnections(ServerSocket serverSocket, int n) {
        try {
            serverSocket.setSoTimeout(1000);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        while (running.get()) {
            Socket socket = null;
            try {
                try {
                    socket = serverSocket.accept();
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(32768);
                socket.setReceiveBufferSize(32768);
                LOGGER.info("P2P client connected from {}", (Object)socket.getRemoteSocketAddress());
                Socket socket2 = new Socket();
                socket2.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), n), 10000);
                socket2.setTcpNoDelay(true);
                socket2.setSendBufferSize(32768);
                socket2.setReceiveBufferSize(32768);
                BridgePair bridgePair = new BridgePair(socket, socket2);
                activePairs.add(bridgePair);
                ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.client, bridgePair.mc, "P2P->MC"));
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.mc, bridgePair.client, "MC->P2P"));
            }
            catch (IOException iOException) {
                if (socket != null) {
                    try {
                        socket.close();
                    }
                    catch (IOException iOException2) {
                        // empty catch block
                    }
                }
                if (!running.get()) continue;
                LOGGER.error("Error accepting P2P connection: {}", (Object)iOException.getMessage());
            }
        }
    }

    public static synchronized CompletableFuture<Integer> connectToHost(String string, int n) {
        cancelled.set(false);
        tcpJoinerBridgeConnectedV4.set(false);
        if (P2PBridge.isRunning()) {
            int n2 = joinerPort;
            if (n2 > 0) {
                return CompletableFuture.completedFuture(n2);
            }
            P2PBridge.disconnect();
        }
        if (string != null && string.contains(":")) {
            try {
                InetAddress.getByName(string);
            }
            catch (Exception exception) {
                LOGGER.warn("IPv6 address pre-resolution failed for {}: {}", (Object)string, (Object)exception.getMessage());
            }
        }
        return P2PBridge.connectToHostWithRetry(string, n, 0);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static CompletableFuture<Integer> connectToHostWithRetry(String string, int n, int n2) {
        if (cancelled.get()) {
            return CompletableFuture.completedFuture(-1);
        }
        Class<P2PBridge> clazz = P2PBridge.class;
        Class<P2PBridge> clazz2 = P2PBridge.class;
        synchronized (P2PBridge.class) {
            int n4;
            if (P2PBridge.isRunning() && (n4 = joinerPort) > 0) {
                // ** MonitorExit[var4_4] (shouldn't be in output)
                return CompletableFuture.completedFuture(n4);
            }
            running.set(true);
            // ** MonitorExit[var4_4] (shouldn't be in output)
            return CompletableFuture.supplyAsync(() -> {
                if (cancelled.get()) {
                    return -1;
                }
                try {
                    LOGGER.info("Joiner: connecting to host {}:{} (attempt {}/{})", new Object[]{string, n, n2 + 1, 4});
                    Socket socket = P2PBridge.tryConnectWithRetry(string, n);
                    if (socket == null) {
                        LOGGER.error("Joiner: failed to connect to host {}:{}", (Object)string, (Object)n);
                        running.set(false);
                        return -1;
                    }
                    LOGGER.info("Joiner: connected to host {}:{}", (Object)string, (Object)n);
                    ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                    int n3 = serverSocket.getLocalPort();
                    Class<P2PBridge> clazz = P2PBridge.class;
                    Class<P2PBridge> clazz2 = P2PBridge.class;
                    synchronized (P2PBridge.class) {
                        joinerServer = serverSocket;
                        joinerPort = n3;
                        currentHostIp = string;
                        currentHostPort = n;
                        LOGGER.info("Joiner: local bridge on port {}, tunneling to host {}:{}", new Object[]{n3, string, n});
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(32768);
                        socket.setReceiveBufferSize(32768);
                        ServerSocket serverSocket2 = serverSocket;
                        ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                        executorService.submit(() -> P2PBridge.acceptJoinerConnectionPreconnected(serverSocket2, socket));
                        // ** MonitorExit[var7_8] (shouldn't be in output)
                        return n3;
                    }
                }
                catch (IOException iOException) {
                    LOGGER.error("Joiner: failed to create local bridge (attempt {}/{}): {}", new Object[]{n2 + 1, 4, iOException.getMessage()});
                    running.set(false);
                    return -1;
                }
            }, P2PBridge.getOrCreateExecutor()).thenCompose(n3 -> {
                if (n3 <= 0 && n2 < 3 && !cancelled.get()) {
                    CompletableFuture completableFuture = new CompletableFuture();
                    Thread thread = new Thread(() -> {
                        if (cancelled.get()) {
                            completableFuture.complete(-1);
                        } else {
                            try {
                                Thread.sleep(1000L * (long)(n2 + 1));
                                if (cancelled.get()) {
                                    completableFuture.complete(-1);
                                    return;
                                }
                                P2PBridge.connectToHostWithRetry(string, n, n2 + 1).whenComplete((n, throwable) -> {
                                    if (throwable != null) {
                                        completableFuture.completeExceptionally((Throwable)throwable);
                                    } else {
                                        completableFuture.complete(n);
                                    }
                                });
                            }
                            catch (InterruptedException interruptedException) {
                                completableFuture.complete(-1);
                            }
                        }
                    }, "VoxLink-Retry");
                    thread.setDaemon(true);
                    thread.start();
                    return completableFuture;
                }
                return CompletableFuture.completedFuture(n3);
            });
        }
    }

    public static synchronized CompletableFuture<Integer> connectToHostIpv6(String string, int n) {
        cancelled.set(false);
        tcpJoinerBridgeConnectedV6.set(false);
        if (P2PBridge.isRunning()) {
            int n2 = joinerPort;
            if (n2 > 0) {
                return CompletableFuture.completedFuture(n2);
            }
            P2PBridge.disconnect();
        }
        return P2PBridge.connectToHostIpv6WithRetry(string, n, 0);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static CompletableFuture<Integer> connectToHostIpv6WithRetry(String string, int n, int n2) {
        if (cancelled.get()) {
            return CompletableFuture.completedFuture(-1);
        }
        Class<P2PBridge> clazz = P2PBridge.class;
        Class<P2PBridge> clazz2 = P2PBridge.class;
        synchronized (P2PBridge.class) {
            int n4;
            if (P2PBridge.isRunning() && (n4 = joinerPort) > 0) {
                // ** MonitorExit[var4_4] (shouldn't be in output)
                return CompletableFuture.completedFuture(n4);
            }
            running.set(true);
            // ** MonitorExit[var4_4] (shouldn't be in output)
            return CompletableFuture.supplyAsync(() -> {
                if (cancelled.get()) {
                    return -1;
                }
                try {
                    LOGGER.info("Joiner: connecting to host [{}]:{} (attempt {}/{})", new Object[]{string, n, n2 + 1, 4});
                    Socket socket = P2PBridge.tryConnectWithRetry(string, n);
                    if (socket == null) {
                        LOGGER.error("Joiner: failed to connect to host [{}]:{}", (Object)string, (Object)n);
                        running.set(false);
                        return -1;
                    }
                    LOGGER.info("Joiner: connected to host [{}]:{}", (Object)string, (Object)n);
                    ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                    int n3 = serverSocket.getLocalPort();
                    Class<P2PBridge> clazz = P2PBridge.class;
                    Class<P2PBridge> clazz2 = P2PBridge.class;
                    synchronized (P2PBridge.class) {
                        joinerServer = serverSocket;
                        joinerPort = n3;
                        currentHostIp = string;
                        currentHostPort = n;
                        LOGGER.info("Joiner: local bridge on port {}, tunneling to host [{}]:{}", new Object[]{n3, string, n});
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(32768);
                        socket.setReceiveBufferSize(32768);
                        ServerSocket serverSocket2 = serverSocket;
                        ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                        executorService.submit(() -> P2PBridge.acceptJoinerConnectionPreconnected(serverSocket2, socket));
                        // ** MonitorExit[var7_8] (shouldn't be in output)
                        return n3;
                    }
                }
                catch (IOException iOException) {
                    LOGGER.error("Joiner: failed to create IPv6 local bridge (attempt {}/{}): {}", new Object[]{n2 + 1, 4, iOException.getMessage()});
                    running.set(false);
                    return -1;
                }
            }, P2PBridge.getOrCreateExecutor()).thenCompose(n3 -> {
                if (n3 <= 0 && n2 < 3 && !cancelled.get()) {
                    CompletableFuture completableFuture = new CompletableFuture();
                    Thread thread = new Thread(() -> {
                        if (cancelled.get()) {
                            completableFuture.complete(-1);
                        } else {
                            try {
                                Thread.sleep(1000L * (long)(n2 + 1));
                                if (cancelled.get()) {
                                    completableFuture.complete(-1);
                                    return;
                                }
                                P2PBridge.connectToHostIpv6WithRetry(string, n, n2 + 1).whenComplete((n, throwable) -> {
                                    if (throwable != null) {
                                        completableFuture.completeExceptionally((Throwable)throwable);
                                    } else {
                                        completableFuture.complete(n);
                                    }
                                });
                            }
                            catch (InterruptedException interruptedException) {
                                completableFuture.complete(-1);
                            }
                        }
                    }, "VoxLink-Retry6");
                    thread.setDaemon(true);
                    thread.start();
                    return completableFuture;
                }
                return CompletableFuture.completedFuture(n3);
            });
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void acceptJoinerConnectionPreconnected(ServerSocket serverSocket, Socket socket) {
        Object object;
        Object object2;
        try {
            serverSocket.setSoTimeout(10000);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        boolean bl = true;
        try {
            while (running.get()) {
                Socket socket2;
                if (serverSocket.isClosed()) {
                    return;
                }
                try {
                    socket2 = serverSocket.accept();
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                socket2.setTcpNoDelay(true);
                socket2.setSendBufferSize(32768);
                socket2.setReceiveBufferSize(32768);
                LOGGER.info("Joiner: MC client connected to local bridge (pre-connected)");
                object2 = null;
                if (bl && socket != null && !socket.isClosed()) {
                    object2 = socket;
                    bl = false;
                } else {
                    object2 = P2PBridge.tryConnectWithRetry(currentHostIp, currentHostPort);
                }
                if (object2 == null) {
                    LOGGER.error("Joiner: failed to establish host channel for MC client");
                    try {
                        socket2.close();
                    }
                    catch (IOException iOException) {}
                    continue;
                }
                object = ((Socket)object2).getInetAddress() instanceof Inet6Address ? "MC->Host(IPv6)" : "MC->Host";
                String string = ((Socket)object2).getInetAddress() instanceof Inet6Address ? "Host(IPv6)->MC" : "Host->MC";
                BridgePair bridgePair = new BridgePair(socket2, (Socket)object2);
                activePairs.add(bridgePair);
                ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                executorService.submit(() -> P2PBridge.lambda$acceptJoinerConnectionPreconnected$14(bridgePair, (String)object));
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.mc, bridgePair.client, string));
            }
            return;
        }
        catch (IOException iOException) {
            if (running.get()) {
                LOGGER.error("Joiner: error accepting MC connection: {}", (Object)iOException.getMessage());
            }
            if (socket == null) {
                return;
            }
            if (socket.isClosed()) {
                return;
            }
            socket.close();
            return;
        }
        finally {
            try {
                serverSocket.close();
            }
            catch (IOException iOException) {}
            object2 = P2PBridge.class;
            object = P2PBridge.class;
            synchronized (P2PBridge.class) {
                if (joinerServer == serverSocket) {
                    joinerServer = null;
                    joinerPort = -1;
                }
                // ** MonitorExit[var5_14] (shouldn't be in output)
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void acceptJoinerConnections(ServerSocket serverSocket) {
        Object object;
        Object object2;
        try {
            serverSocket.setSoTimeout(1000);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        while (running.get() && !serverSocket.isClosed() && !tcpJoinerBridgeConnectedV4.get()) {
            try {
                try {
                    object2 = serverSocket.accept();
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                ((Socket)object2).setTcpNoDelay(true);
                ((Socket)object2).setSendBufferSize(32768);
                ((Socket)object2).setReceiveBufferSize(32768);
                LOGGER.info("Joiner: MC client connected to local bridge");
                object = P2PBridge.tryConnectWithRetry(currentHostIp, currentHostPort);
                if (object == null) {
                    LOGGER.error("Joiner: failed to connect to host {}:{}", (Object)currentHostIp, (Object)currentHostPort);
                    try {
                        ((Socket)object2).close();
                    }
                    catch (IOException iOException) {}
                    continue;
                }
                tcpJoinerBridgeConnectedV4.set(true);
                BridgePair bridgePair = new BridgePair((Socket)object2, (Socket)object);
                activePairs.add(bridgePair);
                ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.client, bridgePair.mc, "MC->Host"));
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.mc, bridgePair.client, "Host->MC"));
            }
            catch (IOException iOException) {
                if (!running.get()) continue;
                LOGGER.error("Joiner: error accepting MC connection: {}", (Object)iOException.getMessage());
            }
        }
        tcpJoinerBridgeConnectedV4.set(false);
        try {
            serverSocket.close();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        object2 = P2PBridge.class;
        object = P2PBridge.class;
        synchronized (P2PBridge.class) {
            if (joinerServer == serverSocket) {
                joinerServer = null;
                joinerPort = -1;
            }
            // ** MonitorExit[var2_5] (shouldn't be in output)
            return;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void acceptJoinerConnectionsIpv6(ServerSocket serverSocket, String string, int n) {
        Object object;
        Object object2;
        try {
            serverSocket.setSoTimeout(1000);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        while (running.get() && !serverSocket.isClosed() && !tcpJoinerBridgeConnectedV6.get()) {
            try {
                try {
                    object2 = serverSocket.accept();
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                ((Socket)object2).setTcpNoDelay(true);
                ((Socket)object2).setSendBufferSize(32768);
                ((Socket)object2).setReceiveBufferSize(32768);
                LOGGER.info("Joiner: MC client connected to IPv6 local bridge");
                object = P2PBridge.tryConnectWithRetry(string, n);
                if (object == null) {
                    LOGGER.error("Joiner: failed to connect to host [{}]:{}", (Object)string, (Object)n);
                    try {
                        ((Socket)object2).close();
                    }
                    catch (IOException iOException) {}
                    continue;
                }
                tcpJoinerBridgeConnectedV6.set(true);
                BridgePair bridgePair = new BridgePair((Socket)object2, (Socket)object);
                activePairs.add(bridgePair);
                ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.client, bridgePair.mc, "MC->Host(IPv6)"));
                executorService.submit(() -> P2PBridge.bridge(bridgePair, bridgePair.mc, bridgePair.client, "Host(IPv6)->MC"));
            }
            catch (IOException iOException) {
                if (!running.get()) continue;
                LOGGER.error("Joiner: error accepting MC connection (IPv6): {}", (Object)iOException.getMessage());
            }
        }
        tcpJoinerBridgeConnectedV6.set(false);
        try {
            serverSocket.close();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        object2 = P2PBridge.class;
        object = P2PBridge.class;
        synchronized (P2PBridge.class) {
            if (joinerServer == serverSocket) {
                joinerServer = null;
                joinerPort = -1;
            }
            // ** MonitorExit[var4_7] (shouldn't be in output)
            return;
        }
    }

    private static Socket tryConnectWithRetry(String string, int n) {
        if (string == null) {
            return null;
        }
        for (int i = 1; i <= 3; ++i) {
            Socket socket = null;
            try {
                InetSocketAddress inetSocketAddress;
                LOGGER.info("Joiner: connecting to {}:{} (attempt {}/{})", new Object[]{string, n, i, 3});
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(32768);
                socket.setReceiveBufferSize(32768);
                String string2 = string;
                if (string.contains(":")) {
                    if (string.startsWith("[") && string.contains("]")) {
                        int n2 = string.indexOf(93);
                        string2 = string.substring(1, n2);
                    }
                    inetSocketAddress = new InetSocketAddress(InetAddress.getByName(string2), n);
                } else {
                    inetSocketAddress = new InetSocketAddress(string, n);
                }
                socket.connect(inetSocketAddress, 10000);
                LOGGER.info("Joiner: connected to {}:{}", (Object)string, (Object)n);
                return socket;
            }
            catch (IOException iOException) {
                if (socket != null) {
                    try {
                        socket.close();
                    }
                    catch (IOException iOException2) {
                        // empty catch block
                    }
                }
                LOGGER.warn("Joiner: connect attempt {}/{} failed for {}:{} - {}", new Object[]{i, 3, string, n, iOException.getMessage()});
                if (i >= 3) continue;
                try {
                    Thread.sleep(500 * i);
                    continue;
                }
                catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void bridge(BridgePair bridgePair, Socket socket, Socket socket2, String string) {
        LOGGER.info("[Bridge] {} started (from={}, to={})", new Object[]{string, socket.getRemoteSocketAddress(), socket2.getRemoteSocketAddress()});
        try {
            socket.setSoTimeout(60000);
            socket.setKeepAlive(true);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        try (InputStream inputStream = socket.getInputStream();
             OutputStream outputStream = socket2.getOutputStream();){
            byte[] byArray = new byte[32768];
            boolean bl = true;
            boolean bl2 = false;
            while (running.get() && !socket.isClosed() && !socket2.isClosed()) {
                int n;
                try {
                    n = inputStream.read(byArray);
                    if (n == -1) break;
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                if (bl) {
                    LOGGER.info("[Bridge] {} first data: {} bytes", (Object)string, (Object)n);
                    P2PBridge.notifyTrafficDetected();
                    bl = false;
                }
                outputStream.write(byArray, 0, n);
                outputStream.flush();
            }
        }
        catch (IOException iOException) {
            if (running.get()) {
                LOGGER.info("Bridge {} closed: {}", (Object)string, (Object)iOException.getMessage());
            }
        }
        finally {
            bridgePair.close();
            activePairs.remove(bridgePair);
        }
    }

    public static void registerPendingUdpTimeout(ScheduledFuture<?> scheduledFuture) {
        if (scheduledFuture != null) {
            pendingUdpTimeouts.add(scheduledFuture);
        }
    }

    public static void cancelPendingUdpTimeouts() {
        for (ScheduledFuture<?> scheduledFuture : pendingUdpTimeouts) {
            scheduledFuture.cancel(false);
        }
        pendingUdpTimeouts.clear();
    }

    public static synchronized void disconnect() {
        StackTraceElement[] stackTraceElementArray = new Throwable().getStackTrace();
        String string = stackTraceElementArray.length > 1 ? stackTraceElementArray[1].getClassName() + "." + stackTraceElementArray[1].getMethodName() : "?";
        LOGGER.info("[P2PBridge] disconnect called from {}", (Object)string);
        cancelled.set(true);
        running.set(false);
        for (BridgePair object : activePairs) {
            object.close();
        }
        activePairs.clear();
        for (ReliableUdpTransport reliableUdpTransport : activeUdpTransports) {
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {}
        }
        activeUdpTransports.clear();
        P2PBridge.cancelPendingUdpTimeouts();
        try {
            if (hostServer != null && !hostServer.isClosed()) {
                hostServer.close();
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
        try {
            if (joinerServer != null && !joinerServer.isClosed()) {
                joinerServer.close();
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
        hostServer = null;
        hostPort = -1;
        joinerServer = null;
        joinerPort = -1;
        currentHostIp = null;
        currentHostPort = 0;
        joinerBridgeConnected.set(false);
        tcpJoinerBridgeConnectedV4.set(false);
        tcpJoinerBridgeConnectedV6.set(false);
        trafficDetected = false;
        firstPacketDeadlineMs = 0L;
        ExecutorService executorService = bridgeExecutor;
        bridgeExecutor = null;
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            executorService.shutdownNow();
        }
    }

    private static synchronized ExecutorService getOrCreateExecutor() {
        if (bridgeExecutor == null || bridgeExecutor.isShutdown()) {
            bridgeExecutor = new ThreadPoolExecutor(0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), runnable -> {
                Thread thread = new Thread(runnable, "VoxLink-Bridge");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.CallerRunsPolicy());
        }
        return bridgeExecutor;
    }

    public static int getHostPort() {
        return hostPort;
    }

    public static int getJoinerPort() {
        return joinerPort;
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static boolean isTargetMatch(String string, int n) {
        return currentHostPort != n ? false : string == null || string.equals(currentHostIp);
    }

    public static void notifyTrafficDetected() {
        if (!trafficDetected) {
            trafficDetected = true;
            LOGGER.info("Detected TCP traffic, triggering P2P punch");
        }
    }

    public static boolean shouldStartPunching() {
        return trafficDetected || System.currentTimeMillis() > firstPacketDeadlineMs;
    }

    public static void armLazyP2pDeadline() {
        firstPacketDeadlineMs = System.currentTimeMillis() + 5000L;
    }

    public static boolean isTrafficDetected() {
        return trafficDetected;
    }

    public static synchronized int startUdpJoinerBridge(ReliableUdpTransport reliableUdpTransport2) {
        ViaForgeHook.setupLocalTranslation();
        cancelled.set(false);
        joinerBridgeConnected.set(false);
        boolean bl = activeUdpTransports.stream().anyMatch(reliableUdpTransport -> reliableUdpTransport.isConnected());
        if (running.get() && joinerPort > 0 && bl) {
            return joinerPort;
        }
        try {
            if (joinerServer != null && !joinerServer.isClosed()) {
                joinerServer.close();
            }
            joinerServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            joinerPort = joinerServer.getLocalPort();
            running.set(true);
            activeUdpTransports.add(reliableUdpTransport2);
            reliableUdpTransport2.start();
            P2PBridge.getOrCreateExecutor().execute(() -> P2PBridge.acceptUdpJoinerConnections(joinerServer, reliableUdpTransport2));
            LOGGER.info("UDP joiner bridge started on port {}", (Object)joinerPort);
            return joinerPort;
        }
        catch (IOException iOException) {
            LOGGER.error("Failed to start UDP joiner bridge: {}", (Object)iOException.getMessage());
            running.set(false);
            if (joinerServer != null && !joinerServer.isClosed()) {
                try {
                    joinerServer.close();
                }
                catch (IOException iOException2) {
                    // empty catch block
                }
            }
            joinerServer = null;
            joinerPort = -1;
            return -1;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void acceptUdpJoinerConnections(ServerSocket serverSocket, ReliableUdpTransport reliableUdpTransport) {
        Object object;
        Object object2;
        try {
            serverSocket.setSoTimeout(30000);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        int n = 0;
        String string = "UDP joiner";
        while (running.get() && !serverSocket.isClosed() && !joinerBridgeConnected.get()) {
            try {
                try {
                    object2 = serverSocket.accept();
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    if (++n < 2) continue;
                    LOGGER.warn("[Bridge] {} half-open detected ({} timeouts), closing", (Object)string, (Object)n);
                    break;
                }
                n = 0;
                ((Socket)object2).setTcpNoDelay(true);
                ((Socket)object2).setSendBufferSize(32768);
                ((Socket)object2).setReceiveBufferSize(32768);
                if (currentJoinerMcSocketLock.compareAndSet(false, true)) {
                    try {
                        object = currentJoinerMcSocket;
                        if (object != null && !((Socket)object).isClosed()) {
                            LOGGER.info("UDP: Closing previous MC client connection");
                            try {
                                ((Socket)object).close();
                            }
                            catch (IOException iOException) {
                                // empty catch block
                            }
                        }
                        currentJoinerMcSocket = object2;
                    }
                    finally {
                        currentJoinerMcSocketLock.set(false);
                    }
                }
                joinerBridgeConnected.set(true);
                LOGGER.info("UDP: MC client connected to joiner bridge");
                object = P2PBridge.getOrCreateExecutor();
                object.submit(() -> P2PBridge.lambda$acceptUdpJoinerConnections$23(reliableUdpTransport, (Socket)object2));
                P2PBridge.bridgeMcToUdp(reliableUdpTransport, (Socket)object2, null);
            }
            catch (IOException iOException) {
                if (!running.get() || serverSocket.isClosed()) continue;
                LOGGER.warn("UDP joiner bridge accept error: {}", (Object)iOException.getMessage());
            }
        }
        joinerBridgeConnected.set(false);
        try {
            serverSocket.close();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        object2 = P2PBridge.class;
        object = P2PBridge.class;
        synchronized (P2PBridge.class) {
            if (joinerServer == serverSocket) {
                joinerServer = null;
                joinerPort = -1;
            }
            // ** MonitorExit[var5_8] (shouldn't be in output)
            return;
        }
    }

    public static synchronized void startUdpHostBridgeForClient(String string, ReliableUdpTransport reliableUdpTransport, int n, Runnable runnable) {
        block8: {
            cancelled.set(false);
            activeUdpTransports.add(reliableUdpTransport);
            reliableUdpTransport.start();
            running.set(true);
            if (n <= 0) {
                try {
                    IntegratedServer integratedServer = Minecraft.func_71410_x().func_71401_C();
                    LOGGER.info("Bridge port probe: integrated server={}", (Object)integratedServer);
                    if (integratedServer == null) break block8;
                    try {
                        Method method = MinecraftServer.class.getMethod("func_71215_F", new Class[0]);
                        n = (Integer)method.invoke(integratedServer, new Object[0]);
                        LOGGER.info("Bridge port probe: func_71215_F returned {}", (Object)n);
                    }
                    catch (Exception exception) {
                        LOGGER.warn("Bridge port probe: func_71215_F failed: {}", (Object)exception.toString());
                        try {
                            Field field = MinecraftServer.class.getDeclaredField("field_71319_s");
                            field.setAccessible(true);
                            n = field.getInt(integratedServer);
                            LOGGER.info("Bridge port probe: field_71319_s = {}", (Object)n);
                        }
                        catch (Exception exception2) {
                            LOGGER.warn("Bridge port probe: field_71319_s failed: {}", (Object)exception2.toString());
                        }
                    }
                }
                catch (Exception exception) {
                    LOGGER.warn("Bridge port probe: integrated server lookup failed: {}", (Object)exception.toString());
                }
            }
        }
        if (n <= 0) {
            n = 25565;
            LOGGER.warn("Host bridge mcPort was invalid, fell back to 25565 for client {}", (Object)string);
        }
        LOGGER.info("Host bridge starting for client {}, forwarding to MC port {}", (Object)string, (Object)n);
        int n2 = n;
        P2PBridge.getOrCreateExecutor().execute(() -> {
            block8: {
                Runnable runnable2 = runnable;
                Socket socket = null;
                AtomicBoolean atomicBoolean = new AtomicBoolean(false);
                try {
                    InputStream inputStream = reliableUdpTransport.getInputStream();
                    byte[] byArray = new byte[32768];
                    Thread thread = new Thread(() -> {
                        try {
                            Thread.sleep(15000L);
                            if (!atomicBoolean.get() && reliableUdpTransport.isConnected() && running.get()) {
                                LOGGER.warn("UDP host bridge for client {} first packet timeout (15s), request ICE restart", (Object)string);
                                reliableUdpTransport.requestIceRestart();
                            }
                        }
                        catch (InterruptedException interruptedException) {
                            // empty catch block
                        }
                    }, "VoxLink-BridgeWatchdog");
                    thread.setDaemon(true);
                    thread.start();
                    int n2 = inputStream.read(byArray);
                    atomicBoolean.set(true);
                    if (n2 <= 0) {
                        throw new IOException("UDP stream closed before first packet (read=" + n2 + ")");
                    }
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), n2), 10000);
                    socket.setTcpNoDelay(true);
                    socket.setSendBufferSize(32768);
                    socket.setReceiveBufferSize(32768);
                    OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(byArray, 0, n2);
                    outputStream.flush();
                    LOGGER.info("UDP host bridge for client {} connected to MC server on port {} (lazy, first packet {} bytes)", new Object[]{string, n2, n2});
                    ExecutorService executorService = P2PBridge.getOrCreateExecutor();
                    Socket socket2 = socket;
                    executorService.submit(() -> P2PBridge.bridgeUdpToMc(reliableUdpTransport, socket2, runnable2));
                    P2PBridge.bridgeMcToUdp(reliableUdpTransport, socket2, runnable2);
                }
                catch (IOException iOException) {
                    LOGGER.error("UDP host bridge for client {} failed to connect to MC server: {}", (Object)string, (Object)iOException.getMessage());
                    try {
                        reliableUdpTransport.close();
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                    if (socket != null) {
                        try {
                            socket.close();
                        }
                        catch (IOException iOException2) {
                            // empty catch block
                        }
                    }
                    activeUdpTransports.remove(reliableUdpTransport);
                    if (runnable2 == null) break block8;
                    runnable2.run();
                }
            }
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void bridgeUdpToMc(ReliableUdpTransport reliableUdpTransport, Socket socket, Runnable runnable) {
        InputStream inputStream = reliableUdpTransport.getInputStream();
        OutputStream outputStream = null;
        int n = 0;
        try {
            outputStream = socket.getOutputStream();
            byte[] byArray = new byte[32768];
            while (running.get() && reliableUdpTransport.isConnected() && !socket.isClosed() && (n = inputStream.read(byArray)) != -1) {
                outputStream.write(byArray, 0, n);
                outputStream.flush();
            }
            LOGGER.info("UDP->MC exit: running={} conn={} mcClosed={} read={}", new Object[]{running.get(), reliableUdpTransport.isConnected(), socket.isClosed(), n});
        }
        catch (IOException iOException) {
            if (running.get()) {
                LOGGER.info("UDP->MC bridge closed: {}", (Object)iOException.getMessage());
            }
        }
        finally {
            try {
                socket.shutdownOutput();
            }
            catch (IOException iOException) {}
            try {
                socket.close();
            }
            catch (IOException iOException) {}
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void bridgeMcToUdp(ReliableUdpTransport reliableUdpTransport, Socket socket, Runnable runnable) {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        int n = 0;
        try {
            inputStream = socket.getInputStream();
            outputStream = reliableUdpTransport.getOutputStream();
            byte[] byArray = new byte[32768];
            while (running.get() && reliableUdpTransport.isConnected() && !socket.isClosed() && (n = inputStream.read(byArray)) != -1) {
                outputStream.write(byArray, 0, n);
                outputStream.flush();
            }
            LOGGER.info("MC->UDP exit: running={} conn={} mcClosed={} read={}", new Object[]{running.get(), reliableUdpTransport.isConnected(), socket.isClosed(), n});
        }
        catch (IOException iOException) {
            if (running.get()) {
                LOGGER.info("MC->UDP bridge closed: {}", (Object)iOException.getMessage());
            }
        }
        finally {
            try {
                socket.close();
            }
            catch (IOException iOException) {}
            try {
                reliableUdpTransport.close();
            }
            catch (Exception exception) {}
            activeUdpTransports.remove(reliableUdpTransport);
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    private static /* synthetic */ void lambda$acceptUdpJoinerConnections$23(ReliableUdpTransport reliableUdpTransport, Socket socket) {
        P2PBridge.bridgeUdpToMc(reliableUdpTransport, socket, null);
    }

    private static /* synthetic */ void lambda$acceptJoinerConnectionPreconnected$14(BridgePair bridgePair, String string) {
        P2PBridge.bridge(bridgePair, bridgePair.client, bridgePair.mc, string);
    }

    static {
        joinerServer = null;
        joinerPort = -1;
        hostPort = -1;
        activePairs = new CopyOnWriteArrayList<BridgePair>();
        pendingUdpTimeouts = new CopyOnWriteArrayList();
        trafficDetected = false;
        firstPacketDeadlineMs = 0L;
        tcpJoinerBridgeConnectedV4 = new AtomicBoolean(false);
        tcpJoinerBridgeConnectedV6 = new AtomicBoolean(false);
        activeUdpTransports = new CopyOnWriteArrayList();
        currentJoinerMcSocketLock = new AtomicBoolean(false);
        currentJoinerMcSocket = null;
        joinerBridgeConnected = new AtomicBoolean(false);
    }

    private static class BridgePair {
        final Socket client;
        final Socket mc;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        BridgePair(Socket socket, Socket socket2) {
            this.client = socket;
            this.mc = socket2;
        }

        void close() {
            if (this.closed.compareAndSet(false, true)) {
                try {
                    if (!this.client.isClosed()) {
                        this.client.close();
                    }
                }
                catch (IOException iOException) {
                    // empty catch block
                }
                try {
                    if (!this.mc.isClosed()) {
                        this.mc.close();
                    }
                }
                catch (IOException iOException) {
                    // empty catch block
                }
            }
        }
    }
}

