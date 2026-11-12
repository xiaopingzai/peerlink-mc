/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.LogUploadManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReliableUdpTransport
implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-rudp");
    private static final byte[] MAGIC = new byte[]{86, 76};
    private static final byte TYPE_PUNCH = 1;
    private static final byte TYPE_PUNCH_ACK = 2;
    private static final byte TYPE_DATA = 3;
    private static final byte TYPE_ACK = 4;
    private static final byte TYPE_DISCONNECT = 7;
    private static final byte TYPE_KEEPALIVE = 8;
    private static final byte TYPE_FEC_XOR = 9;
    private static final byte TYPE_RESTART = 10;
    private static final byte TYPE_VOICE = 11;
    private static final int FEC_GROUP_SIZE = 4;
    private static final int HEADER_SIZE = 11;
    private static final int PAYLOAD_LEN_SIZE = 2;
    private static final int MAX_PAYLOAD = 1400;
    private static final int WINDOW_SIZE = 64;
    private static final long RETRANSMIT_TIMEOUT_MS = 800L;
    private static final int KEEPALIVE_INTERVAL_S = 1;
    private static final int KEEPALIVE_TIMEOUT_S = 60;
    private static final int MAX_SILENT_RETRANSMIT_CYCLES = 30;
    private static final int UNRELIABLE_FAIL_THRESHOLD = 5;
    private static final long UNRELIABLE_SILENCE_MS = 8000L;
    private static final int MAX_FEC_GROUP_SIZE = 20;
    private static final int FEC_MAX_PACKET_SIZE = 1454;
    private static final int FEC_CLEAN_WINDOW = 10;
    private static final int SMALL_PACKET_THRESHOLD = 512;
    private static final int POLL_INTERVAL_MS = 50;
    private static final int MAX_BUFFERED_CHUNKS = 512;
    private static final long RTO_MIN_MS = 100L;
    private static final long RTO_MAX_MS = 800L;
    private static final long CLOCK_GRANULARITY_MS = 10L;
    private static final int WINDOW_MIN = 16;
    private static final int WINDOW_MAX = 64;
    private static final int LOSS_SAMPLE_LIMIT = 200;
    private static final long MAX_RETRANSMIT_TOTAL_MS = 24000L;
    private static final long RETRANSMIT_BACKOFF_MS = 250L;
    private volatile long srtt = -1L;
    private volatile long rttvar = 0L;
    private volatile long rto = 200L;
    private int lossEvents = 0;
    private int lossLostEvents = 0;
    private volatile int effectiveWindow = 64;
    private volatile int lastAckSeq = -1;
    private int dupAckCount = 0;
    private final DatagramSocket socket;
    private volatile InetSocketAddress remoteAddress;
    private volatile boolean remoteConfirmed = false;
    private volatile boolean running = true;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile long lastRecvTime = System.currentTimeMillis();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile int writeState = 0;
    private static final int STATE_WRITABLE = 0;
    private static final int STATE_UNRELIABLE = 1;
    private volatile int consecutiveFailures = 0;
    private final AtomicBoolean iceRestartTriggered = new AtomicBoolean(false);
    private volatile Runnable onIceRestartRequested;
    private volatile Consumer<byte[]> onVoiceData;
    private volatile int nextSendSeq = 0;
    private volatile int oldestUnackedSeq = 0;
    private final ConcurrentSkipListMap<Integer, PendingPacket> pendingAcks = new ConcurrentSkipListMap();
    private final Object sendLock = new Object();
    private final AtomicInteger nextExpectedSeq = new AtomicInteger(0);
    private final ConcurrentSkipListMap<Integer, byte[]> recvBuffer = new ConcurrentSkipListMap();
    private final Object recvLock = new Object();
    private final UdpInputStream inputStream = new UdpInputStream();
    private final UdpOutputStream outputStream = new UdpOutputStream();
    private final ConcurrentLinkedQueue<byte[]> outboundQueue = new ConcurrentLinkedQueue();
    private int bufferedChunks = 0;
    private Thread recvThread;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VoxLink-Retransmit");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> retransmitTask;
    private ScheduledFuture<?> keepaliveTask;
    private final List<byte[]> fecSendGroup = new ArrayList<byte[]>();
    private int fecSendGroupSeq = -1;
    private final Object fecSendLock = new Object();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, byte[]>> fecRecvGroup = new ConcurrentHashMap();
    private final ConcurrentHashMap<Integer, byte[]> fecRecvXor = new ConcurrentHashMap();
    private final ConcurrentHashMap<Integer, int[]> fecRecvLengths = new ConcurrentHashMap();
    private volatile int minActiveGroupId = Integer.MAX_VALUE;
    private volatile int pendingRebindPort = -1;
    private volatile long lastCurrentPortRecvMs = 0L;
    private volatile long pendingRebindTime = 0L;

    public ReliableUdpTransport(DatagramSocket datagramSocket, InetSocketAddress inetSocketAddress) {
        this.socket = datagramSocket;
        this.remoteAddress = inetSocketAddress;
        try {
            datagramSocket.setSoTimeout(100);
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    public InputStream getInputStream() {
        return this.inputStream;
    }

    public OutputStream getOutputStream() {
        return this.outputStream;
    }

    public void start() {
        if (this.connected.compareAndSet(false, true)) {
            try {
                byte[] byArray = new byte[11];
                System.arraycopy(MAGIC, 0, byArray, 0, 2);
                byArray[2] = 8;
                ReliableUdpTransport.writeInt32(byArray, 3, 0);
                ReliableUdpTransport.writeInt32(byArray, 7, 0);
                this.socket.send(new DatagramPacket(byArray, byArray.length, this.remoteAddress));
            }
            catch (IOException iOException) {
                // empty catch block
            }
            this.recvThread = new Thread(this::receiveLoop, "VoxLink-UdpRecv");
            this.recvThread.setDaemon(true);
            this.recvThread.start();
            this.retransmitTask = this.scheduler.scheduleWithFixedDelay(this::retransmitCheck, 50L, 50L, TimeUnit.MILLISECONDS);
            this.scheduler.scheduleWithFixedDelay(this::flushOutbound, 50L, 50L, TimeUnit.MILLISECONDS);
            this.keepaliveTask = this.scheduler.scheduleWithFixedDelay(this::sendKeepalive, 1L, 1L, TimeUnit.SECONDS);
        }
    }

    public boolean isConnected() {
        return this.connected.get() && this.running;
    }

    public void setOnIceRestartRequested(Runnable runnable) {
        this.onIceRestartRequested = runnable;
    }

    public void setOnVoiceData(Consumer<byte[]> consumer) {
        this.onVoiceData = consumer;
    }

    public void sendVoice(byte[] byArray) {
        if (byArray == null || this.socket == null || this.socket.isClosed()) {
            return;
        }
        if (byArray.length > 1400) {
            LOGGER.debug("[ReliableUdp] Voice payload too large ({}), dropped", (Object)byArray.length);
            return;
        }
        byte[] byArray2 = new byte[11 + byArray.length];
        System.arraycopy(MAGIC, 0, byArray2, 0, 2);
        byArray2[2] = 11;
        System.arraycopy(byArray, 0, byArray2, 11, byArray.length);
        try {
            this.socket.send(new DatagramPacket(byArray2, byArray2.length, this.remoteAddress));
        }
        catch (IOException iOException) {
            LOGGER.debug("[ReliableUdp] Voice send failed: {}", (Object)iOException.getMessage());
        }
    }

    private void handleVoice(byte[] byArray, int n) {
        Consumer<byte[]> consumer = this.onVoiceData;
        if (consumer != null && n > 11) {
            try {
                consumer.accept(Arrays.copyOfRange(byArray, 11, n));
            }
            catch (Throwable throwable) {
                LOGGER.warn("[ReliableUdp] voice handler exception: {}", (Object)throwable.getMessage());
            }
        }
    }

    public void requestIceRestart() {
        this.triggerIceRestart();
    }

    private void triggerIceRestart() {
        Runnable runnable;
        if (this.closed.get()) {
            return;
        }
        if (this.iceRestartTriggered.compareAndSet(false, true) && (runnable = this.onIceRestartRequested) != null) {
            try {
                runnable.run();
            }
            catch (Exception exception) {
                LOGGER.warn("[ReliableUdp] ICE Restart callback exception: {}", (Object)exception.getMessage());
            }
        }
    }

    private void sendRestart() {
        try {
            byte[] byArray = new byte[11];
            System.arraycopy(MAGIC, 0, byArray, 0, 2);
            byArray[2] = 10;
            ReliableUdpTransport.writeInt32(byArray, 3, 0);
            ReliableUdpTransport.writeInt32(byArray, 7, 0);
            this.socket.send(new DatagramPacket(byArray, byArray.length, this.remoteAddress));
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void sendPunchAck(SocketAddress socketAddress) {
        try {
            byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], 2, 0, 0};
            this.socket.send(new DatagramPacket(byArray, byArray.length, socketAddress));
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void maybeRebindRemote(DatagramPacket datagramPacket) {
        InetSocketAddress inetSocketAddress = this.remoteAddress;
        if (inetSocketAddress == null) {
            return;
        }
        boolean bl = datagramPacket.getPort() == inetSocketAddress.getPort() && datagramPacket.getAddress().equals(inetSocketAddress.getAddress());
        boolean bl2 = bl;
        if (bl) {
            this.remoteConfirmed = true;
            this.pendingRebindPort = -1;
            this.lastCurrentPortRecvMs = System.currentTimeMillis();
        } else if (datagramPacket.getAddress().equals(inetSocketAddress.getAddress())) {
            long l = System.currentTimeMillis();
            boolean bl3 = this.lastCurrentPortRecvMs > 0L && l - this.lastCurrentPortRecvMs < 6000L;
            boolean bl4 = bl3;
            if (!bl3) {
                if (datagramPacket.getPort() == this.pendingRebindPort && l - this.pendingRebindTime < 5000L) {
                    this.remoteAddress = new InetSocketAddress(inetSocketAddress.getAddress(), datagramPacket.getPort());
                    this.remoteConfirmed = true;
                    this.pendingRebindPort = -1;
                    LOGGER.info("[ReliableUdp] Remote port drifted {} -> {}, rebind to actual peer socket (symmetric NAT)", (Object)inetSocketAddress.getPort(), (Object)datagramPacket.getPort());
                } else {
                    this.pendingRebindPort = datagramPacket.getPort();
                    this.pendingRebindTime = l;
                }
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void receiveLoop() {
        byte[] byArray = new byte[1454];
        DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
        block21: while (this.running) {
            try {
                this.socket.receive(datagramPacket);
                LogUploadManager.onTransportActivity();
                if (datagramPacket.getLength() < 3 || byArray[0] != MAGIC[0] || byArray[1] != MAGIC[1]) continue;
                byte by = byArray[2];
                if (by != 1 && by != 2) {
                    if (datagramPacket.getLength() < 11) continue;
                    this.maybeRebindRemote(datagramPacket);
                    int n = ReliableUdpTransport.readInt32(byArray, 3);
                    int n2 = ReliableUdpTransport.readInt32(byArray, 7);
                    switch (by) {
                        case 3: {
                            this.handleData(n, n2, byArray, datagramPacket.getLength());
                            continue block21;
                        }
                        case 4: {
                            this.handleAck(n2);
                            this.lastRecvTime = System.currentTimeMillis();
                        }
                        default: {
                            continue block21;
                        }
                        case 7: {
                            this.handleDisconnect();
                            continue block21;
                        }
                        case 8: {
                            this.lastRecvTime = System.currentTimeMillis();
                            this.consecutiveFailures = 0;
                            if (this.writeState != 0) {
                                this.writeState = 0;
                                LOGGER.info("[ReliableUdp] writeState restored to WRITABLE");
                            }
                            this.sendKeepalive();
                            continue block21;
                        }
                        case 9: {
                            this.handleFecXor(ReliableUdpTransport.readInt32(byArray, 3), byArray, datagramPacket.getLength());
                            continue block21;
                        }
                        case 10: {
                            this.lastRecvTime = System.currentTimeMillis();
                            LOGGER.info("[ReliableUdp] Received peer RESTART signal, trigger ICE Restart");
                            this.triggerIceRestart();
                            continue block21;
                        }
                        case 11: 
                    }
                    this.handleVoice(byArray, datagramPacket.getLength());
                    continue;
                }
                this.maybeRebindRemote(datagramPacket);
                this.lastRecvTime = System.currentTimeMillis();
                if (by != 1) continue;
                this.sendPunchAck(datagramPacket.getSocketAddress());
            }
            catch (SocketTimeoutException socketTimeoutException) {
            }
            catch (IOException iOException) {
                if (this.socket.isClosed() || !this.running) {
                    this.running = false;
                    break;
                }
                LOGGER.warn("[ReliableUdp] Receive error: {}", (Object)iOException.getMessage());
            }
            catch (Throwable throwable) {
                Object object;
                LOGGER.error("[ReliableUdp] receiveLoop died with exception: {}", (Object)throwable.getMessage(), (Object)throwable);
                this.running = false;
                this.connected.set(false);
                Object object2 = object = this.recvLock;
                synchronized (object2) {
                    this.recvLock.notifyAll();
                }
                object2 = object = this.sendLock;
                synchronized (object2) {
                    this.sendLock.notifyAll();
                }
                try {
                    this.scheduler.execute(this::close);
                }
                catch (Exception exception) {}
                break;
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void handleData(int n3, int n4, byte[] byArray, int n5) {
        this.lastRecvTime = System.currentTimeMillis();
        this.processAck(n4);
        byte[] byArray2 = this.copyPayload(byArray, n5);
        if (byArray2 == null) {
            this.sendAck();
        } else {
            Object object;
            int n6 = this.nextExpectedSeq.get();
            if (ReliableUdpTransport.seqAfter(n6, n3) && ReliableUdpTransport.seqDiff(n6, n3) > 128) {
                LOGGER.warn("[ReliableUdp] Peer restarted (seq {} far behind expected {}), reset receive state", (Object)n3, (Object)n6);
                this.nextExpectedSeq.set(n3);
                this.recvBuffer.clear();
                this.fecRecvGroup.clear();
                this.fecRecvXor.clear();
                this.fecRecvLengths.clear();
                this.minActiveGroupId = Integer.MAX_VALUE;
            }
            int n7 = n3 / 4;
            this.fecRecvGroup.computeIfAbsent(n7, n -> new ConcurrentHashMap()).put(n3, byArray2);
            if (n7 < this.minActiveGroupId) {
                this.minActiveGroupId = n7;
            }
            if (this.fecRecvGroup.size() > 20) {
                int n8 = this.minActiveGroupId + 10;
                this.fecRecvGroup.keySet().removeIf(n2 -> n2 < n8);
                this.fecRecvXor.keySet().removeIf(n2 -> n2 < n8);
                this.fecRecvLengths.keySet().removeIf(n2 -> n2 < n8);
                if (!this.fecRecvGroup.isEmpty()) {
                    this.minActiveGroupId = this.fecRecvGroup.keySet().stream().mapToInt(Integer::intValue).min().orElse(this.minActiveGroupId);
                }
            }
            Object object2 = object = this.recvLock;
            synchronized (object2) {
                int n9 = this.nextExpectedSeq.get();
                if (n3 != n9) {
                    if (!ReliableUdpTransport.seqAfter(n3, n9) || ReliableUdpTransport.seqDiff(n3, n9) >= 128) {
                        this.sendAck();
                        return;
                    }
                    this.recvBuffer.putIfAbsent(n3, byArray2);
                } else {
                    if (this.bufferedChunks >= 512) {
                        return;
                    }
                    this.inputStream.writeBuffer(byArray2);
                    ++this.bufferedChunks;
                    this.nextExpectedSeq.getAndIncrement();
                    while (this.recvBuffer.containsKey(this.nextExpectedSeq.get()) && this.bufferedChunks < 512) {
                        byte[] byArray3 = this.recvBuffer.remove(this.nextExpectedSeq.get());
                        this.inputStream.writeBuffer(byArray3);
                        ++this.bufferedChunks;
                        this.nextExpectedSeq.getAndIncrement();
                    }
                    this.recvLock.notifyAll();
                }
            }
            this.sendAck();
            this.tryFecRecovery(n7);
        }
    }

    private byte[] copyPayload(byte[] byArray, int n) {
        int n2 = ReliableUdpTransport.readUint16(byArray, 11);
        if (13 + n2 > n) {
            return null;
        }
        byte[] byArray2 = new byte[n2];
        System.arraycopy(byArray, 13, byArray2, 0, n2);
        return byArray2;
    }

    private void handleFecXor(int n, byte[] byArray, int n2) {
        this.lastRecvTime = System.currentTimeMillis();
        int n3 = 14;
        int n4 = byArray[13] & 0xFF;
        int n5 = n3 + n4 * 2;
        int n6 = ReliableUdpTransport.readUint16(byArray, 11);
        if (n5 + n6 <= n2) {
            int[] nArray = new int[n4];
            for (int i = 0; i < n4; ++i) {
                nArray[i] = ReliableUdpTransport.readUint16(byArray, n3 + i * 2);
            }
            byte[] byArray2 = new byte[n6];
            System.arraycopy(byArray, n5, byArray2, 0, n6);
            this.fecRecvXor.put(n, byArray2);
            this.fecRecvLengths.put(n, nArray);
            this.tryFecRecovery(n);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void tryFecRecovery(int n) {
        Map map = this.fecRecvGroup.get(n);
        byte[] byArray = this.fecRecvXor.get(n);
        int[] nArray = this.fecRecvLengths.get(n);
        if (map != null && byArray != null && nArray != null) {
            int n2;
            int n3 = n * 4;
            int n4 = -1;
            int n5 = -1;
            int n6 = 0;
            for (n2 = 0; n2 < 4; ++n2) {
                int n7 = n3 + n2;
                if (map.containsKey(n7)) {
                    ++n6;
                    continue;
                }
                if (ReliableUdpTransport.seqAfter(this.nextExpectedSeq.get(), n7)) {
                    ++n6;
                    continue;
                }
                n4 = n7;
                n5 = n2;
            }
            if (n6 == 3 && n4 >= 0) {
                byte[] byArray2;
                Object object;
                Object object2;
                Object object3 = (byte[])byArray.clone();
                Iterator iterator = map.entrySet().iterator();
                while (iterator.hasNext()) {
                    object = object2 = iterator.next();
                    byArray2 = (byte[])object.getValue();
                    int n8 = Math.min(((byte[])object3).length, byArray2.length);
                    for (int i = 0; i < n8; ++i) {
                        int n9 = i;
                        object3[n9] = (byte)(object3[n9] ^ byArray2[i]);
                    }
                }
                n2 = n5 < nArray.length ? nArray[n5] : ((byte[])object3).length;
                int n10 = n2;
                if (n2 < ((byte[])object3).length) {
                    object2 = new byte[n2];
                    System.arraycopy(object3, 0, object2, 0, n2);
                    object3 = object2;
                }
                object = object2 = this.recvLock;
                synchronized (object) {
                    if (this.bufferedChunks < 512) {
                        this.recvBuffer.put(n4, (byte[])object3);
                    }
                    while (this.bufferedChunks < 512 && this.recvBuffer.containsKey(this.nextExpectedSeq.get())) {
                        byArray2 = this.recvBuffer.remove(this.nextExpectedSeq.get());
                        this.inputStream.writeBuffer(byArray2);
                        ++this.bufferedChunks;
                        this.nextExpectedSeq.getAndIncrement();
                    }
                    this.recvLock.notifyAll();
                }
                LOGGER.debug("[ReliableUdp] FEC recovered seq {}", (Object)n4);
                this.fecRecvGroup.remove(n);
                this.fecRecvXor.remove(n);
                this.fecRecvLengths.remove(n);
            } else if (n6 == 4) {
                this.fecRecvGroup.remove(n);
                this.fecRecvXor.remove(n);
                this.fecRecvLengths.remove(n);
            }
        }
    }

    private static byte[] computeXorPayload(List<byte[]> list) {
        Object object2;
        int n = 0;
        for (Object object2 : list) {
            if (((byte[])object2).length <= n) continue;
            n = ((Object)object2).length;
        }
        Object object3 = new byte[n];
        object2 = list.iterator();
        while (object2.hasNext()) {
            byte[] byArray = (byte[])object2.next();
            for (int i = 0; i < byArray.length; ++i) {
                int n2 = i;
                object3[n2] = (byte)(object3[n2] ^ byArray[i]);
            }
        }
        return object3;
    }

    private void handleAck(int n) {
        this.processAck(n);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void processAck(int n) {
        Object object;
        Object object2 = object = this.sendLock;
        synchronized (object2) {
            PendingPacket pendingPacket;
            if (n == this.lastAckSeq) {
                ++this.dupAckCount;
                if (this.dupAckCount >= 3 && !this.pendingAcks.isEmpty()) {
                    pendingPacket = this.pendingAcks.get(this.oldestUnackedSeq);
                    if (pendingPacket != null) {
                        pendingPacket.sendTime = System.currentTimeMillis();
                        ++pendingPacket.retries;
                        this.sendDataPacket(this.oldestUnackedSeq, pendingPacket.data, false);
                        this.recordLoss(true);
                    }
                    this.dupAckCount = 0;
                }
            } else {
                this.lastAckSeq = n;
                this.dupAckCount = 0;
            }
            while (!this.pendingAcks.isEmpty() && ReliableUdpTransport.seqAfter(n, this.oldestUnackedSeq)) {
                pendingPacket = this.pendingAcks.get(this.oldestUnackedSeq);
                if (pendingPacket != null && pendingPacket.retries == 0) {
                    this.updateRto(System.currentTimeMillis() - pendingPacket.sendTime);
                }
                this.pendingAcks.remove(this.oldestUnackedSeq);
                ++this.oldestUnackedSeq;
                this.consecutiveFailures = 0;
                this.recordLoss(false);
            }
            if (this.writeState == 1) {
                this.writeState = 0;
                LOGGER.info("[ReliableUdp] writeState UNRELIABLE->WRITABLE (ack received)");
            }
            this.sendLock.notifyAll();
        }
    }

    private void updateRto(long l) {
        if (l > 0L) {
            if (this.srtt < 0L) {
                this.srtt = l;
                this.rttvar = l / 2L;
            } else {
                this.rttvar = (3L * this.rttvar + Math.abs(this.srtt - l)) / 4L;
                this.srtt = (7L * this.srtt + l) / 8L;
            }
            this.rto = Math.max(100L, Math.min(800L, this.srtt + Math.max(10L, 4L * this.rttvar)));
        }
    }

    private void recordLoss(boolean bl) {
        int n;
        double d;
        if (bl) {
            ++this.lossLostEvents;
        }
        ++this.lossEvents;
        if (this.lossEvents >= 200) {
            this.lossLostEvents = (this.lossLostEvents + 1) / 2;
            this.lossEvents = (this.lossEvents + 1) / 2;
        }
        double d2 = (double)this.lossLostEvents / (double)Math.max(this.lossEvents, 1);
        if ((d < 0.03 ? 64 : (d2 < 0.08 ? 48 : (n = d2 < 0.15 ? 32 : 16))) != this.effectiveWindow) {
            this.effectiveWindow = n;
            LOGGER.debug("[ReliableUdp] congestion window {} (loss={}%)", (Object)n, (Object)((int)(d2 * 100.0)));
        }
    }

    public long getRtoMs() {
        return this.rto;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void handleDisconnect() {
        Object object;
        LOGGER.warn("[ReliableUdp] Received DISCONNECT packet");
        this.running = false;
        this.connected.set(false);
        Object object2 = object = this.recvLock;
        synchronized (object2) {
            this.recvLock.notifyAll();
        }
        object2 = object = this.sendLock;
        synchronized (object2) {
            this.sendLock.notifyAll();
        }
    }

    private void sendAck() {
        try {
            byte[] byArray = new byte[11];
            System.arraycopy(MAGIC, 0, byArray, 0, 2);
            byArray[2] = 4;
            ReliableUdpTransport.writeInt32(byArray, 3, 0);
            ReliableUdpTransport.writeInt32(byArray, 7, this.nextExpectedSeq.get());
            this.enqueueSend(byArray);
        }
        catch (Exception exception) {
            LOGGER.debug("[ReliableUdp] ACK build failed: {}", (Object)exception.getMessage());
        }
    }

    private void enqueueSend(byte[] byArray) {
        if (this.running && !this.closed.get()) {
            this.outboundQueue.offer(byArray);
        }
    }

    private void flushOutbound() {
        if (this.running && !this.closed.get()) {
            byte[] byArray;
            while ((byArray = this.outboundQueue.poll()) != null) {
                try {
                    this.socket.send(new DatagramPacket(byArray, byArray.length, this.remoteAddress));
                }
                catch (IOException iOException) {
                    LOGGER.debug("[ReliableUdp] Outbound send failed: {}", (Object)iOException.getMessage());
                    break;
                }
            }
        } else {
            this.outboundQueue.clear();
        }
    }

    private void sendDataPacket(int n, byte[] byArray, boolean bl) {
        try {
            byte[] byArray2 = new byte[13 + byArray.length];
            System.arraycopy(MAGIC, 0, byArray2, 0, 2);
            byArray2[2] = 3;
            ReliableUdpTransport.writeInt32(byArray2, 3, n);
            ReliableUdpTransport.writeInt32(byArray2, 7, this.nextExpectedSeq.get());
            ReliableUdpTransport.writeUint16(byArray2, 11, byArray.length);
            System.arraycopy(byArray, 0, byArray2, 13, byArray.length);
            this.enqueueSend(byArray2);
            if (bl && byArray.length < 512 && this.running && !this.closed.get()) {
                byte[] byArray3 = (byte[])byArray2.clone();
                this.scheduler.schedule(() -> this.enqueueSend(byArray3), 50L, TimeUnit.MILLISECONDS);
            }
        }
        catch (Exception exception) {
            LOGGER.debug("[ReliableUdp] Data build failed: {}", (Object)exception.getMessage());
        }
    }

    private void sendFecPacket(int n, byte[] byArray, int[] nArray) {
        try {
            int n2 = nArray.length;
            int n3 = 14 + n2 * 2;
            byte[] byArray2 = new byte[n3 + byArray.length];
            System.arraycopy(MAGIC, 0, byArray2, 0, 2);
            byArray2[2] = 9;
            ReliableUdpTransport.writeInt32(byArray2, 3, n);
            ReliableUdpTransport.writeInt32(byArray2, 7, 0);
            ReliableUdpTransport.writeUint16(byArray2, 11, byArray.length);
            byArray2[13] = (byte)n2;
            for (int i = 0; i < n2; ++i) {
                ReliableUdpTransport.writeUint16(byArray2, 14 + i * 2, nArray[i]);
            }
            System.arraycopy(byArray, 0, byArray2, n3, byArray.length);
            this.socket.send(new DatagramPacket(byArray2, byArray2.length, this.remoteAddress));
        }
        catch (IOException iOException) {
            LOGGER.debug("[ReliableUdp] FEC send failed: {}", (Object)iOException.getMessage());
        }
    }

    private void sendKeepalive() {
        if (this.running && this.connected.get()) {
            try {
                byte[] byArray = new byte[11];
                System.arraycopy(MAGIC, 0, byArray, 0, 2);
                byArray[2] = 8;
                ReliableUdpTransport.writeInt32(byArray, 3, 0);
                ReliableUdpTransport.writeInt32(byArray, 7, this.nextExpectedSeq.get());
                this.socket.send(new DatagramPacket(byArray, byArray.length, this.remoteAddress));
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void retransmitCheck() {
        if (this.running) {
            long l = System.currentTimeMillis();
            long l2 = l - this.lastRecvTime;
            if (this.writeState == 0 && !this.pendingAcks.isEmpty()) {
                boolean bl = this.consecutiveFailures >= 5;
                boolean bl2 = l2 > 8000L;
                boolean bl3 = bl2;
                if (bl && bl2) {
                    this.writeState = 1;
                    LOGGER.warn("[ReliableUdp] writeState WRITABLE->UNRELIABLE (failures={}, silence={}ms)", (Object)this.consecutiveFailures, (Object)l2);
                }
            }
            if (!this.closed.get() && l2 > 60000L) {
                LOGGER.warn("[ReliableUdp] No data received for {}s, connection dead", (Object)60);
                this.sendRestart();
                this.triggerIceRestart();
                this.close();
            } else if (!this.closed.get() && !this.pendingAcks.isEmpty() && l2 > 24000L) {
                LOGGER.warn("[ReliableUdp] No packets received for {}ms, {} packets pending, peer probably dead", (Object)24000L, (Object)this.pendingAcks.size());
                this.sendRestart();
                this.triggerIceRestart();
                this.close();
            } else {
                Object object;
                Object object2 = object = this.sendLock;
                synchronized (object2) {
                    long l3 = Math.max(10L, Math.min(120L, 24000L / Math.max(this.rto, 100L)));
                    for (Map.Entry<Integer, PendingPacket> entry : this.pendingAcks.entrySet()) {
                        PendingPacket pendingPacket = entry.getValue();
                        long l4 = this.rto + (long)Math.min(pendingPacket.retries, 3) * 250L;
                        if (l - pendingPacket.sendTime <= l4) continue;
                        if ((long)pendingPacket.retries >= l3) {
                            LOGGER.warn("[ReliableUdp] seq {} retry {} times exceeded limit (unacked:{})", new Object[]{entry.getKey(), l3, this.pendingAcks.size()});
                            this.close();
                            return;
                        }
                        if (pendingPacket.retries == 0) {
                            LOGGER.info("[ReliableUdp] Retransmit seq {} (pending={})", (Object)entry.getKey(), (Object)this.pendingAcks.size());
                        }
                        pendingPacket.sendTime = l;
                        ++pendingPacket.retries;
                        ++this.consecutiveFailures;
                        this.recordLoss(true);
                        this.sendDataPacket(entry.getKey(), pendingPacket.data, false);
                    }
                }
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void sendBytes(byte[] byArray, int n, int n2) throws IOException {
        if (this.running && this.connected.get()) {
            int n3 = n;
            while (n3 < n + n2) {
                Object object;
                int n4 = Math.min(1400, n + n2 - n3);
                byte[] byArray2 = new byte[n4];
                System.arraycopy(byArray, n3, byArray2, 0, n4);
                n3 += n4;
                Object object2 = object = this.sendLock;
                synchronized (object2) {
                    Object object3;
                    int n5 = this.nextSendSeq;
                    long l = -1L;
                    int n6 = this.oldestUnackedSeq;
                    while (this.running && this.connected.get() && ReliableUdpTransport.seqDiff(this.nextSendSeq, this.oldestUnackedSeq) >= this.effectiveWindow) {
                        try {
                            if (l < 0L) {
                                l = System.currentTimeMillis();
                                n6 = this.oldestUnackedSeq;
                            } else if (this.oldestUnackedSeq != n6) {
                                l = System.currentTimeMillis();
                                n6 = this.oldestUnackedSeq;
                            }
                            this.sendLock.wait(1000L);
                            long l2 = System.currentTimeMillis() - l;
                            if (l2 < 30000L) continue;
                            throw new IOException("transport stuck: " + l2 / 1000L + "s no progress");
                        }
                        catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Transport closed or interrupted");
                        }
                    }
                    if (!this.running || !this.connected.get()) {
                        throw new IOException("Transport closed");
                    }
                    ++this.nextSendSeq;
                    PendingPacket pendingPacket = new PendingPacket(byArray2, System.currentTimeMillis(), 0);
                    this.pendingAcks.put(n5, pendingPacket);
                    this.sendDataPacket(n5, byArray2, true);
                    Object object4 = object3 = this.fecSendLock;
                    synchronized (object4) {
                        int n7 = n5 / 4;
                        if (n7 != this.fecSendGroupSeq) {
                            this.fecSendGroup.clear();
                            this.fecSendGroupSeq = n7;
                        }
                        this.fecSendGroup.add((byte[])byArray2.clone());
                        if (this.fecSendGroup.size() == 4) {
                            byte[] byArray3 = ReliableUdpTransport.computeXorPayload(this.fecSendGroup);
                            int[] nArray = new int[4];
                            for (int i = 0; i < 4; ++i) {
                                nArray[i] = this.fecSendGroup.get(i).length;
                            }
                            this.sendFecPacket(n7, byArray3, nArray);
                            this.fecSendGroup.clear();
                        }
                    }
                }
            }
            return;
        }
        throw new IOException("Transport closed");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    @Override
    public void close() {
        Object object;
        if (!this.closed.compareAndSet(false, true)) return;
        this.running = false;
        this.connected.set(false);
        try {
            object = new byte[11];
            System.arraycopy(MAGIC, 0, object, 0, 2);
            object[2] = 7;
            ReliableUdpTransport.writeInt32(object, 3, 0);
            ReliableUdpTransport.writeInt32(object, 7, 0);
            this.socket.send(new DatagramPacket((byte[])object, ((byte[])object).length, this.remoteAddress));
        }
        catch (IOException iOException) {
            // empty catch block
        }
        if (this.retransmitTask != null) {
            this.retransmitTask.cancel(false);
        }
        if (this.keepaliveTask != null) {
            this.keepaliveTask.cancel(false);
        }
        this.scheduler.shutdownNow();
        Object object2 = object = (Object)this.recvLock;
        synchronized (object) {
            this.recvLock.notifyAll();
            // ** MonitorExit[var2_3 /* !! */ ] (shouldn't be in output)
            object2 = object = (Object)this.sendLock;
            synchronized (object) {
                this.sendLock.notifyAll();
                // ** MonitorExit[var2_3 /* !! */ ] (shouldn't be in output)
                if (this.recvThread != null) {
                    this.recvThread.interrupt();
                }
                try {
                    if (this.socket == null || this.socket.isClosed()) return;
                    this.socket.close();
                    return;
                }
                catch (Exception exception) {
                    // empty catch block
                }
                return;
            }
        }
    }

    private static int readInt32(byte[] byArray, int n) {
        return (byArray[n] & 0xFF) << 24 | (byArray[n + 1] & 0xFF) << 16 | (byArray[n + 2] & 0xFF) << 8 | byArray[n + 3] & 0xFF;
    }

    private static boolean seqAfter(int n, int n2) {
        return n > n2 && n - n2 < 0x3FFFFFFF || n < n2 && n2 - n > 0x3FFFFFFF;
    }

    private static int seqDiff(int n, int n2) {
        long l = (long)n - (long)n2 & 0xFFFFFFFFL;
        return (int)l;
    }

    private static int readUint16(byte[] byArray, int n) {
        return (byArray[n] & 0xFF) << 8 | byArray[n + 1] & 0xFF;
    }

    private static void writeInt32(byte[] byArray, int n, int n2) {
        byArray[n] = (byte)(n2 >> 24);
        byArray[n + 1] = (byte)(n2 >> 16);
        byArray[n + 2] = (byte)(n2 >> 8);
        byArray[n + 3] = (byte)n2;
    }

    private static void writeUint16(byte[] byArray, int n, int n2) {
        byArray[n] = (byte)(n2 >> 8);
        byArray[n + 1] = (byte)n2;
    }

    private static class PendingPacket {
        final byte[] data;
        long sendTime;
        int retries;

        PendingPacket(byte[] byArray, long l, int n) {
            this.data = byArray;
            this.sendTime = l;
            this.retries = n;
        }
    }

    private class UdpOutputStream
    extends OutputStream {
        private UdpOutputStream() {
        }

        @Override
        public void write(int n) throws IOException {
            this.write(new byte[]{(byte)n}, 0, 1);
        }

        @Override
        public void write(byte[] byArray, int n, int n2) throws IOException {
            try {
                ReliableUdpTransport.this.sendBytes(byArray, n, n2);
            }
            catch (IOException iOException) {
                LOGGER.debug("[ReliableUdp] Write failed: {}", (Object)iOException.getMessage());
                throw iOException;
            }
        }

        @Override
        public void flush() {
        }
    }

    private class UdpInputStream
    extends InputStream {
        private final ConcurrentLinkedQueue<byte[]> chunks = new ConcurrentLinkedQueue();
        private byte[] currentChunk = null;
        private int currentPos = 0;

        private UdpInputStream() {
        }

        void writeBuffer(byte[] byArray) {
            this.chunks.offer(byArray);
        }

        @Override
        public int read() throws IOException {
            byte[] byArray = new byte[1];
            int n = this.read(byArray, 0, 1);
            return n <= 0 ? -1 : byArray[0] & 0xFF;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public int read(byte[] byArray, int n, int n2) throws IOException {
            Object object;
            if (n2 == 0) {
                return 0;
            }
            Object object2 = object = ReliableUdpTransport.this.recvLock;
            synchronized (object2) {
                while (this.currentChunk == null || this.currentPos >= this.currentChunk.length) {
                    this.currentChunk = this.chunks.poll();
                    this.currentPos = 0;
                    if (this.currentChunk != null) {
                        if (ReliableUdpTransport.this.bufferedChunks <= 0) continue;
                        --ReliableUdpTransport.this.bufferedChunks;
                        continue;
                    }
                    if (!ReliableUdpTransport.this.running && this.chunks.isEmpty()) {
                        return -1;
                    }
                    try {
                        ReliableUdpTransport.this.recvLock.wait(500L);
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
                int n3 = this.currentChunk.length - this.currentPos;
                int n4 = Math.min(n2, n3);
                System.arraycopy(this.currentChunk, this.currentPos, byArray, n, n4);
                this.currentPos += n4;
                if (this.currentPos >= this.currentChunk.length) {
                    this.currentChunk = null;
                    this.currentPos = 0;
                }
                return n4;
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public int available() {
            Object object;
            Object object2 = object = ReliableUdpTransport.this.recvLock;
            synchronized (object2) {
                int n = 0;
                if (this.currentChunk != null) {
                    n += this.currentChunk.length - this.currentPos;
                }
                for (byte[] byArray : (byte[][])this.chunks.toArray((T[])new byte[0][])) {
                    n += byArray.length;
                }
                return n;
            }
        }
    }
}

