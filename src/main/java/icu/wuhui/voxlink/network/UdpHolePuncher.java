/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PunchParams;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.PunchResult;
import icu.wuhui.voxlink.network.StunProbe;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UdpHolePuncher {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-punch");
    private static final byte[] MAGIC = new byte[]{86, 76};
    private static final byte TYPE_PUNCH = 1;
    private static final byte TYPE_PUNCH_ACK = 2;
    private static volatile ScheduledExecutorService PUNCH_TIMEOUT_SCHEDULER = UdpHolePuncher.createScheduler();
    private DatagramSocket socket;
    private final int sessionNonce = ThreadLocalRandom.current().nextInt(65536);
    private final AtomicBoolean punching = new AtomicBoolean(false);
    private final AtomicBoolean holeOpen = new AtomicBoolean(false);
    private final AtomicBoolean remoteReceived = new AtomicBoolean(false);
    private final AtomicBoolean localConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile InetAddress remoteAddress;
    private volatile int remotePort;
    private volatile List<Thread> recvThreadsRef = null;
    private volatile Thread sendThreadRef;
    private volatile ScheduledFuture<?> timeoutFuture;
    private volatile boolean socketTransferred = false;
    private volatile CompletableFuture<PunchResult> activeResult;
    private volatile Consumer<InetSocketAddress> onPeerPunchReceived;
    private volatile List<UdpHolePuncher> socketGroup;
    private volatile boolean skipFirewallDetection;
    private volatile PunchProfile profile;
    private volatile PunchParams punchParams;

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture completableFuture = new CompletableFuture();
        completableFuture.completeExceptionally(throwable);
        return completableFuture;
    }

    public void setProfile(PunchProfile punchProfile) {
        this.profile = punchProfile;
    }

    public PunchProfile profile() {
        PunchProfile punchProfile = this.profile;
        return punchProfile != null ? punchProfile : PunchProfile.current();
    }

    public void setPunchParams(PunchParams punchParams) {
        this.punchParams = punchParams;
    }

    public PunchParams punchParams() {
        return this.punchParams;
    }

    public int effectiveTimeoutMs() {
        PunchParams punchParams = this.punchParams;
        return punchParams != null && punchParams.timeoutMs > 0 ? punchParams.timeoutMs : this.profile().punchTimeoutMs;
    }

    public int effectivePortRange() {
        PunchParams punchParams = this.punchParams;
        return punchParams != null && punchParams.portRange > 0 ? punchParams.portRange : this.profile().portPredictionMaxRange;
    }

    public int effectiveSendInterval() {
        PunchParams punchParams = this.punchParams;
        return punchParams != null && punchParams.sendInterval > 0 ? punchParams.sendInterval : this.profile().send.intervalMs;
    }

    public int effectiveMinRounds() {
        PunchParams punchParams = this.punchParams;
        return punchParams != null && punchParams.sendMinRounds > 0 ? punchParams.sendMinRounds : this.profile().send.minRounds;
    }

    public int effectiveMinPass() {
        PunchParams punchParams = this.punchParams;
        return punchParams != null && punchParams.sendMinPass > 0 ? punchParams.sendMinPass : this.profile().send.minPass;
    }

    public boolean effectiveSkipDirectPunch() {
        PunchParams punchParams = this.punchParams;
        return punchParams != null && punchParams.skipDirectPunch;
    }

    public static void shutdown() {
        ScheduledExecutorService scheduledExecutorService = PUNCH_TIMEOUT_SCHEDULER;
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
            scheduledExecutorService.shutdownNow();
        }
    }

    private static ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-PunchTimeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static ScheduledExecutorService scheduler() {
        ScheduledExecutorService scheduledExecutorService = PUNCH_TIMEOUT_SCHEDULER;
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
            return PUNCH_TIMEOUT_SCHEDULER;
        }
        Class<UdpHolePuncher> clazz = UdpHolePuncher.class;
        Class<UdpHolePuncher> clazz2 = UdpHolePuncher.class;
        synchronized (UdpHolePuncher.class) {
            if (PUNCH_TIMEOUT_SCHEDULER != null && !PUNCH_TIMEOUT_SCHEDULER.isShutdown()) {
                // ** MonitorExit[var2_2] (shouldn't be in output)
                return PUNCH_TIMEOUT_SCHEDULER;
            }
            PUNCH_TIMEOUT_SCHEDULER = UdpHolePuncher.createScheduler();
            // ** MonitorExit[var2_2] (shouldn't be in output)
            return PUNCH_TIMEOUT_SCHEDULER;
        }
    }

    void replaceSocket(DatagramSocket datagramSocket) {
        DatagramSocket datagramSocket2 = this.socket;
        if (datagramSocket2 != null && datagramSocket2 != datagramSocket && !datagramSocket2.isClosed()) {
            try {
                datagramSocket2.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        this.socket = datagramSocket;
    }

    public void markSocketTransferred() {
        this.socketTransferred = true;
    }

    public boolean isSocketTransferred() {
        return this.socketTransferred;
    }

    public void setSkipFirewallDetection(boolean bl) {
        this.skipFirewallDetection = bl;
    }

    public boolean isPunching() {
        return this.punching.get();
    }

    public DatagramSocket createSocket() throws SocketException {
        DatagramSocket datagramSocket = this.socket;
        if (datagramSocket != null && !datagramSocket.isClosed()) {
            datagramSocket.close();
        }
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(this.profile().send.socketTimeoutMs);
        return this.socket;
    }

    public DatagramSocket createSocket(int n) throws SocketException {
        DatagramSocket datagramSocket = this.socket;
        if (datagramSocket != null && !datagramSocket.isClosed()) {
            datagramSocket.close();
        }
        try {
            this.socket = new DatagramSocket(n);
            this.socket.setSoTimeout(this.profile().send.socketTimeoutMs);
            return this.socket;
        }
        catch (SocketException socketException) {
            return this.createSocket();
        }
    }

    public DatagramSocket getSocket() {
        return this.socket;
    }

    public StunProbe.PublicMappedAddress discoverMappedAddress(List<String> list) {
        return StunProbe.discoverMappedAddress(this.socket, list);
    }

    public StunProbe.PublicMappedAddress[] discoverMappedAddressDual(String string, String string2) {
        return StunProbe.discoverMappedAddressDual(this.socket, string, string2);
    }

    public CompletableFuture<PunchResult> punch(String string, int n) {
        return this.punchWithPortPrediction(string, n, 0);
    }

    public CompletableFuture<PunchResult> punchWithPortPrediction(String string, int n, int n2) {
        return this.punchWithPortPrediction(string, n, n2, false);
    }

    public CompletableFuture<PunchResult> punchMultiSocket(String string, int n, List<UdpHolePuncher> list, AtomicBoolean atomicBoolean) {
        return this.punchMultiSocket(string, n, list, atomicBoolean, 0);
    }

    public CompletableFuture<PunchResult> punchMultiSocket(String string, int n, List<UdpHolePuncher> list, AtomicBoolean atomicBoolean, int n2) {
        Object object;
        Object object2;
        Object object3;
        this.punching.set(true);
        this.holeOpen.set(false);
        this.remoteReceived.set(false);
        this.localConfirmed.set(false);
        this.completed.set(false);
        try {
            this.remoteAddress = InetAddress.getByName(string);
            this.remotePort = n;
        }
        catch (Exception exception) {
            return UdpHolePuncher.failedFuture(exception);
        }
        CompletableFuture<PunchResult> completableFuture = new CompletableFuture<PunchResult>();
        this.activeResult = completableFuture;
        this.socketGroup = list;
        Object object4 = new Object();
        Consumer<InetSocketAddress> consumer = this.onPeerPunchReceived;
        int[] nArray = new int[]{0};
        int[] nArray2 = new int[]{0};
        long l = System.currentTimeMillis();
        int n3 = list.size();
        byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], 1, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
        int n4 = this.effectiveTimeoutMs() / this.effectiveSendInterval();
        LOGGER.info("[UdpHolePuncher] Multi-socket send start: target={}:{}, sockets={}, interval={}ms, profile={}", new Object[]{string, n, list.size(), this.effectiveSendInterval(), this.profile().describeInstance()});
        ArrayList<DatagramChannel> arrayList = new ArrayList<DatagramChannel>();
        HashMap<DatagramChannel, Object> hashMap = new HashMap<DatagramChannel, Object>();
        HashMap<DatagramChannel, Integer> hashMap2 = new HashMap<DatagramChannel, Integer>();
        Selector selector = null;
        try {
            selector = Selector.open();
            for (int i = 0; i < list.size(); ++i) {
                object3 = list.get(i);
                object2 = ((UdpHolePuncher)object3).getSocket();
                if (object2 == null || ((DatagramSocket)object2).isClosed()) continue;
                DatagramChannel datagramChannel = ((DatagramSocket)object2).getChannel();
                if (datagramChannel == null) {
                    object = ((DatagramSocket)object2).getLocalAddress();
                    int bl = ((DatagramSocket)object2).getLocalPort();
                    try {
                        ((DatagramSocket)object2).close();
                    }
                    catch (Exception thread) {
                        // empty catch block
                    }
                    datagramChannel = DatagramChannel.open();
                    datagramChannel.configureBlocking(false);
                    datagramChannel.bind(new InetSocketAddress((InetAddress)object, bl));
                    ((UdpHolePuncher)object3).replaceSocket(datagramChannel.socket());
                } else {
                    datagramChannel.configureBlocking(false);
                }
                datagramChannel.register(selector, 1);
                arrayList.add(datagramChannel);
                hashMap.put(datagramChannel, object3);
                hashMap2.put(datagramChannel, i);
            }
        }
        catch (IOException iOException) {
            LOGGER.warn("[UdpHolePuncher] NIO Selector init failed, fallback to multi-thread mode: {}", (Object)iOException.getMessage());
            return this.punchMultiSocketLegacy(string, n, list, atomicBoolean);
        }
        Selector selector2 = selector;
        object3 = hashMap;
        object2 = hashMap2;
        int n5 = this.sessionNonce;
        object = new Thread(() -> this.lambda$punchMultiSocket$1(selector2, (HashMap)object3, (HashMap)object2, nArray, object4, atomicBoolean, l, completableFuture, n3, nArray2, consumer), "VoxLink-PunchRecvNIO");
        ((Thread)object).setDaemon(true);
        this.recvThreadsRef = Collections.singletonList(object);
        ((Thread)object).start();
        boolean bl = list.size() <= 1 || this.skipFirewallDetection;
        Thread thread = new Thread(() -> {
            int n4;
            long l2 = System.currentTimeMillis();
            for (n4 = 0; this.punching.get() && !this.holeOpen.get() && n4 < n4; ++n4) {
                Object object2;
                Object object3;
                if (!bl && n4 >= this.profile().firewallDetectCycles && !this.remoteReceived.get()) {
                    long l3 = System.currentTimeMillis() - l2;
                    LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check: sent {} cycles/{}ms no reply, UDP blocked, abort early", (Object)n4, (Object)l3);
                    object2 = object3 = object4;
                    synchronized (object2) {
                        if (this.completed.compareAndSet(false, true)) {
                            this.punching.set(false);
                            completableFuture.complete(PunchResult.failure(n3, nArray[0], nArray2[0], 0, l3, true));
                        }
                        return;
                    }
                }
                int n5 = n3 > 0 ? n3 * 2 + 1 : 0;
                int n6 = 0;
                object3 = list.iterator();
                while (object3.hasNext()) {
                    object2 = (UdpHolePuncher)object3.next();
                    DatagramSocket datagramSocket = ((UdpHolePuncher)object2).getSocket();
                    if (datagramSocket == null || datagramSocket.isClosed()) continue;
                    int n7 = this.remotePort;
                    if (n3 > 0) {
                        n7 = this.remotePort + (n6++ % n5 - n3);
                    }
                    for (int i = 0; i < 3; ++i) {
                        DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, this.remoteAddress, n7);
                        UdpHolePuncher.sendPkt(datagramSocket, datagramPacket);
                    }
                }
                try {
                    Thread.sleep(this.effectiveSendInterval());
                    continue;
                }
                catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.info("[UdpHolePuncher] Multi-socket send end: cycles={}, holeOpen={}", (Object)n4, (Object)this.holeOpen.get());
            if (!this.holeOpen.get() && this.punching.get()) {
                Object object4;
                Object object5 = object4 = object4;
                synchronized (object5) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long l4 = System.currentTimeMillis() - l;
                        completableFuture.complete(PunchResult.failure(n3, nArray[0], nArray2[0], 0, l4, false));
                    }
                }
            }
        }, "VoxLink-PunchSend");
        thread.setDaemon(true);
        this.sendThreadRef = thread;
        thread.start();
        ScheduledFuture<?> scheduledFuture = UdpHolePuncher.scheduler().schedule(() -> {
            if (this.punching.get()) {
                Object object2;
                Object object3 = object2 = object4;
                synchronized (object3) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long l2 = System.currentTimeMillis() - l;
                        completableFuture.complete(PunchResult.failure(n3, nArray[0], nArray2[0], 0, l2, false));
                    }
                }
            }
        }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitMs), TimeUnit.MILLISECONDS);
        this.timeoutFuture = scheduledFuture;
        P2PBridge.registerPendingUdpTimeout(scheduledFuture);
        return completableFuture;
    }

    private void sendControlTo(byte by, InetAddress inetAddress, int n) {
        byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], by, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
        DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, inetAddress, n);
        UdpHolePuncher.sendPkt(this.socket, datagramPacket);
    }

    private static void sendPkt(DatagramSocket datagramSocket, DatagramPacket datagramPacket) {
        for (int i = 0; i < 3; ++i) {
            try {
                DatagramChannel datagramChannel = datagramSocket.getChannel();
                if (datagramChannel != null) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(datagramPacket.getData(), 0, datagramPacket.getLength());
                    datagramChannel.send(byteBuffer, datagramPacket.getSocketAddress());
                } else {
                    datagramSocket.send(datagramPacket);
                }
                return;
            }
            catch (IOException | RuntimeException exception) {
                continue;
            }
        }
    }

    private boolean acceptAddress(InetAddress inetAddress, byte by) {
        InetAddress inetAddress2 = this.remoteAddress;
        if (inetAddress2 == null) {
            return false;
        }
        if (inetAddress2.equals(inetAddress)) {
            return true;
        }
        if ((by == 1 || by == 2) && this.punching.get()) {
            byte[] byArray = inetAddress2.getAddress();
            byte[] byArray2 = inetAddress.getAddress();
            boolean bl = byArray != null && byArray2 != null && byArray.length == 4 && byArray2.length == 4 && byArray[0] == byArray2[0] && byArray[1] == byArray2[1];
            boolean bl2 = bl;
            if (bl) {
                LOGGER.info("[UdpHolePuncher] CGNAT multi-IP accept {} (expected {})", new Object[]{inetAddress.getHostAddress(), inetAddress2.getHostAddress()});
                this.remoteAddress = inetAddress;
                return true;
            }
        }
        return false;
    }

    private boolean acceptPacket(byte[] byArray, DatagramPacket datagramPacket) {
        byte by = byArray.length > 2 ? byArray[2] : (byte)-1;
        return this.acceptAddress(datagramPacket.getAddress(), by);
    }

    public CompletableFuture<PunchResult> punchMultiPort(String string, List<Integer> list) {
        if (list != null && !list.isEmpty()) {
            this.punching.set(true);
            this.holeOpen.set(false);
            this.remoteReceived.set(false);
            this.localConfirmed.set(false);
            this.completed.set(false);
            LOGGER.info("[UdpHolePuncher] punchMultiPort start: target={}, port count={}, range={}~{}, profile={}", new Object[]{string, list.size(), list.get(0), list.get(list.size() - 1), this.profile().describeInstance()});
            try {
                this.remoteAddress = InetAddress.getByName(string);
                this.remotePort = list.get(0);
            }
            catch (Exception exception) {
                this.punching.set(false);
                return UdpHolePuncher.failedFuture(exception);
            }
            CompletableFuture<PunchResult> completableFuture = new CompletableFuture<PunchResult>();
            this.activeResult = completableFuture;
            Object object = new Object();
            Consumer<InetSocketAddress> consumer = this.onPeerPunchReceived;
            int[] nArray = new int[]{0};
            int[] nArray2 = new int[]{0};
            long l = System.currentTimeMillis();
            int n = list.size();
            Thread thread = new Thread(() -> {
                byte[] byArray = new byte[64];
                DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
                boolean bl = false;
                int n2 = 0;
                while (this.punching.get() && !this.holeOpen.get()) {
                    Object object2;
                    try {
                        Object object3;
                        this.socket.receive(datagramPacket);
                        if (++n2 <= 10) {
                            LOGGER.info("[UdpHolePuncher] Received #{}: from {}:{}, len={}, bytes=[{},{},{}]", new Object[]{n2, datagramPacket.getAddress().getHostAddress(), datagramPacket.getPort(), datagramPacket.getLength(), datagramPacket.getLength() > 0 ? byArray[0] & 0xFF : -1, datagramPacket.getLength() > 1 ? byArray[1] & 0xFF : -1, datagramPacket.getLength() > 2 ? byArray[2] & 0xFF : -1});
                        }
                        if (datagramPacket.getLength() < 3 || byArray[0] != MAGIC[0] || byArray[1] != MAGIC[1] || !this.acceptPacket(byArray, datagramPacket)) continue;
                        byte by = byArray[2];
                        boolean bl2 = !this.completed.get() || by == 1;
                        boolean bl3 = bl2;
                        if (bl2 && !datagramPacket.getAddress().equals(this.remoteAddress)) {
                            LOGGER.info("[UdpHolePuncher] CGNAT multi-IP: accept from {}:{} (expected IP {})", new Object[]{datagramPacket.getAddress().getHostAddress(), datagramPacket.getPort(), this.remoteAddress.getHostAddress()});
                            this.remoteAddress = datagramPacket.getAddress();
                            this.remotePort = datagramPacket.getPort();
                            if (!bl && consumer != null) {
                                bl = true;
                                try {
                                    consumer.accept(new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()));
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                            }
                        }
                        if (bl2 && datagramPacket.getPort() != this.remotePort) {
                            LOGGER.info("[UdpHolePuncher] Accept from {}:{} (expected port {})", new Object[]{datagramPacket.getAddress().getHostAddress(), datagramPacket.getPort(), this.remotePort});
                            this.remotePort = datagramPacket.getPort();
                            if (!bl && consumer != null) {
                                bl = true;
                                try {
                                    consumer.accept(new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()));
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                            }
                        }
                        if (by == 1) {
                            nArray[0] = nArray[0] + 1;
                            this.remoteReceived.set(true);
                            this.sendControlTo((byte)2, datagramPacket.getAddress(), datagramPacket.getPort());
                            object3 = object2 = object;
                            synchronized (object3) {
                                if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                                    this.holeOpen.set(true);
                                    this.punching.set(false);
                                    this.socketTransferred = true;
                                    long l2 = System.currentTimeMillis() - l;
                                    completableFuture.complete(PunchResult.success(this.socket, n, nArray[0], nArray2[0], 0, l2));
                                }
                                continue;
                            }
                        }
                        if (by != 2) continue;
                        nArray2[0] = nArray2[0] + 1;
                        this.localConfirmed.set(true);
                        this.sendControlTo((byte)2, datagramPacket.getAddress(), datagramPacket.getPort());
                        object3 = object2 = object;
                        synchronized (object3) {
                            if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                                this.holeOpen.set(true);
                                this.punching.set(false);
                                this.socketTransferred = true;
                                long l3 = System.currentTimeMillis() - l;
                                completableFuture.complete(PunchResult.success(this.socket, n, nArray[0], nArray2[0], 0, l3));
                            }
                        }
                    }
                    catch (SocketTimeoutException socketTimeoutException) {
                    }
                    catch (IOException iOException) {
                        IOException iOException2 = iOException;
                        if (this.punching.get()) {
                            Object object4 = object2 = object;
                            synchronized (object4) {
                                if (this.completed.compareAndSet(false, true)) {
                                    this.punching.set(false);
                                    completableFuture.completeExceptionally(iOException2);
                                }
                            }
                        }
                        return;
                    }
                }
            }, "VoxLink-PunchRecv");
            thread.setDaemon(true);
            this.recvThreadsRef = Collections.singletonList(thread);
            thread.start();
            Thread thread2 = new Thread(() -> {
                Object object2;
                int n2;
                int n3 = this.effectiveTimeoutMs() / this.effectiveSendInterval();
                long l2 = System.currentTimeMillis();
                byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], 1, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
                LOGGER.info("[UdpHolePuncher] Multi-port send thread start: target={}, port={}, local port={}", new Object[]{this.remoteAddress.getHostAddress(), list, this.socket.getLocalPort()});
                for (n2 = 0; this.punching.get() && !this.holeOpen.get() && n2 < n3; ++n2) {
                    if (n2 >= n3 * 4 / 5 && !this.remoteReceived.get()) {
                        long l3 = System.currentTimeMillis() - l2;
                        LOGGER.debug("[UdpHolePuncher] Multi-port no reply after {} cycles/{}ms, continue until timeout (peer may be offline)", (Object)n2, (Object)l3);
                    }
                    object2 = list.iterator();
                    while (object2.hasNext()) {
                        int n4 = (Integer)object2.next();
                        DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, this.remoteAddress, n4);
                        UdpHolePuncher.sendPkt(this.socket, datagramPacket);
                    }
                    try {
                        Thread.sleep(this.effectiveSendInterval());
                        continue;
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                LOGGER.info("[UdpHolePuncher] Multi-port send thread end: cycles={}, holeOpen={}, punching={}", new Object[]{n2, this.holeOpen.get(), this.punching.get()});
                if (!this.holeOpen.get() && this.punching.get()) {
                    Object object3 = object2 = object;
                    synchronized (object3) {
                        if (this.completed.compareAndSet(false, true)) {
                            this.punching.set(false);
                            long l4 = System.currentTimeMillis() - l;
                            completableFuture.complete(PunchResult.failure(n, nArray[0], nArray2[0], 0, l4, false));
                        }
                    }
                }
            }, "VoxLink-PunchSend");
            thread2.setDaemon(true);
            this.sendThreadRef = thread2;
            thread2.start();
            ScheduledFuture<?> scheduledFuture = UdpHolePuncher.scheduler().schedule(() -> {
                Object object2;
                Object object3 = object2 = object;
                synchronized (object3) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long l2 = System.currentTimeMillis() - l;
                        completableFuture.complete(PunchResult.failure(n, nArray[0], nArray2[0], 0, l2, false));
                    }
                }
            }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitLongMs), TimeUnit.MILLISECONDS);
            this.timeoutFuture = scheduledFuture;
            P2PBridge.registerPendingUdpTimeout(scheduledFuture);
            return completableFuture;
        }
        return UdpHolePuncher.failedFuture(new IllegalArgumentException("punchMultiPort: empty target ports"));
    }

    public CompletableFuture<PunchResult> punchWithPortPrediction(String string, int n, int n2, boolean bl) {
        this.punching.set(true);
        this.holeOpen.set(false);
        this.remoteReceived.set(false);
        this.localConfirmed.set(false);
        this.completed.set(false);
        LOGGER.info("[UdpHolePuncher] punchWithPortPrediction start: target={}:{}, range={}, fixed={}, profile={}", new Object[]{string, n, n2, bl, this.profile().describeInstance()});
        try {
            this.remoteAddress = InetAddress.getByName(string);
            this.remotePort = n;
        }
        catch (Exception exception) {
            return UdpHolePuncher.failedFuture(exception);
        }
        CompletableFuture<PunchResult> completableFuture = new CompletableFuture<PunchResult>();
        this.activeResult = completableFuture;
        Object object = new Object();
        boolean bl2 = n2 > 0;
        boolean bl3 = bl;
        Consumer<InetSocketAddress> consumer = this.onPeerPunchReceived;
        int[] nArray = new int[]{0};
        int[] nArray2 = new int[]{0};
        long l = System.currentTimeMillis();
        boolean bl4 = true;
        Thread thread = new Thread(() -> {
            byte[] byArray = new byte[64];
            DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
            boolean bl = false;
            int n = 0;
            while (this.punching.get() && !this.holeOpen.get()) {
                try {
                    Object object2;
                    this.socket.receive(datagramPacket);
                    if (++n <= 10) {
                        LOGGER.info("[UdpHolePuncher] Received #{}: from {}:{}, len={}, bytes=[{},{},{}]", new Object[]{n, datagramPacket.getAddress().getHostAddress(), datagramPacket.getPort(), datagramPacket.getLength(), datagramPacket.getLength() > 0 ? byArray[0] & 0xFF : -1, datagramPacket.getLength() > 1 ? byArray[1] & 0xFF : -1, datagramPacket.getLength() > 2 ? byArray[2] & 0xFF : -1});
                    }
                    if (datagramPacket.getLength() < 3 || byArray[0] != MAGIC[0] || byArray[1] != MAGIC[1] || !this.acceptPacket(byArray, datagramPacket)) continue;
                    byte by = byArray[2];
                    if ((by == 1 || by == 2) && this.remoteAddress != null) {
                        boolean bl2 = !this.completed.get() || by == 1;
                        boolean bl3 = bl2;
                        if (bl2 && datagramPacket.getPort() != this.remotePort) {
                            LOGGER.info("[UdpHolePuncher] Accept from {}:{} (expected port {})", new Object[]{datagramPacket.getAddress().getHostAddress(), datagramPacket.getPort(), this.remotePort});
                            this.remotePort = datagramPacket.getPort();
                            if (!bl && consumer != null) {
                                bl = true;
                                try {
                                    consumer.accept(new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()));
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                            }
                        }
                    }
                    if (by == 1) {
                        Object object3;
                        nArray[0] = nArray[0] + 1;
                        this.remoteReceived.set(true);
                        this.sendControlTo((byte)2, datagramPacket.getAddress(), datagramPacket.getPort());
                        Object object4 = object3 = object;
                        synchronized (object4) {
                            if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                                this.holeOpen.set(true);
                                this.punching.set(false);
                                this.socketTransferred = true;
                                long l2 = System.currentTimeMillis() - l;
                                completableFuture.complete(PunchResult.success(this.socket, 1, nArray[0], nArray2[0], 0, l2));
                            }
                            continue;
                        }
                    }
                    if (by != 2) continue;
                    nArray2[0] = nArray2[0] + 1;
                    this.localConfirmed.set(true);
                    this.sendControlTo((byte)2, datagramPacket.getAddress(), datagramPacket.getPort());
                    Object object5 = object2 = object;
                    synchronized (object5) {
                        if ((this.localConfirmed.get() || this.remoteReceived.get()) && this.completed.compareAndSet(false, true)) {
                            this.holeOpen.set(true);
                            this.punching.set(false);
                            this.socketTransferred = true;
                            long l3 = System.currentTimeMillis() - l;
                            completableFuture.complete(PunchResult.success(this.socket, 1, nArray[0], nArray2[0], 0, l3));
                        }
                    }
                }
                catch (SocketTimeoutException socketTimeoutException) {
                }
                catch (IOException iOException) {
                    IOException iOException2 = iOException;
                    if (this.punching.get()) {
                        Object object6;
                        Object object7 = object6 = object;
                        synchronized (object7) {
                            if (this.completed.compareAndSet(false, true)) {
                                this.punching.set(false);
                                completableFuture.completeExceptionally(iOException2);
                            }
                        }
                    }
                    return;
                }
            }
        }, "VoxLink-PunchRecv");
        thread.setDaemon(true);
        this.recvThreadsRef = Collections.singletonList(thread);
        thread.start();
        Thread thread2 = new Thread(() -> {
            int n3 = 0;
            int n4 = this.effectiveTimeoutMs() / this.effectiveSendInterval();
            int n5 = 0;
            long l2 = System.currentTimeMillis();
            LOGGER.info("[UdpHolePuncher] Send thread start: target={}, port={}, range={}, local port={}", new Object[]{this.remoteAddress != null ? this.remoteAddress.getHostAddress() : "null", this.remotePort, n2, this.socket.getLocalPort()});
            while (this.punching.get() && !this.holeOpen.get() && n3 < n4) {
                if (bl2) {
                    int n6;
                    if (bl3) {
                        n6 = n2;
                    } else {
                        int n7 = n3 / this.profile().cyclesPerRange;
                        if (n7 >= this.profile().progressiveRanges.length) {
                            n7 = this.profile().progressiveRanges.length - 1;
                        }
                        n6 = Math.min(this.profile().progressiveRanges[n7], n2 > 0 ? n2 : this.effectivePortRange());
                    }
                    if (n5 < 5) {
                        LOGGER.info("[UdpHolePuncher] Send #{}: PUNCH to {}:{}\u00b1{} (cycle={}, fixed={}, local port={})", new Object[]{n5 + 1, this.remoteAddress.getHostAddress(), n, n6, n3, bl3, this.socket.getLocalPort()});
                    }
                    this.sendControlMultiPort((byte)1, n, n6, n3);
                } else {
                    if (n5 < 5) {
                        LOGGER.info("[UdpHolePuncher] Send #{}: PUNCH to {}:{} (cycle={}, local port={})", new Object[]{n5 + 1, this.remoteAddress.getHostAddress(), this.remotePort, n3, this.socket.getLocalPort()});
                    }
                    this.sendControl((byte)1);
                }
                ++n3;
                ++n5;
                try {
                    Thread.sleep(this.effectiveSendInterval());
                }
                catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.info("[UdpHolePuncher] Send thread end: cyclesPerformed={}, holeOpen={}, punching={}", new Object[]{n3, this.holeOpen.get(), this.punching.get()});
            if (!this.holeOpen.get() && this.punching.get()) {
                Object object2;
                Object object3 = object2 = object;
                synchronized (object3) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long l3 = System.currentTimeMillis() - l;
                        PunchResult punchResult = PunchResult.failure(1, nArray[0], nArray2[0], 0, l3, false);
                        completableFuture.complete(bl2 ? punchResult.withPortPrediction() : punchResult);
                    }
                }
            }
        }, "VoxLink-PunchSend");
        thread2.setDaemon(true);
        this.sendThreadRef = thread2;
        thread2.start();
        ScheduledFuture<?> scheduledFuture = UdpHolePuncher.scheduler().schedule(() -> {
            if (this.punching.get()) {
                Object object2;
                Object object3 = object2 = object;
                synchronized (object3) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long l2 = System.currentTimeMillis() - l;
                        PunchResult punchResult = PunchResult.failure(1, nArray[0], nArray2[0], 0, l2, false);
                        completableFuture.complete(bl2 ? punchResult.withPortPrediction() : punchResult);
                    }
                }
            }
        }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitMs), TimeUnit.MILLISECONDS);
        this.timeoutFuture = scheduledFuture;
        P2PBridge.registerPendingUdpTimeout(scheduledFuture);
        return completableFuture;
    }

    public CompletableFuture<PunchResult> punchEasySymDual(String string, int n, StunProbe.NatType natType, StunProbe.NatType natType2) {
        return this.punchEasySymDual(string, n, natType, natType2, this.profile().easySymDualSocketCount);
    }

    public CompletableFuture<PunchResult> punchEasySymDual(String string, int n, StunProbe.NatType natType, StunProbe.NatType natType2, int n2) {
        Object object3;
        Object object2;
        int n3 = n2 > 0 ? n2 : this.profile().easySymDualSocketCount;
        LOGGER.info("[UdpHolePuncher] EasySym mutual punch start: target={}:{}, sockets={}, range=+/-{}, local={}, remote={}, profile={}", new Object[]{string, n, n3, this.profile().easySymDualPortRange, natType.key, natType2.key, this.profile().describeInstance()});
        ArrayList<Object> arrayList = new ArrayList<Object>();
        for (int i = 0; i < n3; ++i) {
            object2 = new UdpHolePuncher();
            try {
                ((UdpHolePuncher)object2).createSocket();
            }
            catch (SocketException socketException) {
                LOGGER.warn("[UdpHolePuncher] EasySym socket#{} create failed: {}", (Object)i, (Object)socketException.getMessage());
                continue;
            }
            arrayList.add(object2);
        }
        if (arrayList.isEmpty()) {
            return UdpHolePuncher.failedFuture(new SocketException("EasySym\u5bf9\u6253: \u65e0\u53ef\u7528socket"));
        }
        ArrayList<Object> arrayList2 = arrayList;
        object2 = new ArrayList();
        for (Object object3 : arrayList) {
            ((ArrayList)object2).add(((UdpHolePuncher)object3).punchWithPortPrediction(string, n, this.profile().easySymDualPortRange, true));
        }
        CompletableFuture completableFuture = new CompletableFuture();
        object3 = new AtomicInteger(((ArrayList)object2).size());
        AtomicInteger atomicInteger = new AtomicInteger(0);
        AtomicInteger atomicInteger2 = new AtomicInteger(0);
        long l = System.currentTimeMillis();
        int n4 = arrayList2.size();
        for (int i = 0; i < ((ArrayList)object2).size(); ++i) {
            int n5 = i;
            ((CompletableFuture)((ArrayList)object2).get(i)).whenComplete((arg_0, arg_1) -> UdpHolePuncher.lambda$punchEasySymDual$10(completableFuture, n5, arrayList2, atomicInteger, atomicInteger2, (AtomicInteger)object3, l, n4, arg_0, arg_1));
        }
        return completableFuture;
    }

    private void sendControl(byte by) {
        byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], by, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
        DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, this.remoteAddress, this.remotePort);
        UdpHolePuncher.sendPkt(this.socket, datagramPacket);
    }

    private void sendControlMultiPort(byte by, int n, int n2, int n3) {
        byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], by, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
        InetAddress inetAddress = this.remoteAddress;
        if (inetAddress != null) {
            Object object;
            int n4;
            int n5;
            int n6;
            int n7;
            int n8 = this.remotePort;
            boolean bl = n2 > 20;
            ArrayList<Integer> arrayList = new ArrayList<Integer>();
            arrayList.add(n8);
            Random random = new Random();
            if (bl) {
                n7 = Math.max(1, n8 - n2);
                n6 = Math.min(65535, n8 + n2);
                n5 = n6 - n7 + 1;
                n4 = Math.min(this.profile().send.sweepWindowSize, n5 - 1);
                object = new HashSet<Integer>();
                ((HashSet)object).add(n8);
                while (((HashSet)object).size() < n4 + 1) {
                    ((HashSet)object).add(n7 + random.nextInt(n5));
                }
                arrayList.addAll((Collection<Integer>)object);
                Collections.shuffle(arrayList.subList(1, arrayList.size()), random);
            } else {
                for (n7 = 1; n7 <= n2; ++n7) {
                    n6 = n8 - n7;
                    n5 = n8 + n7;
                    if (n6 > 0) {
                        arrayList.add(n6);
                    }
                    if (n5 > 65535) continue;
                    arrayList.add(n5);
                }
            }
            LOGGER.info("[UdpHolePuncher] sendControlMultiPort: send to {} ports (x{} rounds x{} pass, round={}): {} (center={}, range=+/-{}, random={}, local port={})", new Object[]{arrayList.size(), this.effectiveMinRounds(), this.effectiveMinPass(), n3, arrayList.subList(0, Math.min(10, arrayList.size())), n8, n2, bl, this.socket.getLocalPort()});
            for (n7 = 0; n7 < this.effectiveMinRounds(); ++n7) {
                for (n6 = 0; n6 < arrayList.size(); ++n6) {
                    n5 = (Integer)arrayList.get(n6);
                    for (n4 = 0; n4 < this.effectiveMinPass(); ++n4) {
                        object = new DatagramPacket(byArray, byArray.length, inetAddress, n5);
                        UdpHolePuncher.sendPkt(this.socket, (DatagramPacket)object);
                    }
                    if (n6 >= arrayList.size() - 1) continue;
                    try {
                        Thread.sleep(this.profile().send.sleepShortMs);
                        continue;
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (n7 >= 2) continue;
                try {
                    Thread.sleep(this.profile().send.sleepLongMs);
                    continue;
                }
                catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void cancel() {
        List<Thread> list;
        List<UdpHolePuncher> list2;
        ScheduledFuture<?> scheduledFuture;
        CompletableFuture<PunchResult> completableFuture;
        if (this.punching.compareAndSet(true, false) && (completableFuture = this.activeResult) != null && this.completed.compareAndSet(false, true)) {
            completableFuture.completeExceptionally(new CancellationException("punch cancelled"));
        }
        if ((scheduledFuture = this.timeoutFuture) != null) {
            scheduledFuture.cancel(false);
            this.timeoutFuture = null;
        }
        if ((list2 = this.socketGroup) != null) {
            for (UdpHolePuncher object : list2) {
                DatagramSocket datagramSocket = object.getSocket();
                if (datagramSocket == null || datagramSocket.isClosed() || object.socketTransferred) continue;
                datagramSocket.close();
            }
            this.socketGroup = null;
        }
        if (this.socket != null && !this.socket.isClosed() && !this.socketTransferred) {
            this.socket.close();
        }
        if ((list = this.recvThreadsRef) != null) {
            for (Thread thread : list) {
                if (thread == null) continue;
                thread.interrupt();
            }
        }
        if (this.sendThreadRef != null) {
            this.sendThreadRef.interrupt();
        }
    }

    public void stopPunch() {
        List<Thread> list;
        ScheduledFuture<?> scheduledFuture;
        CompletableFuture<PunchResult> completableFuture;
        if (this.punching.compareAndSet(true, false) && (completableFuture = this.activeResult) != null && this.completed.compareAndSet(false, true)) {
            completableFuture.completeExceptionally(new CancellationException("punch stopped"));
        }
        if ((scheduledFuture = this.timeoutFuture) != null) {
            scheduledFuture.cancel(false);
            this.timeoutFuture = null;
        }
        if ((list = this.recvThreadsRef) != null) {
            for (Thread thread : list) {
                if (thread == null) continue;
                thread.interrupt();
            }
        }
        if (this.sendThreadRef != null) {
            this.sendThreadRef.interrupt();
        }
        this.holeOpen.set(false);
        this.remoteReceived.set(false);
        this.localConfirmed.set(false);
    }

    public synchronized void updateTarget(String string, int n) {
        try {
            this.remoteAddress = InetAddress.getByName(string);
            this.remotePort = n;
            LOGGER.info("[UdpHolePuncher] Target updated to {}:{}", (Object)string, (Object)n);
        }
        catch (Exception exception) {
            LOGGER.warn("[UdpHolePuncher] Target update failed: {}", (Object)exception.getMessage());
        }
    }

    public void setOnPeerPunchReceived(Consumer<InetSocketAddress> consumer) {
        this.onPeerPunchReceived = consumer;
    }

    public boolean isHoleOpen() {
        return this.holeOpen.get();
    }

    public InetSocketAddress getActualRemoteAddress() {
        InetAddress inetAddress = this.remoteAddress;
        int n = this.remotePort;
        return inetAddress != null && n > 0 ? new InetSocketAddress(inetAddress, n) : null;
    }

    public void waitForRecvThreadExit() {
        Thread thread;
        List<Thread> list = this.recvThreadsRef;
        if (list != null) {
            for (Thread thread2 : list) {
                if (thread2 == null || !thread2.isAlive()) continue;
                try {
                    thread2.join(2000L);
                }
                catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if ((thread = this.sendThreadRef) != null && thread.isAlive()) {
            try {
                thread.join(2000L);
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean waitPunchStop(long l) {
        long l2 = System.currentTimeMillis() + l;
        if (this.punching.get()) {
            this.stopPunch();
        }
        while (System.currentTimeMillis() < l2 && this.punching.get()) {
            try {
                Thread.sleep(20L);
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (this.punching.get()) {
            return false;
        }
        while (System.currentTimeMillis() < l2) {
            Thread thread;
            boolean bl = false;
            List<Thread> list = this.recvThreadsRef;
            if (list != null) {
                for (Thread thread2 : list) {
                    if (thread2 == null || !thread2.isAlive()) continue;
                    bl = true;
                }
            }
            if ((thread = this.sendThreadRef) != null && thread.isAlive()) {
                bl = true;
            }
            if (!bl) {
                return true;
            }
            try {
                Thread.sleep(20L);
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void close() {
        List<Thread> list;
        List<UdpHolePuncher> list2;
        ScheduledFuture<?> scheduledFuture;
        CompletableFuture<PunchResult> completableFuture;
        if (this.punching.compareAndSet(true, false) && (completableFuture = this.activeResult) != null && this.completed.compareAndSet(false, true)) {
            completableFuture.completeExceptionally(new CancellationException("punch closed"));
        }
        if ((scheduledFuture = this.timeoutFuture) != null) {
            scheduledFuture.cancel(false);
            this.timeoutFuture = null;
        }
        if ((list2 = this.socketGroup) != null) {
            for (UdpHolePuncher object : list2) {
                DatagramSocket datagramSocket = object.getSocket();
                if (datagramSocket == null || datagramSocket.isClosed() || object.socketTransferred) continue;
                datagramSocket.close();
            }
            this.socketGroup = null;
        }
        if (this.socket != null && !this.socket.isClosed() && !this.socketTransferred) {
            this.socket.close();
        }
        if ((list = this.recvThreadsRef) != null) {
            for (Thread thread : list) {
                if (thread == null) continue;
                thread.interrupt();
            }
        }
        if (this.sendThreadRef != null) {
            this.sendThreadRef.interrupt();
        }
    }

    private CompletableFuture<PunchResult> punchMultiSocketLegacy(String string, int n, List<UdpHolePuncher> list, AtomicBoolean atomicBoolean) {
        Object object;
        int n2;
        CompletableFuture<PunchResult> completableFuture = new CompletableFuture<PunchResult>();
        this.activeResult = completableFuture;
        this.socketGroup = list;
        Object object2 = new Object();
        Consumer<InetSocketAddress> consumer = this.onPeerPunchReceived;
        int[] nArray = new int[]{0};
        int[] nArray2 = new int[]{0};
        long l = System.currentTimeMillis();
        int n3 = list.size();
        byte[] byArray = new byte[]{MAGIC[0], MAGIC[1], 1, (byte)(this.sessionNonce >> 8), (byte)this.sessionNonce};
        int n4 = this.effectiveTimeoutMs() / this.effectiveSendInterval();
        LOGGER.info("[UdpHolePuncher] Multi-socket send start (Legacy): target={}:{}, sockets={}, profile={}", new Object[]{string, n, list.size(), this.profile().describeInstance()});
        ArrayList<Thread> arrayList = new ArrayList<Thread>();
        for (n2 = 0; n2 < list.size(); ++n2) {
            object = list.get(n2);
            int n5 = n2;
            DatagramSocket datagramSocket = ((UdpHolePuncher)object).getSocket();
            if (datagramSocket == null || datagramSocket.isClosed()) continue;
            try {
                datagramSocket.setSoTimeout(this.profile().send.socketTimeoutMs);
            }
            catch (Exception exception) {
                // empty catch block
            }
            Thread thread = new Thread(() -> this.lambda$punchMultiSocketLegacy$11(datagramSocket, nArray, (UdpHolePuncher)object, object2, atomicBoolean, n5, l, completableFuture, n3, nArray2, consumer), "VoxLink-PunchRecv-Legacy-" + n2);
            thread.setDaemon(true);
            arrayList.add(thread);
            this.recvThreadsRef = arrayList;
            thread.start();
        }
        this.recvThreadsRef = arrayList.isEmpty() ? null : arrayList;
        n2 = list.size() <= 1 || this.skipFirewallDetection ? 1 : 0;
        object = new Thread(() -> this.lambda$punchMultiSocketLegacy$12(n4, n2 != 0, object2, completableFuture, n3, nArray, nArray2, list, byArray, l), "VoxLink-PunchSend-Legacy");
        ((Thread)object).setDaemon(true);
        this.sendThreadRef = object;
        ((Thread)object).start();
        ScheduledFuture<?> scheduledFuture = UdpHolePuncher.scheduler().schedule(() -> {
            if (this.punching.get()) {
                Object object2;
                Object object3 = object2 = object2;
                synchronized (object3) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        long l2 = System.currentTimeMillis() - l;
                        completableFuture.complete(PunchResult.failure(n3, nArray[0], nArray2[0], 0, l2, false));
                    }
                }
            }
        }, (long)(this.effectiveTimeoutMs() + this.profile().send.extraWaitMs), TimeUnit.MILLISECONDS);
        this.timeoutFuture = scheduledFuture;
        P2PBridge.registerPendingUdpTimeout(scheduledFuture);
        return completableFuture;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private /* synthetic */ void lambda$punchMultiSocketLegacy$12(int n, boolean bl, Object object, CompletableFuture completableFuture, int n2, int[] nArray, int[] nArray2, List list, byte[] byArray, long l) {
        long l2 = System.currentTimeMillis();
        for (int i = 0; this.punching.get() && !this.holeOpen.get() && i < n; ++i) {
            Object object2;
            if (!bl && i >= this.profile().firewallDetectCycles && !this.remoteReceived.get()) {
                long l3 = System.currentTimeMillis() - l2;
                LOGGER.warn("[UdpHolePuncher] Multi-socket firewall check (Legacy): sent {} cycles/{}ms no reply, UDP blocked", (Object)i, (Object)l3);
                Object object3 = object2 = object;
                synchronized (object3) {
                    if (this.completed.compareAndSet(false, true)) {
                        this.punching.set(false);
                        completableFuture.complete(PunchResult.failure(n2, nArray[0], nArray2[0], 0, l3, true));
                    }
                    return;
                }
            }
            for (UdpHolePuncher udpHolePuncher : list) {
                object2 = udpHolePuncher.getSocket();
                if (object2 == null || ((DatagramSocket)object2).isClosed()) continue;
                for (int j = 0; j < 3; ++j) {
                    DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, this.remoteAddress, this.remotePort);
                    UdpHolePuncher.sendPkt((DatagramSocket)object2, datagramPacket);
                }
            }
            try {
                Thread.sleep(this.effectiveSendInterval());
                continue;
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!this.holeOpen.get() && this.punching.get()) {
            Object object4;
            Object object5 = object4 = object;
            synchronized (object5) {
                if (this.completed.compareAndSet(false, true)) {
                    this.punching.set(false);
                    long l4 = System.currentTimeMillis() - l;
                    completableFuture.complete(PunchResult.failure(n2, nArray[0], nArray2[0], 0, l4, false));
                }
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private /* synthetic */ void lambda$punchMultiSocketLegacy$11(DatagramSocket datagramSocket, int[] nArray, UdpHolePuncher udpHolePuncher, Object object, AtomicBoolean atomicBoolean, int n, long l, CompletableFuture completableFuture, int n2, int[] nArray2, Consumer consumer) {
        byte[] byArray = new byte[64];
        DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
        boolean bl = false;
        while (this.punching.get() && !this.holeOpen.get()) {
            try {
                long l2;
                Object object2;
                Object object3;
                datagramSocket.receive(datagramPacket);
                if (datagramPacket.getLength() < 3 || byArray[0] != MAGIC[0] || byArray[1] != MAGIC[1] || !datagramPacket.getAddress().equals(this.remoteAddress)) continue;
                byte by = byArray[2];
                if (by == 1) {
                    nArray[0] = nArray[0] + 1;
                    this.remoteReceived.set(true);
                    udpHolePuncher.sendControlTo((byte)2, datagramPacket.getAddress(), datagramPacket.getPort());
                    object2 = object3 = object;
                    synchronized (object2) {
                        if (this.localConfirmed.get() && atomicBoolean.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                            this.holeOpen.set(true);
                            this.punching.set(false);
                            udpHolePuncher.socketTransferred = true;
                            this.remoteAddress = datagramPacket.getAddress();
                            this.remotePort = datagramPacket.getPort();
                            LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (Legacy)", (Object)n);
                            l2 = System.currentTimeMillis() - l;
                            completableFuture.complete(PunchResult.success(datagramSocket, n2, nArray[0], nArray2[0], 0, l2));
                        }
                    }
                }
                if (by == 2) {
                    nArray2[0] = nArray2[0] + 1;
                    this.localConfirmed.set(true);
                    udpHolePuncher.sendControlTo((byte)2, datagramPacket.getAddress(), datagramPacket.getPort());
                    object2 = object3 = object;
                    synchronized (object2) {
                        if (this.remoteReceived.get() && atomicBoolean.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                            this.holeOpen.set(true);
                            this.punching.set(false);
                            udpHolePuncher.socketTransferred = true;
                            this.remoteAddress = datagramPacket.getAddress();
                            this.remotePort = datagramPacket.getPort();
                            LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (Legacy)", (Object)n);
                            l2 = System.currentTimeMillis() - l;
                            completableFuture.complete(PunchResult.success(datagramSocket, n2, nArray[0], nArray2[0], 0, l2));
                        }
                    }
                }
                if (bl || consumer == null) continue;
                bl = true;
                try {
                    consumer.accept(new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()));
                }
                catch (Exception exception) {}
            }
            catch (SocketTimeoutException socketTimeoutException) {
            }
            catch (IOException iOException) {
                if (!this.punching.get()) continue;
                return;
            }
        }
    }

    private static /* synthetic */ void lambda$punchEasySymDual$10(CompletableFuture completableFuture, int n, ArrayList arrayList, AtomicInteger atomicInteger, AtomicInteger atomicInteger2, AtomicInteger atomicInteger3, long l, int n2, Object object, Object object2) {
        PunchResult punchResult = (PunchResult)object;
        if (punchResult != null && punchResult.isSuccess()) {
            if (completableFuture.complete(punchResult)) {
                LOGGER.info("[UdpHolePuncher] EasySym socket#{} hit, cancel others", (Object)n);
                for (int i = 0; i < arrayList.size(); ++i) {
                    if (i == n) continue;
                    ((UdpHolePuncher)arrayList.get(i)).cancel();
                }
            } else {
                try {
                    punchResult.getSuccessSocket().close();
                }
                catch (Exception exception) {}
            }
        } else {
            if (punchResult != null) {
                atomicInteger.addAndGet(punchResult.socketsReceivedPunch);
                atomicInteger2.addAndGet(punchResult.socketsReceivedAck);
            }
            if (atomicInteger3.decrementAndGet() == 0 && !completableFuture.isDone()) {
                for (UdpHolePuncher udpHolePuncher : arrayList) {
                    try {
                        udpHolePuncher.close();
                    }
                    catch (Exception exception) {}
                }
                if (object2 != null) {
                    completableFuture.completeExceptionally((Throwable)object2);
                } else {
                    long l2 = System.currentTimeMillis() - l;
                    completableFuture.complete(PunchResult.failure(n2, atomicInteger.get(), atomicInteger2.get(), 0, l2, false).withPortPrediction());
                }
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private /* synthetic */ void lambda$punchMultiSocket$1(Selector selector, HashMap hashMap, HashMap hashMap2, int[] nArray, Object object, AtomicBoolean atomicBoolean, long l, CompletableFuture completableFuture, int n, int[] nArray2, Consumer consumer) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(64);
        boolean bl = false;
        try {
            while (this.punching.get() && !this.holeOpen.get()) {
                int n2 = selector.select(500L);
                if (n2 == 0) continue;
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    long l2;
                    Object object2;
                    Object object3;
                    InetSocketAddress inetSocketAddress;
                    SelectionKey selectionKey = iterator.next();
                    iterator.remove();
                    if (!selectionKey.isReadable()) continue;
                    DatagramChannel datagramChannel = (DatagramChannel)selectionKey.channel();
                    byteBuffer.clear();
                    try {
                        inetSocketAddress = (InetSocketAddress)datagramChannel.receive(byteBuffer);
                    }
                    catch (IOException iOException) {
                        continue;
                    }
                    if (inetSocketAddress == null || byteBuffer.position() < 3) continue;
                    byteBuffer.flip();
                    byte by = byteBuffer.get(0);
                    byte by2 = byteBuffer.get(1);
                    byte by3 = byteBuffer.get(2);
                    if (by != MAGIC[0] || by2 != MAGIC[1] || !this.acceptAddress(inetSocketAddress.getAddress(), by3)) continue;
                    UdpHolePuncher udpHolePuncher = (UdpHolePuncher)hashMap.get(datagramChannel);
                    int n3 = (Integer)hashMap2.get(datagramChannel);
                    if (by3 == 1) {
                        nArray[0] = nArray[0] + 1;
                        this.remoteReceived.set(true);
                        udpHolePuncher.sendControlTo((byte)2, inetSocketAddress.getAddress(), inetSocketAddress.getPort());
                        object2 = object3 = object;
                        synchronized (object2) {
                            if ((this.localConfirmed.get() || this.remoteReceived.get()) && atomicBoolean.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                                this.holeOpen.set(true);
                                this.punching.set(false);
                                udpHolePuncher.socketTransferred = true;
                                this.remoteAddress = inetSocketAddress.getAddress();
                                this.remotePort = inetSocketAddress.getPort();
                                LOGGER.info("[UdpHolePuncher] socket#{} received PUNCH, punch success (NIO)", (Object)n3);
                                l2 = System.currentTimeMillis() - l;
                                completableFuture.complete(PunchResult.success(udpHolePuncher.getSocket(), n, nArray[0], nArray2[0], 0, l2));
                            }
                        }
                    }
                    if (by3 == 2) {
                        nArray2[0] = nArray2[0] + 1;
                        this.localConfirmed.set(true);
                        udpHolePuncher.sendControlTo((byte)2, inetSocketAddress.getAddress(), inetSocketAddress.getPort());
                        object2 = object3 = object;
                        synchronized (object2) {
                            if ((this.localConfirmed.get() || this.remoteReceived.get()) && atomicBoolean.compareAndSet(false, true) && this.completed.compareAndSet(false, true)) {
                                this.holeOpen.set(true);
                                this.punching.set(false);
                                udpHolePuncher.socketTransferred = true;
                                this.remoteAddress = inetSocketAddress.getAddress();
                                this.remotePort = inetSocketAddress.getPort();
                                LOGGER.info("[UdpHolePuncher] socket#{} received ACK, punch success (NIO)", (Object)n3);
                                l2 = System.currentTimeMillis() - l;
                                completableFuture.complete(PunchResult.success(udpHolePuncher.getSocket(), n, nArray[0], nArray2[0], 0, l2));
                            }
                        }
                    }
                    if (bl || consumer == null) continue;
                    bl = true;
                    try {
                        consumer.accept(inetSocketAddress);
                    }
                    catch (Exception exception) {}
                }
            }
        }
        catch (IOException iOException) {
            LOGGER.debug("[UdpHolePuncher] Selector receive exception: {}", (Object)iOException.getMessage());
        }
        finally {
            try {
                selector.close();
            }
            catch (IOException iOException) {}
        }
    }
}

