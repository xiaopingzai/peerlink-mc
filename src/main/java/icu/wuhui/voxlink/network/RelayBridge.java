/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.room.ConnectionManager;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RelayBridge {
    private static final Logger LOGGER = LogManager.getLogger((String)"VoxLink-Relay");
    private static final int MONITOR_INITIAL_DELAY_SEC = 5;
    private static final int MONITOR_INTERVAL_SEC = 5;
    private static final int RELAY_BUFFER_SIZE = 65536;
    private final ScheduledExecutorService scheduler;
    private final Map<String, RelaySession> activeRelays = new ConcurrentHashMap<String, RelaySession>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> monitorTask;
    private static volatile RelayBridge instance;

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static RelayBridge getInstance(ScheduledExecutorService scheduledExecutorService) {
        if (instance != null) {
            return instance;
        }
        Class<RelayBridge> clazz = RelayBridge.class;
        Class<RelayBridge> clazz2 = RelayBridge.class;
        synchronized (RelayBridge.class) {
            if (instance != null) {
                // ** MonitorExit[var2_2] (shouldn't be in output)
                return instance;
            }
            instance = new RelayBridge(scheduledExecutorService);
            // ** MonitorExit[var2_2] (shouldn't be in output)
            return instance;
        }
    }

    private RelayBridge(ScheduledExecutorService scheduledExecutorService) {
        this.scheduler = scheduledExecutorService;
    }

    public void startRelay(String string, String string2, ReliableUdpTransport reliableUdpTransport, ReliableUdpTransport reliableUdpTransport2) {
        if (string != null && string2 != null && !string.equals(string2) && reliableUdpTransport != null && reliableUdpTransport2 != null && reliableUdpTransport != reliableUdpTransport2) {
            RelaySession relaySession;
            String string3 = string.compareTo(string2) < 0 ? string + "<->" + string2 : string2 + "<->" + string;
            if (this.activeRelays.putIfAbsent(string3, relaySession = new RelaySession(string, string2, reliableUdpTransport, reliableUdpTransport2)) != null) {
                LOGGER.info("[Relay] Relay session already exists: {}", (Object)string3);
            } else {
                relaySession.startForwarding();
                LOGGER.info("[Relay] Relay started: {} (A={}, B={})", new Object[]{string3, string, string2});
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft.player != null) {
                    VoxLinkChat.print((ITextComponent)new TextComponentString("voxlink.relay.started"));
                }
                if (this.running.compareAndSet(false, true)) {
                    this.monitorTask = this.scheduler.scheduleAtFixedRate(this::monitorRelays, 5L, 5L, TimeUnit.SECONDS);
                }
            }
        } else {
            LOGGER.warn("[Relay] Reject invalid relay session: {} <-> {}", (Object)string, (Object)string2);
        }
    }

    public int getRelayCountForPeer(String string) {
        int n = 0;
        for (RelaySession relaySession : this.activeRelays.values()) {
            if (!relaySession.peerAId.equals(string) && !relaySession.peerBId.equals(string)) continue;
            ++n;
        }
        return n;
    }

    public void stopAllRelays() {
        if (!this.activeRelays.isEmpty()) {
            LOGGER.info("[Relay] Stopping {} relay sessions", (Object)this.activeRelays.size());
            for (RelaySession relaySession : this.activeRelays.values()) {
                try {
                    relaySession.stop();
                }
                catch (Exception exception) {}
            }
            this.activeRelays.clear();
            if (this.running.compareAndSet(true, false) && this.monitorTask != null) {
                this.monitorTask.cancel(false);
                this.monitorTask = null;
            }
        }
    }

    private void monitorRelays() {
        ArrayList arrayList = new ArrayList();
        this.activeRelays.entrySet().removeIf(entry -> {
            RelaySession relaySession = (RelaySession)entry.getValue();
            if (relaySession.transportA.isConnected() && relaySession.transportB.isConnected()) {
                return false;
            }
            LOGGER.warn("[Relay] Relay session disconnected: {}", entry.getKey());
            arrayList.add((String)entry.getKey());
            relaySession.stop();
            return true;
        });
        if (this.activeRelays.isEmpty() && this.running.compareAndSet(true, false)) {
            if (this.monitorTask != null) {
                this.monitorTask.cancel(false);
                this.monitorTask = null;
            }
            LOGGER.info("[Relay] No relay sessions, monitor stopped");
        }
        if (!arrayList.isEmpty()) {
            for (String string : arrayList) {
                String[] stringArray = string.split("<->");
                if (stringArray.length != 2) continue;
                try {
                    ConnectionManager connectionManager = ConnectionManager.getInstance();
                    if (connectionManager == null) continue;
                    connectionManager.onRelayDisconnected(stringArray[0], stringArray[1]);
                }
                catch (Exception exception) {}
            }
        }
    }

    private static class RelaySession {
        final String peerAId;
        final String peerBId;
        final ReliableUdpTransport transportA;
        final ReliableUdpTransport transportB;
        volatile boolean forwarding = true;
        Thread threadAB;
        Thread threadBA;

        RelaySession(String string, String string2, ReliableUdpTransport reliableUdpTransport, ReliableUdpTransport reliableUdpTransport2) {
            this.peerAId = string;
            this.peerBId = string2;
            this.transportA = reliableUdpTransport;
            this.transportB = reliableUdpTransport2;
        }

        void startForwarding() {
            InputStream inputStream = this.transportA.getInputStream();
            OutputStream outputStream = this.transportA.getOutputStream();
            InputStream inputStream2 = this.transportB.getInputStream();
            OutputStream outputStream2 = this.transportB.getOutputStream();
            this.threadAB = new Thread(() -> {
                byte[] byArray = new byte[65536];
                while (this.forwarding) {
                    try {
                        int n = inputStream.read(byArray);
                        if (n > 0) {
                            outputStream2.write(byArray, 0, n);
                            continue;
                        }
                        if (n < 0) break;
                    }
                    catch (Exception exception) {
                        if (!this.forwarding) break;
                        LOGGER.debug("[Relay] A->B exception: {}", (Object)exception.getMessage());
                        break;
                    }
                }
                this.forwarding = false;
            }, "VoxLink-Relay-A2B");
            this.threadAB.setDaemon(true);
            this.threadBA = new Thread(() -> {
                byte[] byArray = new byte[65536];
                while (this.forwarding) {
                    try {
                        int n = inputStream2.read(byArray);
                        if (n > 0) {
                            outputStream.write(byArray, 0, n);
                            continue;
                        }
                        if (n < 0) break;
                    }
                    catch (Exception exception) {
                        if (!this.forwarding) break;
                        LOGGER.debug("[Relay] B->A exception: {}", (Object)exception.getMessage());
                        break;
                    }
                }
                this.forwarding = false;
            }, "VoxLink-Relay-B2A");
            this.threadBA.setDaemon(true);
            this.threadAB.start();
            this.threadBA.start();
        }

        void stop() {
            this.forwarding = false;
            if (this.threadAB != null) {
                this.threadAB.interrupt();
            }
            if (this.threadBA != null) {
                this.threadBA.interrupt();
            }
            try {
                this.transportA.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                this.transportB.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }
}

