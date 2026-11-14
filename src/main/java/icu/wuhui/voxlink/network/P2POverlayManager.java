/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class P2POverlayManager {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-overlay");
    private static final Gson GSON = new Gson();
    private static final int MAX_PACKET_SIZE = 32768;
    private static final int READ_BUFFER_SIZE = 65536;
    private static final int PING_INTERVAL_SEC = 2;
    private static final int MAX_PING_FAILURES = 5;
    private static final int MAX_SEEN_SEQ_SIZE = 1000;
    private DatagramSocket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Role> role = new AtomicReference<Role>(Role.NONE);
    private final AtomicReference<InetSocketAddress> upstreamAddr = new AtomicReference<InetSocketAddress>(null);
    private volatile String upstreamId;
    private final AtomicReference<InetSocketAddress> downstreamAddr = new AtomicReference<InetSocketAddress>(null);
    private volatile String downstreamId;
    private String nodeId;
    private final int localPort;
    private final AtomicInteger packetSeq = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Long> seenSeq = new ConcurrentHashMap();
    private ExecutorService ioExecutor;
    private ScheduledExecutorService pingScheduler;
    private volatile PacketHandler handler;
    private final AtomicInteger upstreamLatency = new AtomicInteger(-1);
    private final AtomicInteger downstreamLatency = new AtomicInteger(-1);
    private final AtomicReference<PendingPing> pendingUpstreamPing = new AtomicReference<PendingPing>(null);
    private final AtomicReference<PendingPing> pendingDownstreamPing = new AtomicReference<PendingPing>(null);
    private final AtomicInteger upstreamPingFailures = new AtomicInteger(0);
    private final AtomicInteger downstreamPingFailures = new AtomicInteger(0);

    public P2POverlayManager(String string, int n) {
        this.nodeId = string != null ? string : "node_" + System.identityHashCode(this);
        this.localPort = n;
    }

    public void start(PacketHandler packetHandler) throws IOException {
        if (!this.running.get()) {
            this.handler = packetHandler;
            try {
                this.socket = new DatagramSocket(null);
                this.socket.setReuseAddress(true);
                this.socket.bind(new InetSocketAddress(this.localPort));
                this.socket.setSoTimeout(1000);
            }
            catch (SocketException socketException) {
                LOGGER.error("Overlay UDP port {} bind failed: {}", (Object)this.localPort, (Object)socketException.getMessage());
                throw new IOException("Failed to bind overlay socket", socketException);
            }
            try {
                this.ioExecutor = Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "VoxLink-Overlay-IO");
                    thread.setDaemon(true);
                    return thread;
                });
                this.pingScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "VoxLink-Overlay-Ping");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            catch (Exception exception) {
                if (this.ioExecutor != null) {
                    this.ioExecutor.shutdownNow();
                }
                if (this.pingScheduler != null) {
                    this.pingScheduler.shutdownNow();
                }
                if (this.socket != null && !this.socket.isClosed()) {
                    this.socket.close();
                }
                throw new IOException("Failed to create executors", exception);
            }
            this.running.set(true);
            try {
                this.ioExecutor.submit(this::readLoop);
            }
            catch (RejectedExecutionException rejectedExecutionException) {
                LOGGER.error("Overlay read loop submit failed: {}", (Object)rejectedExecutionException.getMessage());
                this.stop();
                throw new IOException("Failed to submit overlay read loop", rejectedExecutionException);
            }
            this.pingScheduler.scheduleAtFixedRate(this::pingTask, 2L, 2L, TimeUnit.SECONDS);
            LOGGER.info("P2P overlay started, port {}, node {}", (Object)this.socket.getLocalPort(), (Object)this.nodeId);
        }
    }

    public void connectUpstream(String string, String string2, int n) {
        if (this.running.get() && string2 != null) {
            InetSocketAddress inetSocketAddress = new InetSocketAddress(string2, n);
            this.upstreamAddr.set(inetSocketAddress);
            this.upstreamId = string;
            if (this.downstreamAddr.get() == null) {
                this.role.set(Role.CHAIN_TAIL);
            } else {
                this.role.set(Role.CHAIN_MIDDLE);
            }
            LOGGER.info("Upstream connection: {} at {}:{} role {}", new Object[]{string, string2, n, this.role.get()});
            this.sendHandshake(string, inetSocketAddress);
        }
    }

    public void setDownstream(String string, String string2, int n) {
        if (this.running.get() && string2 != null) {
            InetSocketAddress inetSocketAddress = new InetSocketAddress(string2, n);
            this.downstreamAddr.set(inetSocketAddress);
            this.downstreamId = string;
            LOGGER.info("Downstream set: {} at {}:{}", new Object[]{string, string2, n});
            if (this.role.get() != Role.CHAIN_HEAD && this.role.get() != Role.CHAIN_MIDDLE) {
                if (this.upstreamAddr.get() != null) {
                    this.role.set(Role.CHAIN_MIDDLE);
                } else {
                    this.role.set(Role.CHAIN_TAIL);
                }
            }
        }
    }

    public void becomeHead(String string, String string2, int n) {
        if (this.running.get()) {
            this.role.set(Role.CHAIN_HEAD);
            this.upstreamAddr.set(null);
            this.upstreamId = null;
            if (string != null && string2 != null) {
                InetSocketAddress inetSocketAddress = new InetSocketAddress(string2, n);
                this.downstreamAddr.set(inetSocketAddress);
                this.downstreamId = string;
                this.sendHandshake(string, inetSocketAddress);
            } else {
                this.downstreamAddr.set(null);
                this.downstreamId = null;
            }
            LOGGER.info("Became chain head, downstream: {}", (Object)(string != null ? string : "none"));
        }
    }

    public void switchToDirectMode() {
        this.role.set(Role.NONE);
        this.upstreamAddr.set(null);
        this.upstreamId = null;
        this.downstreamAddr.set(null);
        this.downstreamId = null;
        LOGGER.info("Switched to direct mode");
    }

    public int getUpstreamLatency() {
        return this.upstreamLatency.get();
    }

    public int getDownstreamLatency() {
        return this.downstreamLatency.get();
    }

    public Role getRole() {
        return this.role.get();
    }

    public void setNodeId(String string) {
        this.nodeId = string;
    }

    public void stop() {
        this.running.set(false);
        this.upstreamAddr.set(null);
        this.downstreamAddr.set(null);
        this.upstreamId = null;
        this.downstreamId = null;
        if (this.socket != null && !this.socket.isClosed()) {
            this.socket.close();
        }
        this.socket = null;
        if (this.pingScheduler != null) {
            this.pingScheduler.shutdownNow();
            this.pingScheduler = null;
        }
        if (this.ioExecutor != null) {
            this.ioExecutor.shutdownNow();
            this.ioExecutor = null;
        }
        this.role.set(Role.NONE);
        this.seenSeq.clear();
        LOGGER.info("P2P overlay stopped");
    }

    public int getLocalPort() {
        if (this.socket != null && !this.socket.isClosed()) {
            try {
                return this.socket.getLocalPort();
            }
            catch (Exception exception) {
                return this.localPort;
            }
        }
        return this.localPort;
    }

    private void readLoop() {
        byte[] byArray = new byte[65536];
        while (this.running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
                this.socket.receive(datagramPacket);
                byte[] byArray2 = new byte[datagramPacket.getLength()];
                System.arraycopy(datagramPacket.getData(), datagramPacket.getOffset(), byArray2, 0, datagramPacket.getLength());
                this.ioExecutor.submit(() -> this.processPacket(byArray2, datagramPacket.getSocketAddress()));
            }
            catch (SocketException socketException) {
                if (!this.running.get()) break;
                LOGGER.debug("Overlay read socket temp error: {}", (Object)socketException.getMessage());
            }
            catch (Exception exception) {
                if (!this.running.get()) continue;
                LOGGER.debug("Overlay read error: {}", (Object)exception.getMessage());
            }
        }
    }

    private void processPacket(byte[] byArray, SocketAddress socketAddress) {
        try {
            if (!(socketAddress instanceof InetSocketAddress)) {
                return;
            }
            InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;
            String string = P2POverlayManager.decompress(byArray);
            if (string == null) {
                return;
            }
            JsonObject jsonObject = (JsonObject)GSON.fromJson(string, JsonObject.class);
            String string2 = jsonObject.has("type") ? jsonObject.get("type").getAsString() : "";
            String string3 = jsonObject.has("from") ? jsonObject.get("from").getAsString() : "";
            switch (string2) {
                case "handshake": {
                    this.handleHandshake(jsonObject, inetSocketAddress);
                    break;
                }
                case "ping": {
                    this.handlePing(jsonObject, string3, inetSocketAddress);
                    break;
                }
                case "pong": {
                    this.handlePong(jsonObject);
                    break;
                }
                case "data_relay": {
                    String string4 = this.determineDirection(inetSocketAddress);
                    this.handleDataRelay(jsonObject, string4, inetSocketAddress);
                    break;
                }
                default: {
                    LOGGER.debug("Unknown overlay packet type: {}", (Object)string2);
                    break;
                }
            }
        }
        catch (Exception exception) {
            LOGGER.debug("Overlay packet process failed: {}", (Object)exception.getMessage());
        }
    }

    private void handleHandshake(JsonObject jsonObject, InetSocketAddress inetSocketAddress) {
        String string = jsonObject.has("from") ? jsonObject.get("from").getAsString() : "";
        LOGGER.info("Received downstream handshake: {}", (Object)string);
        if (this.role.get() == Role.CHAIN_HEAD || this.role.get() == Role.CHAIN_MIDDLE) {
            this.downstreamAddr.set(inetSocketAddress);
            this.downstreamId = string;
        }
        if (this.handler != null) {
            this.handler.onLinkReady();
        }
    }

    private void handlePing(JsonObject jsonObject, String string, InetSocketAddress inetSocketAddress) {
        JsonObject jsonObject2 = new JsonObject();
        jsonObject2.addProperty("type", "pong");
        jsonObject2.addProperty("from", this.nodeId);
        jsonObject2.addProperty("seq", (Number)(jsonObject.has("seq") ? jsonObject.get("seq").getAsInt() : 0));
        if (jsonObject.has("dir")) {
            jsonObject2.addProperty("dir", jsonObject.get("dir").getAsString());
        }
        this.sendPacketTo(jsonObject2, inetSocketAddress);
    }

    private void handlePong(JsonObject jsonObject) {
        long l = System.currentTimeMillis();
        int n = jsonObject.has("seq") ? jsonObject.get("seq").getAsInt() : -1;
        String string = jsonObject.has("dir") ? jsonObject.get("dir").getAsString() : "up";
        String string2 = string;
        if ("down".equals(string)) {
            PendingPing pendingPing = this.pendingDownstreamPing.get();
            if (pendingPing != null && n == pendingPing.seq) {
                int n2 = (int)(l - pendingPing.timestamp);
                this.downstreamLatency.set(n2);
                this.pendingDownstreamPing.compareAndSet(pendingPing, null);
                this.downstreamPingFailures.set(0);
            }
        } else {
            PendingPing pendingPing = this.pendingUpstreamPing.get();
            if (pendingPing != null && n == pendingPing.seq) {
                int n3 = (int)(l - pendingPing.timestamp);
                this.upstreamLatency.set(n3);
                this.pendingUpstreamPing.compareAndSet(pendingPing, null);
                this.upstreamPingFailures.set(0);
            }
        }
    }

    private void handleDataRelay(JsonObject jsonObject, String string, InetSocketAddress inetSocketAddress) {
        String string2 = jsonObject.has("from") ? jsonObject.get("from").getAsString() : "";
        int n = jsonObject.has("seq") ? jsonObject.get("seq").getAsInt() : 0;
        String string3 = string2 + ":" + n;
        if (this.seenSeq.putIfAbsent(string3, System.currentTimeMillis()) == null) {
            JsonObject jsonObject2;
            if (this.seenSeq.size() > 1000) {
                long l = System.currentTimeMillis() - 60000L;
                Iterator<Map.Entry<String, Long>> iter = this.seenSeq.entrySet().iterator();
                while (iter.hasNext() && this.seenSeq.size() > 1000) {
                    if (iter.next().getValue() >= l) continue;
                    iter.remove();
                }
                if (this.seenSeq.size() > 1000) {
                    ArrayList<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(this.seenSeq.entrySet());
                    sorted.sort(Comparator.comparingLong(Map.Entry::getValue));
                    int n2 = this.seenSeq.size() - 1000;
                    for (int i = 0; i < n2; ++i) {
                        this.seenSeq.remove(sorted.get(i).getKey());
                    }
                }
            }
            String string4 = jsonObject.has("to") && !jsonObject.get("to").isJsonNull() ? jsonObject.get("to").getAsString() : null;
            String string5 = jsonObject.has("priority") ? jsonObject.get("priority").getAsString() : "L2";
            JsonObject jsonObject3 = jsonObject.has("payload") ? jsonObject.getAsJsonObject("payload") : new JsonObject();
            jsonObject2 = jsonObject3;
            if ((string4 == null || string4.equals(this.nodeId)) && this.handler != null) {
                this.handler.onDataReceived(string2, string5, jsonObject3);
            }
            if (string4 == null || !string4.equals(this.nodeId)) {
                if ("upstream".equals(string)) {
                    this.forwardToDownstream(jsonObject);
                } else if ("downstream".equals(string)) {
                    this.forwardToUpstream(jsonObject);
                } else {
                    InetSocketAddress upstream = this.upstreamAddr.get();
                    InetSocketAddress inetSocketAddress2 = this.downstreamAddr.get();
                    if (upstream != null && upstream.equals(inetSocketAddress)) {
                        this.forwardToDownstream(jsonObject);
                    } else if (inetSocketAddress2 != null && inetSocketAddress2.equals(inetSocketAddress)) {
                        this.forwardToUpstream(jsonObject);
                    }
                }
            }
        }
    }

    private void forwardToDownstream(JsonObject jsonObject) {
        InetSocketAddress inetSocketAddress = this.downstreamAddr.get();
        if (inetSocketAddress != null) {
            this.sendPacketTo(jsonObject, inetSocketAddress);
        }
    }

    private void forwardToUpstream(JsonObject jsonObject) {
        InetSocketAddress inetSocketAddress = this.upstreamAddr.get();
        if (inetSocketAddress != null) {
            this.sendPacketTo(jsonObject, inetSocketAddress);
        }
    }

    private String determineDirection(InetSocketAddress inetSocketAddress) {
        InetSocketAddress inetSocketAddress2 = this.upstreamAddr.get();
        InetSocketAddress inetSocketAddress3 = this.downstreamAddr.get();
        if (inetSocketAddress2 != null && inetSocketAddress2.equals(inetSocketAddress)) {
            return "upstream";
        }
        return inetSocketAddress3 != null && inetSocketAddress3.equals(inetSocketAddress) ? "downstream" : "unknown";
    }

    private void sendHandshake(String string, InetSocketAddress inetSocketAddress) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("type", "handshake");
        jsonObject.addProperty("from", this.nodeId);
        this.sendPacketTo(jsonObject, inetSocketAddress);
    }

    private void pingTask() {
        if (this.running.get() && this.role.get() != Role.NONE) {
            InetSocketAddress inetSocketAddress;
            JsonObject jsonObject;
            int n;
            PendingPing pendingPing;
            InetSocketAddress inetSocketAddress2 = this.upstreamAddr.get();
            if (inetSocketAddress2 != null) {
                pendingPing = this.pendingUpstreamPing.get();
                if (pendingPing != null) {
                    n = this.upstreamPingFailures.incrementAndGet();
                    if (n >= 5) {
                        LOGGER.warn("Upstream link broken, {} consecutive ping failures", (Object)n);
                        this.upstreamPingFailures.set(0);
                        this.pendingUpstreamPing.compareAndSet(pendingPing, null);
                        if (this.handler != null) {
                            this.handler.onLinkLost("upstream_timeout");
                        }
                    }
                } else {
                    this.upstreamPingFailures.set(0);
                }
                n = this.packetSeq.incrementAndGet();
                this.pendingUpstreamPing.set(new PendingPing(n, System.currentTimeMillis()));
                jsonObject = new JsonObject();
                jsonObject.addProperty("type", "ping");
                jsonObject.addProperty("from", this.nodeId);
                jsonObject.addProperty("seq", (Number)n);
                jsonObject.addProperty("dir", "up");
                this.sendPacketTo(jsonObject, inetSocketAddress2);
            }
            if ((inetSocketAddress = this.downstreamAddr.get()) != null) {
                pendingPing = this.pendingDownstreamPing.get();
                if (pendingPing != null) {
                    n = this.downstreamPingFailures.incrementAndGet();
                    if (n >= 5) {
                        LOGGER.warn("Downstream link broken, {} consecutive ping failures", (Object)n);
                        this.downstreamPingFailures.set(0);
                        this.pendingDownstreamPing.compareAndSet(pendingPing, null);
                        if (this.handler != null) {
                            this.handler.onLinkLost("downstream_timeout");
                        }
                    }
                } else {
                    this.downstreamPingFailures.set(0);
                }
                n = this.packetSeq.incrementAndGet();
                this.pendingDownstreamPing.set(new PendingPing(n, System.currentTimeMillis()));
                jsonObject = new JsonObject();
                jsonObject.addProperty("type", "ping");
                jsonObject.addProperty("from", this.nodeId);
                jsonObject.addProperty("seq", (Number)n);
                jsonObject.addProperty("dir", "down");
                this.sendPacketTo(jsonObject, inetSocketAddress);
            }
        }
    }

    private void sendPacket(JsonObject jsonObject) {
        Role role = this.role.get();
        if (role == Role.CHAIN_HEAD) {
            this.sendPacketToDownstream(jsonObject);
        } else if (role == Role.CHAIN_TAIL) {
            this.sendPacketToUpstream(jsonObject);
        } else if (role == Role.CHAIN_MIDDLE) {
            String string = jsonObject.has("to") && !jsonObject.get("to").isJsonNull() ? jsonObject.get("to").getAsString() : null;
            String string2 = string;
            if (string == null) {
                this.sendPacketToUpstream(jsonObject);
                this.sendPacketToDownstream(jsonObject);
            } else if (string.equals(this.upstreamId)) {
                this.sendPacketToUpstream(jsonObject);
            } else if (string.equals(this.downstreamId)) {
                this.sendPacketToDownstream(jsonObject);
            } else {
                this.sendPacketToUpstream(jsonObject);
                this.sendPacketToDownstream(jsonObject);
            }
        }
    }

    private void sendPacketToUpstream(JsonObject jsonObject) {
        InetSocketAddress inetSocketAddress = this.upstreamAddr.get();
        if (inetSocketAddress != null) {
            this.sendPacketTo(jsonObject, inetSocketAddress);
        }
    }

    private void sendPacketToDownstream(JsonObject jsonObject) {
        InetSocketAddress inetSocketAddress = this.downstreamAddr.get();
        if (inetSocketAddress != null) {
            this.sendPacketTo(jsonObject, inetSocketAddress);
        }
    }

    private void sendPacketTo(JsonObject jsonObject, InetSocketAddress inetSocketAddress) {
        try {
            String string = GSON.toJson((JsonElement)jsonObject);
            byte[] byArray = P2POverlayManager.compress(string);
            byte[] byArray2 = P2POverlayManager.framePacket(byArray);
            DatagramPacket datagramPacket = new DatagramPacket(byArray2, byArray2.length, inetSocketAddress);
            this.socket.send(datagramPacket);
        }
        catch (Exception exception) {
            LOGGER.debug("Send packet to {} failed: {}", (Object)inetSocketAddress, (Object)exception.getMessage());
        }
    }

    static byte[] compress(String string) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gZIPOutputStream = new GZIPOutputStream(byteArrayOutputStream);){
                gZIPOutputStream.write(string.getBytes(StandardCharsets.UTF_8));
            }
            return byteArrayOutputStream.toByteArray();
        }
        catch (IOException iOException) {
            LOGGER.error("Overlay data compression failed: {}", (Object)iOException.getMessage());
            throw new RuntimeException("Compression failed", iOException);
        }
    }

    static String decompress(byte[] byArray) {
        String string;
        GZIPInputStream gZIPInputStream;
        if (byArray.length < 4) {
            return null;
        }
        int n = ByteBuffer.wrap(byArray, 0, 4).getInt();
        if (n <= 0 || n > byArray.length - 4) {
            return null;
        }
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(byArray, 4, n);
        try {
            gZIPInputStream = new GZIPInputStream(byteArrayInputStream);
        }
        catch (IOException iOException) {
            return null;
        }
        try {
            byte[] byArray2 = P2POverlayManager.readAllBytes8(gZIPInputStream);
            string = new String(byArray2, StandardCharsets.UTF_8);
        }
        catch (Throwable throwable) {
            try {
                try {
                    try {
                        gZIPInputStream.close();
                    }
                    catch (IOException iOException) {
                        // empty catch block
                    }
                    throw throwable;
                }
                catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                    throw throwable;
                }
            }
            catch (IOException iOException) {
                return null;
            }
        }
        try {
            gZIPInputStream.close();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        return string;
    }

    static byte[] framePacket(byte[] byArray) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(4 + byArray.length);
        byteBuffer.putInt(byArray.length);
        byteBuffer.put(byArray);
        return byteBuffer.array();
    }

    private static byte[] readAllBytes8(InputStream inputStream) throws IOException {
        int n;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] byArray = new byte[8192];
        while ((n = inputStream.read(byArray)) > 0) {
            byteArrayOutputStream.write(byArray, 0, n);
        }
        return byteArrayOutputStream.toByteArray();
    }

    private static class PendingPing {
        final int seq;
        final long timestamp;

        PendingPing(int n, long l) {
            this.seq = n;
            this.timestamp = l;
        }
    }

    public static interface PacketHandler {
        public void onDataReceived(String var1, String var2, JsonObject var3);

        public void onLinkReady();

        public void onLinkLost(String var1);
    }

    public static enum Role {
        NONE,
        CHAIN_HEAD,
        CHAIN_MIDDLE,
        CHAIN_TAIL;

    }
}

