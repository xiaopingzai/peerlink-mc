/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.StunCache;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class StunProbe {
    private static final int PROBE_TIMEOUT_MS = 8000;
    private static final int DISCOVER_TIMEOUT_MS = 800;
    private static final int DUAL_STUN_TIMEOUT_MS = 800;
    private static final int STUN_INITIAL_RTO_MS = 200;
    private static final int STUN_MAX_RETRANSMISSIONS = 2;
    private static final int STUN_MAX_RTO_MS = 800;
    private static final double STUN_JITTER_FACTOR = 0.2;
    private static final int STUN_RECV_POLL_MS = 100;
    private static final int STUN_DEFAULT_PORT = 3478;
    private static final int EASY_SYM_PORT_DELTA_THRESHOLD = 100;
    private static final int EASY_SYM_PORT_DELTA_FALLBACK = 200;
    private static final int ATTR_CHANGE_REQUEST = 3;
    private static final int CHANGE_IP_FLAG = 4;
    private static final int CHANGE_PORT_FLAG = 2;
    private static final int RFC5780_MAX_TRIES = 3;
    private static final long CACHE_TTL_MS = 300000L;
    private static final long WARM_REUSE_TTL_MS = 20000L;
    private static final int TIMEOUT_MULTIPLIER = 2;
    private static final int SYM_DETECT_TIMEOUT_MS = 1500;
    private static volatile ExecutorService STUN_EXECUTOR = StunProbe.createExecutor();
    private static final AtomicReference<CacheEntry> cachedEntry = new AtomicReference();
    private static volatile String lastProbeLocalIp;

    /*
     * Exception decompiling
     */
    public static PublicMappedAddress discoverMappedAddress(DatagramSocket var0, List<String> var1_1) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [1[TRYBLOCK]], but top level block is 41[SIMPLE_IF_TAKEN]
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
         *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
         *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
         *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:257)
         *     at org.benf.cfr.reader.Driver.doJar(Driver.java:139)
         *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
         *     at org.benf.cfr.reader.Main.main(Main.java:54)
         */
        throw new IllegalStateException("Decompilation failed");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static PublicMappedAddress[] discoverMappedAddressDual(DatagramSocket datagramSocket, String string, String string2) {
        int n = -1;
        try {
            n = datagramSocket.getSoTimeout();
            datagramSocket.setSoTimeout(100);
        }
        catch (Exception exception) {
            // empty catch block
        }
        VoxLinkMod.LOGGER.info("[StunProbe] Parallel dual STUN: {} + {}, socket port={}", new Object[]{string, string2, datagramSocket.getLocalPort()});
        PublicMappedAddress[] publicMappedAddressArray = new PublicMappedAddress[2];
        try {
            ParsedStunUrl parsedStunUrl = StunProbe.parseStunUrl(string);
            ParsedStunUrl parsedStunUrl2 = StunProbe.parseStunUrl(string2);
            if (parsedStunUrl == null || parsedStunUrl2 == null) {
                PublicMappedAddress[] publicMappedAddressArray2;
                if (parsedStunUrl != null) {
                    publicMappedAddressArray[0] = StunProbe.discoverMappedAddress(datagramSocket, Collections.singletonList(string));
                }
                if (parsedStunUrl2 != null) {
                    publicMappedAddressArray[1] = StunProbe.discoverMappedAddress(datagramSocket, Collections.singletonList(string2));
                }
                PublicMappedAddress[] publicMappedAddressArray3 = publicMappedAddressArray2 = publicMappedAddressArray;
                return publicMappedAddressArray3;
            }
            byte[] byArray = StunProbe.createBindingRequest();
            byte[] byArray2 = StunProbe.createBindingRequest();
            InetAddress inetAddress = InetAddress.getByName(parsedStunUrl.host);
            InetAddress inetAddress2 = InetAddress.getByName(parsedStunUrl2.host);
            datagramSocket.send(new DatagramPacket(byArray, byArray.length, inetAddress, parsedStunUrl.port));
            datagramSocket.send(new DatagramPacket(byArray2, byArray2.length, inetAddress2, parsedStunUrl2.port));
            byte[] byArray3 = new byte[576];
            DatagramPacket datagramPacket = new DatagramPacket(byArray3, byArray3.length);
            long l = System.currentTimeMillis();
            long l2 = l + 800L;
            long l3 = l + (long)StunProbe.resendDelay(1);
            int n2 = 0;
            while (true) {
                MappedAddress mappedAddress;
                if (n2 >= 2 || System.currentTimeMillis() >= l2) {
                    if (publicMappedAddressArray[0] == null) {
                        publicMappedAddressArray[0] = StunProbe.discoverMappedAddress(datagramSocket, Collections.singletonList(string));
                    }
                    if (publicMappedAddressArray[1] == null) {
                        publicMappedAddressArray[1] = StunProbe.discoverMappedAddress(datagramSocket, Collections.singletonList(string2));
                    }
                    break;
                }
                long l4 = System.currentTimeMillis();
                if (l4 >= l3) {
                    if (publicMappedAddressArray[0] == null) {
                        datagramSocket.send(new DatagramPacket(byArray, byArray.length, inetAddress, parsedStunUrl.port));
                    }
                    if (publicMappedAddressArray[1] == null) {
                        datagramSocket.send(new DatagramPacket(byArray2, byArray2.length, inetAddress2, parsedStunUrl2.port));
                    }
                    l3 = 0x3FFFFFFFFFFFFFFFL;
                }
                try {
                    datagramSocket.receive(datagramPacket);
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                byte[] byArray4 = new byte[datagramPacket.getLength()];
                System.arraycopy(datagramPacket.getData(), 0, byArray4, 0, datagramPacket.getLength());
                if (byArray4.length < 20) continue;
                if (publicMappedAddressArray[0] == null && StunProbe.matchTransaction(byArray4, byArray) && (mappedAddress = StunProbe.parseBindingResponse(byArray4, byArray)) != null) {
                    publicMappedAddressArray[0] = new PublicMappedAddress(mappedAddress.ip, mappedAddress.port);
                    ++n2;
                    continue;
                }
                if (publicMappedAddressArray[1] != null || !StunProbe.matchTransaction(byArray4, byArray2) || (mappedAddress = StunProbe.parseBindingResponse(byArray4, byArray2)) == null) continue;
                publicMappedAddressArray[1] = new PublicMappedAddress(mappedAddress.ip, mappedAddress.port);
                ++n2;
            }
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[StunProbe] Parallel dual STUN exception: {}", (Object)exception.getMessage());
        }
        finally {
            try {
                if (n >= 0) {
                    datagramSocket.setSoTimeout(n);
                }
            }
            catch (Exception exception) {}
        }
        VoxLinkMod.LOGGER.info("[StunProbe] Dual STUN results: [0]={}, [1]={}", (Object)publicMappedAddressArray[0], (Object)publicMappedAddressArray[1]);
        return publicMappedAddressArray;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static PublicMappedAddress[] discoverMappedAddressQuad(DatagramSocket datagramSocket, String string, String string2, String string3, String string4) {
        String[] stringArray = new String[]{string, string2, string3, string4};
        PublicMappedAddress[] publicMappedAddressArray = new PublicMappedAddress[4];
        int n = -1;
        try {
            n = datagramSocket.getSoTimeout();
            datagramSocket.setSoTimeout(100);
        }
        catch (Exception exception) {
            // empty catch block
        }
        VoxLinkMod.LOGGER.info("[StunProbe] Parallel 4 STUN: {}+{}+{}+{}, socket port={}", new Object[]{string, string2, string3, string4, datagramSocket.getLocalPort()});
        try {
            ParsedStunUrl[] parsedStunUrlArray = new ParsedStunUrl[4];
            byte[][] byArrayArray = new byte[4][];
            InetAddress[] inetAddressArray = new InetAddress[4];
            int n2 = 0;
            for (int i = 0; i < 4; ++i) {
                parsedStunUrlArray[i] = StunProbe.parseStunUrl(stringArray[i]);
                if (parsedStunUrlArray[i] == null) continue;
                byArrayArray[i] = StunProbe.createBindingRequest();
                inetAddressArray[i] = InetAddress.getByName(parsedStunUrlArray[i].host);
                datagramSocket.send(new DatagramPacket(byArrayArray[i], byArrayArray[i].length, inetAddressArray[i], parsedStunUrlArray[i].port));
                ++n2;
            }
            if (n2 == 0) {
                PublicMappedAddress[] publicMappedAddressArray2 = publicMappedAddressArray;
                return publicMappedAddressArray2;
            }
            byte[] byArray = new byte[576];
            DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
            long l = System.currentTimeMillis();
            long l2 = l + 800L;
            long l3 = l + (long)StunProbe.resendDelay(1);
            int n3 = 0;
            block19: while (n3 < n2 && System.currentTimeMillis() < l2) {
                long l4 = System.currentTimeMillis();
                if (l4 >= l3) {
                    for (int i = 0; i < 4; ++i) {
                        if (byArrayArray[i] == null || publicMappedAddressArray[i] != null) continue;
                        datagramSocket.send(new DatagramPacket(byArrayArray[i], byArrayArray[i].length, inetAddressArray[i], parsedStunUrlArray[i].port));
                    }
                    l3 = 0x3FFFFFFFFFFFFFFFL;
                }
                try {
                    datagramSocket.receive(datagramPacket);
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    continue;
                }
                byte[] byArray2 = new byte[datagramPacket.getLength()];
                System.arraycopy(datagramPacket.getData(), 0, byArray2, 0, datagramPacket.getLength());
                if (byArray2.length < 20) continue;
                for (int i = 0; i < 4; ++i) {
                    if (publicMappedAddressArray[i] != null || byArrayArray[i] == null || !StunProbe.matchTransaction(byArray2, byArrayArray[i])) continue;
                    MappedAddress mappedAddress = StunProbe.parseBindingResponse(byArray2, byArrayArray[i]);
                    if (mappedAddress == null) continue block19;
                    publicMappedAddressArray[i] = new PublicMappedAddress(mappedAddress.ip, mappedAddress.port);
                    ++n3;
                    continue block19;
                }
            }
            for (int i = 0; i < 2; ++i) {
                if (publicMappedAddressArray[i] != null || parsedStunUrlArray[i] == null) continue;
                publicMappedAddressArray[i] = StunProbe.discoverMappedAddress(datagramSocket, Collections.singletonList(stringArray[i]));
            }
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[StunProbe] Parallel 4 STUN exception: {}", (Object)exception.getMessage());
        }
        finally {
            try {
                if (n >= 0) {
                    datagramSocket.setSoTimeout(n);
                }
            }
            catch (Exception exception) {}
        }
        VoxLinkMod.LOGGER.info("[StunProbe] 4 STUN results: [0]={}, [1]={}, [2]={}, [3]={}", new Object[]{publicMappedAddressArray[0], publicMappedAddressArray[1], publicMappedAddressArray[2], publicMappedAddressArray[3]});
        return publicMappedAddressArray;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static PublicMappedAddress[] discoverMappedAddressRace(DatagramSocket datagramSocket, List<String> list, int n) {
        PublicMappedAddress[] publicMappedAddressArray = new PublicMappedAddress[n];
        if (list != null && !list.isEmpty() && n > 0) {
            int n2 = -1;
            try {
                n2 = datagramSocket.getSoTimeout();
                datagramSocket.setSoTimeout(100);
            }
            catch (Exception exception) {
                // empty catch block
            }
            int n3 = list.size();
            ParsedStunUrl[] parsedStunUrlArray = new ParsedStunUrl[n3];
            byte[][] byArrayArray = new byte[n3][];
            VoxLinkMod.LOGGER.info("[StunProbe] Parallel race {} STUN take {} , socket port={}", new Object[]{n3, n, datagramSocket.getLocalPort()});
            int n4 = 0;
            try {
                InetAddress[] inetAddressArray = new InetAddress[n3];
                boolean[] blArray = new boolean[n3];
                for (int i = 0; i < n3; ++i) {
                    parsedStunUrlArray[i] = StunProbe.parseStunUrl(list.get(i));
                    if (parsedStunUrlArray[i] == null) continue;
                    byArrayArray[i] = StunProbe.createBindingRequest();
                    inetAddressArray[i] = InetAddress.getByName(parsedStunUrlArray[i].host);
                    datagramSocket.send(new DatagramPacket(byArrayArray[i], byArrayArray[i].length, inetAddressArray[i], parsedStunUrlArray[i].port));
                }
                byte[] byArray = new byte[576];
                DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
                long l = System.currentTimeMillis();
                long l2 = l + 800L;
                long l3 = l + (long)StunProbe.resendDelay(1);
                block16: while (n4 < n) {
                    if (System.currentTimeMillis() >= l2) {
                        break;
                    }
                    long l4 = System.currentTimeMillis();
                    if (l4 >= l3) {
                        for (int i = 0; i < n3; ++i) {
                            if (byArrayArray[i] == null || blArray[i]) continue;
                            datagramSocket.send(new DatagramPacket(byArrayArray[i], byArrayArray[i].length, inetAddressArray[i], parsedStunUrlArray[i].port));
                        }
                        l3 = 0x3FFFFFFFFFFFFFFFL;
                    }
                    try {
                        datagramSocket.receive(datagramPacket);
                    }
                    catch (SocketTimeoutException socketTimeoutException) {
                        continue;
                    }
                    byte[] byArray2 = new byte[datagramPacket.getLength()];
                    System.arraycopy(datagramPacket.getData(), 0, byArray2, 0, datagramPacket.getLength());
                    if (byArray2.length < 20) continue;
                    for (int i = 0; i < n3; ++i) {
                        if (byArrayArray[i] == null || blArray[i] || !StunProbe.matchTransaction(byArray2, byArrayArray[i])) continue;
                        MappedAddress mappedAddress = StunProbe.parseBindingResponse(byArray2, byArrayArray[i]);
                        if (mappedAddress == null) continue block16;
                        blArray[i] = true;
                        publicMappedAddressArray[n4] = new PublicMappedAddress(mappedAddress.ip, mappedAddress.port);
                        VoxLinkMod.LOGGER.info("[StunProbe] Race #{}: {} -> {}:{}", new Object[]{++n4, list.get(i), mappedAddress.ip, mappedAddress.port});
                        continue block16;
                    }
                }
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("[StunProbe] Parallel race exception: {}", (Object)exception.getMessage());
            }
            finally {
                try {
                    if (n2 >= 0) {
                        datagramSocket.setSoTimeout(n2);
                    }
                }
                catch (Exception exception) {}
            }
            VoxLinkMod.LOGGER.info("[StunProbe] Race done: got {}/{}", (Object)n4, (Object)n);
            return publicMappedAddressArray;
        }
        return publicMappedAddressArray;
    }

    private static boolean matchTransaction(byte[] byArray, byte[] byArray2) {
        if (byArray.length < 20) {
            return false;
        }
        for (int i = 8; i < 20; ++i) {
            if (byArray[i] == byArray2[i]) continue;
            return false;
        }
        return true;
    }

    private static int resendDelay(int n) {
        int n2 = 200 << n;
        n2 = Math.min(n2, 800);
        int n3 = (int)((double)n2 * 0.2);
        return n2 - n3 + ThreadLocalRandom.current().nextInt(0, 2 * n3 + 1);
    }

    public static void shutdown() {
        ExecutorService executorService = STUN_EXECUTOR;
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(2L, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            }
            catch (InterruptedException interruptedException) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "VoxLink-STUN");
            thread.setDaemon(true);
            return thread;
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static ExecutorService executor() {
        ExecutorService executorService = STUN_EXECUTOR;
        if (executorService != null && !executorService.isShutdown()) {
            return STUN_EXECUTOR;
        }
        Class<StunProbe> clazz = StunProbe.class;
        Class<StunProbe> clazz2 = StunProbe.class;
        synchronized (StunProbe.class) {
            if (STUN_EXECUTOR != null && !STUN_EXECUTOR.isShutdown()) {
                // ** MonitorExit[var2_2] (shouldn't be in output)
                return STUN_EXECUTOR;
            }
            STUN_EXECUTOR = StunProbe.createExecutor();
            // ** MonitorExit[var2_2] (shouldn't be in output)
            return STUN_EXECUTOR;
        }
    }

    public static String getLastProbeLocalIp() {
        return lastProbeLocalIp;
    }

    public static void invalidateCache() {
        cachedEntry.set(null);
        StunCache.clear();
    }

    public static boolean isNetworkChanged() {
        String string = lastProbeLocalIp;
        if (string != null && !"unknown".equals(string)) {
            String string2 = StunProbe.detectLocalIp();
            return !string.equals(string2);
        }
        return false;
    }

    private static String detectLocalIp() {
        DatagramSocket datagramSocket;
        try {
            datagramSocket = new DatagramSocket();
        }
        catch (SocketException socketException) {
            return "unknown";
        }
        try {
            datagramSocket.connect(InetAddress.getByName("8.8.8.8"), 53);
            String string = datagramSocket.getLocalAddress().getHostAddress();
            datagramSocket.close();
            return string;
        }
        catch (Throwable throwable) {
            try {
                try {
                    datagramSocket.close();
                }
                catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                }
                throw throwable;
            }
            catch (Exception exception) {
                return "unknown";
            }
        }
    }

    public static ProbeResult getCachedResult() {
        CacheEntry cacheEntry = cachedEntry.get();
        return cacheEntry != null && System.currentTimeMillis() - cacheEntry.timestamp < 20000L ? cacheEntry.result : null;
    }

    private static void setCachedResult(ProbeResult probeResult) {
        cachedEntry.set(new CacheEntry(probeResult, System.currentTimeMillis()));
    }

    public static CompletableFuture<ProbeResult> probeAsync(List<List<String>> list) {
        return CompletableFuture.supplyAsync(() -> {
            ProbeResult probeResult = StunProbe.probe(list);
            StunProbe.setCachedResult(probeResult);
            if (!probeResult.serverResults.isEmpty()) {
                StunServerResult stunServerResult = null;
                ArrayList<String> arrayList = new ArrayList<String>();
                for (StunServerResult stunServerResult2 : probeResult.serverResults) {
                    if (!stunServerResult2.reachable) continue;
                    arrayList.add(stunServerResult2.url);
                    if (stunServerResult != null || stunServerResult2.mappedIp == null || stunServerResult2.mappedPort <= 0) continue;
                    stunServerResult = stunServerResult2;
                }
                if (stunServerResult != null) {
                    StunCache.save(probeResult.natType.key, stunServerResult.mappedIp, stunServerResult.mappedPort, arrayList);
                }
            }
            return probeResult;
        }, StunProbe.executor());
    }

    private static NatType parseNatType(String string) {
        for (NatType natType : NatType.values()) {
            if (!natType.key.equals(string)) continue;
            return natType;
        }
        return NatType.UNKNOWN;
    }

    public static ProbeResult probe(List<List<String>> list) {
        lastProbeLocalIp = StunProbe.detectLocalIp();
        ArrayList<CompletableFuture<StunServerResult>> arrayList2 = new ArrayList<CompletableFuture<StunServerResult>>();
        for (List<String> arrayList3 : list) {
            for (String string : arrayList3) {
                arrayList2.add(StunProbe.probeSingleServerAsync(string));
            }
        }
        CountDownLatch countDownLatch = new CountDownLatch(2);
        for (CompletableFuture completableFuture : arrayList2) {
            completableFuture.whenComplete((stunServerResult, throwable) -> {
                if (stunServerResult != null && stunServerResult.reachable) {
                    countDownLatch.countDown();
                }
            });
        }
        try {
            countDownLatch.await(10000L, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        ArrayList<StunServerResult> arrayList = new ArrayList<StunServerResult>();
        for (CompletableFuture completableFuture : arrayList2) {
            try {
                arrayList.add(completableFuture.getNow(new StunServerResult("", "", 0, false, -1L, null, 0)));
            }
            catch (Exception exception) {}
        }
        NatType natType = StunProbe.detectNatType(arrayList);
        long l = arrayList.stream().filter(stunServerResult -> stunServerResult.reachable).count();
        VoxLinkMod.LOGGER.info("[StunProbe] NAT={}, reachable={}/{}", new Object[]{natType.key, l, arrayList.size()});
        return new ProbeResult(natType, arrayList);
    }

    private static CompletableFuture<StunServerResult> probeSingleServerAsync(String string) {
        return CompletableFuture.supplyAsync(() -> StunProbe.probeSingleServer(string), StunProbe.executor());
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static StunServerResult probeSingleServer(String string) {
        StunServerResult stunServerResult;
        Object object;
        ParsedStunUrl parsedStunUrl = StunProbe.parseStunUrl(string);
        if (parsedStunUrl == null) {
            return new StunServerResult(string, false, -1L, null);
        }
        DatagramSocket datagramSocket = null;
        try {
            InetAddress inetAddress = InetAddress.getByName(parsedStunUrl.host);
            datagramSocket = new DatagramSocket();
            datagramSocket.setSoTimeout(8000);
            object = StunProbe.createBindingRequest();
            long l = System.nanoTime();
            DatagramPacket datagramPacket = new DatagramPacket((byte[])object, ((byte[])object).length, inetAddress, parsedStunUrl.port);
            datagramSocket.send(datagramPacket);
            byte[] byArray = new byte[576];
            DatagramPacket datagramPacket2 = new DatagramPacket(byArray, byArray.length);
            datagramSocket.receive(datagramPacket2);
            long l2 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - l);
            byte[] byArray2 = new byte[datagramPacket2.getLength()];
            System.arraycopy(datagramPacket2.getData(), 0, byArray2, 0, datagramPacket2.getLength());
            MappedAddress mappedAddress = StunProbe.parseBindingResponse(byArray2, object);
            if (mappedAddress != null) {
                StunServerResult stunServerResult2;
                VoxLinkMod.LOGGER.debug("[StunProbe] {} reachable, latency={}ms, mapped={}:{}", new Object[]{string, l2, mappedAddress.ip, mappedAddress.port});
                StunServerResult stunServerResult3 = stunServerResult2 = new StunServerResult(string, parsedStunUrl.host, parsedStunUrl.port, true, l2, mappedAddress.ip, mappedAddress.port);
                return stunServerResult3;
            }
            VoxLinkMod.LOGGER.debug("[StunProbe] {} has response but no mapped address", (Object)string);
            stunServerResult = new StunServerResult(string, parsedStunUrl.host, parsedStunUrl.port, true, l2, null, 0);
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[StunProbe] {} unreachable: {}", (Object)string, (Object)exception.getMessage());
            Object object2 = object = (Object)new StunServerResult(string, parsedStunUrl.host, parsedStunUrl.port, false, -1L, null, 0);
            return object2;
        }
        finally {
            if (datagramSocket != null) {
                try {
                    datagramSocket.close();
                }
                catch (Exception exception) {}
            }
        }
        return stunServerResult;
    }

    public static NatType probeNatType(List<List<String>> list) {
        try {
            ProbeResult probeResult = StunProbe.probe(list);
            return probeResult != null ? probeResult.natType : null;
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[StunProbe] NAT type probe failed: {}", (Object)exception.getMessage());
            return null;
        }
    }

    private static byte[] createBindingRequestWithChange(boolean bl, boolean bl2) {
        int n = (bl ? 4 : 0) | (bl2 ? 2 : 0);
        byte[] byArray = new byte[28];
        byArray[0] = 0;
        byArray[1] = 1;
        byArray[2] = 0;
        byArray[3] = 8;
        byArray[4] = 33;
        byArray[5] = 18;
        byArray[6] = -92;
        byArray[7] = 66;
        byte[] byArray2 = new byte[12];
        ThreadLocalRandom.current().nextBytes(byArray2);
        System.arraycopy(byArray2, 0, byArray, 8, 12);
        byArray[20] = 0;
        byArray[21] = 3;
        byArray[22] = 0;
        byArray[23] = 4;
        byArray[24] = 0;
        byArray[25] = 0;
        byArray[26] = 0;
        byArray[27] = (byte)n;
        return byArray;
    }

    private static InetSocketAddress sendChangeRequest(DatagramSocket datagramSocket, String string, int n, boolean bl, boolean bl2) {
        try {
            InetAddress inetAddress = InetAddress.getByName(string);
            byte[] byArray = StunProbe.createBindingRequestWithChange(bl, bl2);
            datagramSocket.send(new DatagramPacket(byArray, byArray.length, inetAddress, n));
            byte[] byArray2 = new byte[576];
            DatagramPacket datagramPacket = new DatagramPacket(byArray2, byArray2.length);
            try {
                datagramSocket.receive(datagramPacket);
            }
            catch (SocketTimeoutException socketTimeoutException) {
                return null;
            }
            byte[] byArray3 = new byte[datagramPacket.getLength()];
            System.arraycopy(datagramPacket.getData(), 0, byArray3, 0, datagramPacket.getLength());
            if (byArray3.length >= 20 && StunProbe.matchTransaction(byArray3, byArray)) {
                int n2 = (byArray3[0] & 0xFF) << 8 | byArray3[1] & 0xFF;
                if (n2 == 273) {
                    VoxLinkMod.LOGGER.info("[StunProbe] STUN error response, RFC5780 not supported");
                    return null;
                }
                return n2 != 257 ? null : (InetSocketAddress)datagramPacket.getSocketAddress();
            }
            return null;
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[StunProbe] CHANGE-REQUEST failed: {}", (Object)exception.getMessage());
            return null;
        }
    }

    private static NatType detectNatTypeWithRfc5780(DatagramSocket datagramSocket, String string, int n, int n2) {
        throw new IllegalStateException("Decompilation failed");
    }

    private static NatType detectNatType(List<StunServerResult> list) {
        List<StunServerResult> list2 = list.stream().filter(stunServerResult -> stunServerResult.reachable && stunServerResult.mappedIp != null).collect(Collectors.toList());
        if (list2.size() < 2) {
            VoxLinkMod.LOGGER.info("[StunProbe] Not enough reachable servers ({}), cannot determine NAT type", (Object)list2.size());
            return NatType.UNKNOWN;
        }
        DatagramSocket datagramSocket = null;
        try {
            byte[] byArray;
            Object object;
            int n;
            Object object2;
            StunServerResult stunServerResult22;
            DatagramPacket datagramPacket = null;
            int n2 = 0;
            datagramSocket = new DatagramSocket();
            datagramSocket.setSoTimeout(8000);
            int n3 = 0;
            for (StunServerResult stunServerResult22 : list2) {
                if (n3 >= 3) break;
                ++n3;
                object2 = StunProbe.detectNatTypeWithRfc5780(datagramSocket, stunServerResult22.host, stunServerResult22.port, stunServerResult22.port + 1);
                if (object2 == null) continue;
                VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 determined: {} -> {}", (Object)stunServerResult22.url, (Object)object2.key);
                Object object3 = object2;
                return object3;
            }
            VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 all failed ({} tried), fallback to two-server delta method", (Object)n3);
            StunServerResult stunServerResult3 = (StunServerResult)list2.get(0);
            stunServerResult22 = (StunServerResult)list2.get(1);
            object2 = InetAddress.getByName(stunServerResult3.host);
            InetAddress inetAddress = InetAddress.getByName(stunServerResult22.host);
            byte[] byArray2 = StunProbe.createBindingRequest();
            byte[] byArray3 = StunProbe.createBindingRequest();
            datagramSocket.send(new DatagramPacket(byArray2, byArray2.length, (InetAddress)object2, stunServerResult3.port));
            datagramSocket.send(new DatagramPacket(byArray3, byArray3.length, inetAddress, stunServerResult22.port));
            MappedAddress mappedAddress = null;
            MappedAddress mappedAddress2 = null;
            byte[] byArray4 = new byte[576];
            long l = System.currentTimeMillis() + 8000L;
            for (n = 0; n < 2 && System.currentTimeMillis() < l; ++n) {
                MappedAddress mappedAddress3;
                object = new DatagramPacket(byArray4, byArray4.length);
                try {
                    datagramSocket.receive((DatagramPacket)object);
                }
                catch (SocketTimeoutException socketTimeoutException) {
                    break;
                }
                byArray = new byte[((DatagramPacket)object).getLength()];
                System.arraycopy(((DatagramPacket)object).getData(), 0, byArray, 0, ((DatagramPacket)object).getLength());
                if (mappedAddress == null) {
                    mappedAddress3 = StunProbe.parseBindingResponse(byArray, byArray2);
                    if (mappedAddress3 != null) {
                        mappedAddress = mappedAddress3;
                        continue;
                    }
                    mappedAddress3 = StunProbe.parseBindingResponse(byArray, byArray3);
                    if (mappedAddress3 == null) continue;
                    mappedAddress2 = mappedAddress3;
                    continue;
                }
                mappedAddress3 = StunProbe.parseBindingResponse(byArray, byArray3);
                if (mappedAddress3 != null) {
                    mappedAddress2 = mappedAddress3;
                    continue;
                }
                mappedAddress3 = StunProbe.parseBindingResponse(byArray, byArray2);
                if (mappedAddress3 == null) continue;
                mappedAddress = mappedAddress3;
            }
            if (mappedAddress == null || mappedAddress2 == null) {
                NatType natType = NatType.UNKNOWN;
                return natType;
            }
            if (mappedAddress.ip.equals(mappedAddress2.ip) && mappedAddress.port == mappedAddress2.port) {
                VoxLinkMod.LOGGER.info("[StunProbe] Non-symmetric NAT, mapped addresses same ({}:{})", (Object)mappedAddress.ip, (Object)mappedAddress.port);
                NatType natType = NatType.PORT_RESTRICTED_CONE;
                return natType;
            }
            VoxLinkMod.LOGGER.info("[StunProbe] Mapped differs across servers ({}:{} vs {}:{}), confirm symmetric via same-server alt-port", new Object[]{mappedAddress.ip, mappedAddress.port, mappedAddress2.ip, mappedAddress2.port});
            n = 0;
            for (int i = 1; i <= 3 && n == 0 && (n2 = stunServerResult3.port + i) <= 65535; ++i) {
                try {
                    object = StunProbe.createBindingRequest();
                    datagramSocket.send(new DatagramPacket((byte[])object, ((Object)object).length, (InetAddress)object2, n2));
                    datagramPacket = new DatagramPacket(byArray4, byArray4.length);
                    try {
                        datagramSocket.receive(datagramPacket);
                    }
                    catch (SocketTimeoutException socketTimeoutException) {
                        continue;
                    }
                    byArray = new byte[datagramPacket.getLength()];
                    System.arraycopy(datagramPacket.getData(), 0, byArray, 0, datagramPacket.getLength());
                    MappedAddress mappedAddress4 = StunProbe.parseBindingResponse(byArray, (byte[])object);
                    if (mappedAddress4 == null) continue;
                    VoxLinkMod.LOGGER.info("[StunProbe] Same-server alt-port {}:{} mapped {}:{}", new Object[]{stunServerResult3.host, n2, mappedAddress4.ip, mappedAddress4.port});
                    if (mappedAddress4.port == mappedAddress.port) continue;
                    n = 1;
                    continue;
                }
                catch (Exception exception) {
                    break;
                }
            }
            if (n == 0) {
                VoxLinkMod.LOGGER.info("[StunProbe] Same-server alt-port not confirmed, treat as PortRestricted (per-destination mapping stable)");
                NatType natType = NatType.PORT_RESTRICTED_CONE;
                return natType;
            }
            VoxLinkMod.LOGGER.info("[StunProbe] Confirmed symmetric NAT (same-server alt-port differs), determine direction");
            NatType natType = StunProbe.detectEasySymmetric(datagramSocket, mappedAddress.port, list2);
            if (natType != null) {
                return natType;
            }
            int n4 = mappedAddress2.port - mappedAddress.port;
            int n5 = Math.abs(n4);
            if (!mappedAddress.ip.equals(mappedAddress2.ip)) {
                VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback: public IP differs ({} vs {}), determined HardSym", (Object)mappedAddress.ip, (Object)mappedAddress2.ip);
                return NatType.SYMMETRIC;
            }
            int n6 = mappedAddress.port > 0 && mappedAddress2.port > 0 ? 100 : 200;
            int n7 = n6;
            if (n5 > 0 && n5 < n6) {
                NatType natType2 = n4 > 0 ? NatType.SYMMETRIC_EASY_INC : NatType.SYMMETRIC_EASY_DEC;
                VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback (3-sample failed): {}->{} diff={}, threshold={}, determined {}", new Object[]{mappedAddress.port, mappedAddress2.port, n4, n6, natType2.key});
                VoxLinkMod.LOGGER.debug("Port delta {} -> {}", (Object)n4, (Object)natType2.key);
                NatType natType3 = natType2;
                return natType3;
            }
            VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback: {}->{} diff={}>={}, threshold={}, determined HardSym", new Object[]{mappedAddress.port, mappedAddress2.port, n5, n6, n6});
            VoxLinkMod.LOGGER.debug("Port delta {} -> {}", (Object)n4, (Object)NatType.SYMMETRIC.key);
            NatType natType4 = NatType.SYMMETRIC;
            return natType4;
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("[StunProbe] NAT type detection failed: {}", (Object)exception.getMessage());
            return NatType.UNKNOWN;
        }
    }

    private static NatType detectEasySymmetric(DatagramSocket datagramSocket, int n, List<StunServerResult> list) {
        StunServerResult stunServerResult;
        if (list.size() >= 3) {
            stunServerResult = list.get(2);
        } else {
            if (list.size() < 1) {
                VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: no STUN available, skip 3rd sample");
                return null;
            }
            stunServerResult = list.get(0);
            VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: only {} reachable STUN, reuse {}:{} for 3rd sample", new Object[]{list.size(), stunServerResult.host, stunServerResult.port});
        }
        try {
            datagramSocket.setSoTimeout(1500);
            InetAddress inetAddress = InetAddress.getByName(stunServerResult.host);
            byte[] byArray = StunProbe.createBindingRequest();
            datagramSocket.send(new DatagramPacket(byArray, byArray.length, inetAddress, stunServerResult.port));
            byte[] byArray2 = new byte[576];
            DatagramPacket datagramPacket = new DatagramPacket(byArray2, byArray2.length);
            datagramSocket.receive(datagramPacket);
            byte[] byArray3 = new byte[datagramPacket.getLength()];
            System.arraycopy(datagramPacket.getData(), 0, byArray3, 0, datagramPacket.getLength());
            MappedAddress mappedAddress = StunProbe.parseBindingResponse(byArray3, byArray);
            if (mappedAddress != null) {
                int n2 = mappedAddress.port - n;
                int n3 = Math.abs(n2);
                int n4 = n > 0 && mappedAddress.port > 0 ? 100 : 200;
                VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection (same socket 3rd server): basePort={}, extraPort={}, diff={}, threshold={}", new Object[]{n, mappedAddress.port, n2, n4});
                if (n2 > 0 && n2 < n4) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym increment (port+{}, threshold={})", (Object)n2, (Object)n4);
                    VoxLinkMod.LOGGER.debug("Port delta {} -> {}", (Object)n2, (Object)NatType.SYMMETRIC_EASY_INC.key);
                    return NatType.SYMMETRIC_EASY_INC;
                }
                if (n2 < 0 && n2 > -n4) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym decrement (port{}, threshold={})", (Object)n2, (Object)n4);
                    VoxLinkMod.LOGGER.debug("Port delta {} -> {}", (Object)n2, (Object)NatType.SYMMETRIC_EASY_DEC.key);
                    return NatType.SYMMETRIC_EASY_DEC;
                }
                if (n3 >= n4) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: diff={}>={}, determined HardSym", (Object)n3, (Object)n4);
                    VoxLinkMod.LOGGER.debug("Port delta {} -> {}", (Object)n2, (Object)NatType.SYMMETRIC.key);
                    return NatType.SYMMETRIC;
                }
                VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: diff=0 (NAT reuses port), still EasySym");
                return NatType.SYMMETRIC_EASY_INC;
            }
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[StunProbe] EasySym detection failed: {}", (Object)exception.getMessage());
        }
        return null;
    }

    private static byte[] createBindingRequest() {
        byte[] byArray = new byte[20];
        byArray[0] = 0;
        byArray[1] = 1;
        byArray[2] = 0;
        byArray[3] = 0;
        byArray[4] = 33;
        byArray[5] = 18;
        byArray[6] = -92;
        byArray[7] = 66;
        byte[] byArray2 = new byte[12];
        ThreadLocalRandom.current().nextBytes(byArray2);
        System.arraycopy(byArray2, 0, byArray, 8, 12);
        return byArray;
    }

    private static MappedAddress parseBindingResponse(byte[] byArray, byte[] byArray2) {
        if (byArray.length < 20) {
            return null;
        }
        int n = (byArray[0] & 0xFF) << 8 | byArray[1] & 0xFF;
        if (n != 257) {
            return null;
        }
        if (byArray[4] == 33 && byArray[5] == 18 && byArray[6] == -92 && byArray[7] == 66) {
            int n2;
            for (n2 = 8; n2 < 20; ++n2) {
                if (byArray[n2] == byArray2[n2]) continue;
                return null;
            }
            n2 = (byArray[2] & 0xFF) << 8 | byArray[3] & 0xFF;
            int n3 = byArray.length - 20;
            if (n2 > n3) {
                n2 = n3;
            }
            VoxLinkMod.LOGGER.info("[StunProbe] Binding response: dataLen={}, msgLen={}", (Object)byArray.length, (Object)n2);
            int n4 = 20;
            while (n4 + 4 <= byArray.length && n4 - 20 < n2) {
                int n5 = (byArray[n4] & 0xFF) << 8 | byArray[n4 + 1] & 0xFF;
                int n6 = (byArray[n4 + 2] & 0xFF) << 8 | byArray[n4 + 3] & 0xFF;
                if (n4 + 4 + n6 > byArray.length) break;
                if (n5 == 32) {
                    return StunProbe.parseXorMappedAddress(byArray, n4, n6, byArray2);
                }
                if (n5 == 1) {
                    return StunProbe.parseMappedAddress(byArray, n4, n6);
                }
                if (n5 == 32800) {
                    return StunProbe.parseXorMappedAddress(byArray, n4, n6, byArray2);
                }
                if (n5 == 32808) {
                    VoxLinkMod.LOGGER.debug("[StunProbe] Skip FINGERPRINT");
                } else if (n5 == 32802) {
                    VoxLinkMod.LOGGER.debug("[StunProbe] Skip SOFTWARE(len={})", (Object)n6);
                } else if (n5 == 32809) {
                    VoxLinkMod.LOGGER.debug("[StunProbe] Skip MESSAGE-INTEGRITY(len={})", (Object)n6);
                } else {
                    VoxLinkMod.LOGGER.warn("[StunProbe] Unknown attribute: 0x{}, len={}", (Object)Integer.toHexString(n5), (Object)n6);
                }
                n4 += 4 + n6;
                if (n6 % 4 == 0) continue;
                n4 += 4 - n6 % 4;
            }
            return null;
        }
        return null;
    }

    private static MappedAddress parseXorMappedAddress(byte[] byArray, int n, int n2, byte[] byArray2) {
        if (n2 >= 8 && n + 8 <= byArray.length) {
            byte by = byArray[n + 5];
            int n3 = (byArray[n + 6] & 0xFF) << 8 | byArray[n + 7] & 0xFF;
            int n4 = n3 ^ 0x2112;
            if (by == 1) {
                if (n + 12 > byArray.length) {
                    return null;
                }
                int n5 = (byArray[n + 8] & 0xFF) << 24 | (byArray[n + 9] & 0xFF) << 16 | (byArray[n + 10] & 0xFF) << 8 | byArray[n + 11] & 0xFF;
                int n6 = n5 ^ 0x2112A442;
                String string = (n6 >> 24 & 0xFF) + "." + (n6 >> 16 & 0xFF) + "." + (n6 >> 8 & 0xFF) + "." + (n6 & 0xFF);
                return new MappedAddress(string, n4);
            }
            if (by != 2) {
                return null;
            }
            if (n2 >= 20 && n + 24 <= byArray.length) {
                byte[] byArray3 = new byte[16];
                System.arraycopy(byArray2, 4, byArray3, 0, 16);
                byte[] byArray4 = new byte[16];
                for (int i = 0; i < 16; ++i) {
                    byArray4[i] = (byte)(byArray[n + 8 + i] & 0xFF ^ byArray3[i] & 0xFF);
                }
                try {
                    InetAddress inetAddress = InetAddress.getByAddress(byArray4);
                    return new MappedAddress(inetAddress.getHostAddress(), n4);
                }
                catch (Exception exception) {
                    return null;
                }
            }
            return null;
        }
        return null;
    }

    private static MappedAddress parseMappedAddress(byte[] byArray, int n, int n2) {
        if (n2 >= 8 && n + 8 <= byArray.length) {
            byte by = byArray[n + 5];
            int n3 = (byArray[n + 6] & 0xFF) << 8 | byArray[n + 7] & 0xFF;
            if (by != 1) {
                return null;
            }
            if (n + 12 > byArray.length) {
                return null;
            }
            String string = (byArray[n + 8] & 0xFF) + "." + (byArray[n + 9] & 0xFF) + "." + (byArray[n + 10] & 0xFF) + "." + (byArray[n + 11] & 0xFF);
            return new MappedAddress(string, n3);
        }
        return null;
    }

    private static ParsedStunUrl parseStunUrl(String string) {
        int n;
        String string2 = string.replace("stun:", "").replace("stuns:", "");
        if (string2.startsWith("[") && (n = string2.indexOf(93)) > 0) {
            String string3 = string2.substring(1, n);
            int n2 = 0;
            if (string2.length() > n + 2 && string2.charAt(n + 1) == ':') {
                try {
                    n2 = Integer.parseInt(string2.substring(n + 2));
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            return new ParsedStunUrl(string3, n2 > 0 ? n2 : 3478);
        }
        if (string2.contains(":")) {
            int n3 = string2.lastIndexOf(":");
            if (n3 > 0 && n3 < string2.length() - 1) {
                String string4 = string2.substring(0, n3);
                String string5 = string2.substring(n3 + 1);
                if (string4.contains(":")) {
                    VoxLinkMod.LOGGER.warn("[StunProbe] IPv6 without brackets, ambiguous, ignore: {}", (Object)string);
                    return null;
                }
                try {
                    int n4 = Integer.parseInt(string5);
                    if (n4 > 0 && n4 <= 65535) {
                        return new ParsedStunUrl(string4, n4);
                    }
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            return new ParsedStunUrl(string2, 3478);
        }
        return new ParsedStunUrl(string2, 3478);
    }

    public static List<Integer> samplePortsSequential(DatagramSocket datagramSocket, List<String> list, int n, int n2) {
        if (list != null && !list.isEmpty()) {
            MappedAddress mappedAddress;
            Object object;
            byte[] byArray;
            ArrayList<Integer> arrayList = new ArrayList<Integer>();
            int n3 = -1;
            try {
                n3 = datagramSocket.getSoTimeout();
                datagramSocket.setSoTimeout(100);
            }
            catch (Exception exception) {
                // empty catch block
            }
            String string = null;
            int n4 = 3478;
            int n5 = list.size();
            ParsedStunUrl[] parsedStunUrlArray = new ParsedStunUrl[n5];
            byte[][] byArrayArray = new byte[n5][];
            try {
                InetAddress[] inetAddressArray = new InetAddress[n5];
                for (int i = 0; i < n5; ++i) {
                    parsedStunUrlArray[i] = StunProbe.parseStunUrl(list.get(i));
                    if (parsedStunUrlArray[i] == null) continue;
                    byArrayArray[i] = StunProbe.createBindingRequest();
                    inetAddressArray[i] = InetAddress.getByName(parsedStunUrlArray[i].host);
                    datagramSocket.send(new DatagramPacket(byArrayArray[i], byArrayArray[i].length, inetAddressArray[i], parsedStunUrlArray[i].port));
                }
                byArray = new byte[576];
                object = new DatagramPacket(byArray, byArray.length);
                long l = System.currentTimeMillis();
                long l2 = l + 800L;
                long l3 = l + (long)StunProbe.resendDelay(1);
                block19: while (string == null && System.currentTimeMillis() < l2) {
                    long l4 = System.currentTimeMillis();
                    if (l4 >= l3) {
                        for (int i = 0; i < n5; ++i) {
                            if (byArrayArray[i] == null) continue;
                            datagramSocket.send(new DatagramPacket(byArrayArray[i], byArrayArray[i].length, inetAddressArray[i], parsedStunUrlArray[i].port));
                        }
                        l3 = 0x3FFFFFFFFFFFFFFFL;
                    }
                    try {
                        datagramSocket.receive((DatagramPacket)object);
                    }
                    catch (SocketTimeoutException socketTimeoutException) {
                        continue;
                    }
                    byte[] byArray2 = new byte[((DatagramPacket)object).getLength()];
                    System.arraycopy(((DatagramPacket)object).getData(), 0, byArray2, 0, ((DatagramPacket)object).getLength());
                    if (byArray2.length < 20) continue;
                    for (int i = 0; i < n5; ++i) {
                        if (byArrayArray[i] == null || !StunProbe.matchTransaction(byArray2, byArrayArray[i])) continue;
                        mappedAddress = StunProbe.parseBindingResponse(byArray2, byArrayArray[i]);
                        if (mappedAddress == null) continue block19;
                        string = parsedStunUrlArray[i].host;
                        n4 = parsedStunUrlArray[i].port;
                        arrayList.add(mappedAddress.port);
                        VoxLinkMod.LOGGER.info("[StunProbe] P-PRE race selected STUN: {}:{}, first port={}", new Object[]{string, n4, mappedAddress.port});
                        continue block19;
                    }
                }
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.debug("[StunProbe] P-PRE race exception: {}", (Object)exception.getMessage());
            }
            if (string == null) {
                VoxLinkMod.LOGGER.warn("[StunProbe] P-PRE race no response, sampling failed");
                try {
                    if (n3 >= 0) {
                        datagramSocket.setSoTimeout(n3);
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
                return arrayList;
            }
            try {
                datagramSocket.setSoTimeout(100);
            }
            catch (Exception exception) {
                // empty catch block
            }
            for (int i = 1; i < n; ++i) {
                try {
                    byArray = StunProbe.createBindingRequest();
                    object = InetAddress.getByName(string);
                    DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length, (InetAddress)object, n4);
                    datagramSocket.send(datagramPacket);
                    byte[] byArray3 = new byte[576];
                    DatagramPacket datagramPacket2 = new DatagramPacket(byArray3, byArray3.length);
                    long l = System.currentTimeMillis();
                    long l5 = l + 800L;
                    int n6 = 1;
                    long l6 = l + (long)StunProbe.resendDelay(1);
                    while (System.currentTimeMillis() < l5) {
                        long l7 = System.currentTimeMillis();
                        if (l7 >= l6 && n6 <= 2) {
                            datagramSocket.send(datagramPacket);
                            l6 = ++n6 <= 2 ? l7 + (long)StunProbe.resendDelay(n6) : 0x3FFFFFFFFFFFFFFFL;
                        }
                        try {
                            datagramSocket.receive(datagramPacket2);
                        }
                        catch (SocketTimeoutException socketTimeoutException) {
                            continue;
                        }
                        byte[] byArray4 = new byte[datagramPacket2.getLength()];
                        System.arraycopy(datagramPacket2.getData(), 0, byArray4, 0, datagramPacket2.getLength());
                        if (byArray4.length < 20 || (mappedAddress = StunProbe.parseBindingResponse(byArray4, byArray)) == null) continue;
                        arrayList.add(mappedAddress.port);
                        break;
                    }
                    if (i >= n - 1) continue;
                    try {
                        Thread.sleep(n2);
                        continue;
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                catch (Exception exception) {
                    VoxLinkMod.LOGGER.debug("[StunProbe] P-PRE sample #{}/{} failed: {}", new Object[]{i + 1, n, exception.getMessage()});
                }
            }
            try {
                if (n3 >= 0) {
                    datagramSocket.setSoTimeout(n3);
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
            return arrayList;
        }
        return Collections.emptyList();
    }

    private static MappedAddress tryRecvMapped(DatagramSocket datagramSocket, DatagramPacket datagramPacket, byte[] byArray) {
        try {
            datagramSocket.setSoTimeout(800);
            datagramSocket.receive(datagramPacket);
            byte[] byArray2 = new byte[datagramPacket.getLength()];
            System.arraycopy(datagramPacket.getData(), 0, byArray2, 0, datagramPacket.getLength());
            try {
                datagramSocket.setSoTimeout(8000);
            }
            catch (Exception exception) {
                // empty catch block
            }
            return StunProbe.parseBindingResponse(byArray2, byArray);
        }
        catch (Exception exception) {
            return null;
        }
    }

    public static class StunServerResult {
        public final String url;
        public final String host;
        public final int port;
        public final boolean reachable;
        public final long latencyMs;
        public final String mappedIp;
        public final int mappedPort;

        StunServerResult(String string, String string2, int n, boolean bl, long l, String string3, int n2) {
            this.url = string;
            this.host = string2;
            this.port = n;
            this.reachable = bl;
            this.latencyMs = l;
            this.mappedIp = string3;
            this.mappedPort = n2;
        }

        StunServerResult(String string, boolean bl, long l, String string2) {
            this(string, null, 0, bl, l, string2, 0);
        }
    }

    public static enum NatType {
        UNKNOWN("unknown"),
        FULL_CONE("full_cone"),
        RESTRICTED_CONE("restricted_cone"),
        PORT_RESTRICTED_CONE("port_restricted_cone"),
        SYMMETRIC_EASY_INC("symmetric_easy_inc"),
        SYMMETRIC_EASY_DEC("symmetric_easy_dec"),
        SYMMETRIC("symmetric");

        public final String key;

        private NatType(String string2) {
            this.key = string2;
        }

        public boolean isSymmetric() {
            return this == SYMMETRIC || this == SYMMETRIC_EASY_INC || this == SYMMETRIC_EASY_DEC;
        }

        public boolean isEasySymmetric() {
            return this == SYMMETRIC_EASY_INC || this == SYMMETRIC_EASY_DEC;
        }

        public boolean isHardSymmetric() {
            return this == SYMMETRIC;
        }

        public boolean canHolePunch() {
            return this != SYMMETRIC;
        }
    }

    public static class ProbeResult {
        public final NatType natType;
        public final List<StunServerResult> serverResults;
        public final List<String> reachableStunUrls;

        ProbeResult(NatType natType, List<StunServerResult> list) {
            this.natType = natType;
            this.serverResults = Collections.unmodifiableList(new ArrayList<StunServerResult>(list));
            ArrayList<String> arrayList = new ArrayList<String>();
            for (StunServerResult stunServerResult : list) {
                if (!stunServerResult.reachable) continue;
                arrayList.add(stunServerResult.url);
            }
            this.reachableStunUrls = Collections.unmodifiableList(arrayList);
        }
    }

    private static final class CacheEntry {
        final ProbeResult result;
        final long timestamp;

        CacheEntry(ProbeResult probeResult, long l) {
            this.result = probeResult;
            this.timestamp = l;
        }
    }

    public static final class PublicMappedAddress {
        final String ip;
        final int port;

        public PublicMappedAddress(String string, int n) {
            this.ip = string;
            this.port = n;
        }

        public String ip() {
            return this.ip;
        }

        public int port() {
            return this.port;
        }

        public String toString() {
            return this.ip + ":" + this.port;
        }

        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof PublicMappedAddress)) {
                return false;
            }
            PublicMappedAddress publicMappedAddress = (PublicMappedAddress)object;
            return this.port == publicMappedAddress.port && Objects.equals(this.ip, publicMappedAddress.ip);
        }

        public int hashCode() {
            return Objects.hash(this.ip, this.port);
        }
    }

    private static class MappedAddress {
        final String ip;
        final int port;

        MappedAddress(String string, int n) {
            this.ip = string;
            this.port = n;
        }
    }

    private static final class ParsedStunUrl {
        final String host;
        final int port;

        ParsedStunUrl(String string, int n) {
            this.host = string;
            this.port = n;
        }

        String host() {
            return this.host;
        }

        int port() {
            return this.port;
        }
    }
}

