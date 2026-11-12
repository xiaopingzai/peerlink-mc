/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.ReliableUdpTransport;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UdpForwardBridge
implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-udp-fwd");
    private static final int MAX_VOICE_PAYLOAD = 1400;
    private static final int PORT_HEADER_LEN = 2;
    private final ReliableUdpTransport transport;
    private final boolean hostSide;
    private final List<PortBridge> bridges = new CopyOnWriteArrayList<PortBridge>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public UdpForwardBridge(ReliableUdpTransport reliableUdpTransport, boolean bl, int ... nArray) {
        this.transport = reliableUdpTransport;
        this.hostSide = bl;
        for (int n : nArray) {
            try {
                DatagramSocket datagramSocket = bl ? new DatagramSocket() : new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), n));
                PortBridge portBridge = new PortBridge(datagramSocket, n);
                this.bridges.add(portBridge);
                portBridge.start();
                LOGGER.info("[UdpForward] voice relay {} on loopback:{}", (Object)(bl ? "host-relay" : "bound"), (Object)n);
            }
            catch (IOException iOException) {
                LOGGER.warn("[UdpForward] skip port {} ({}): {}", new Object[]{n, bl ? "relay" : "bind", iOException.getMessage()});
            }
        }
        if (!this.bridges.isEmpty()) {
            this.transport.setOnVoiceData(byArray -> {
                if (byArray != null && ((byte[])byArray).length >= 2 && !this.closed.get()) {
                    int n = this.readUint16((byte[])byArray, 0);
                    for (PortBridge portBridge : this.bridges) {
                        if (portBridge.port != n) continue;
                        portBridge.deliver((byte[])byArray);
                        break;
                    }
                }
            });
        }
    }

    public boolean isActive() {
        return !this.bridges.isEmpty();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.transport.setOnVoiceData(null);
            for (PortBridge portBridge : this.bridges) {
                portBridge.close();
            }
            LOGGER.info("[UdpForward] voice relay closed");
        }
    }

    private int readUint16(byte[] byArray, int n) {
        return (byArray[n] & 0xFF) << 8 | byArray[n + 1] & 0xFF;
    }

    private static void writeUint16(byte[] byArray, int n, int n2) {
        byArray[n] = (byte)(n2 >> 8);
        byArray[n + 1] = (byte)n2;
    }

    private class PortBridge
    implements AutoCloseable {
        final DatagramSocket socket;
        final int port;
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile InetSocketAddress localService;

        PortBridge(DatagramSocket datagramSocket, int n) {
            this.socket = datagramSocket;
            this.port = n;
        }

        void start() {
            Thread thread = new Thread(this::receiveLoop, "VoxLink-UdpForward-" + this.port);
            thread.setDaemon(true);
            thread.start();
        }

        private void receiveLoop() {
            byte[] byArray = new byte[1400];
            DatagramPacket datagramPacket = new DatagramPacket(byArray, byArray.length);
            while (this.running.get() && !this.socket.isClosed()) {
                try {
                    this.socket.receive(datagramPacket);
                    if (datagramPacket.getLength() >= 1398) continue;
                    this.localService = new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort());
                    byte[] byArray2 = new byte[2 + datagramPacket.getLength()];
                    UdpForwardBridge.writeUint16(byArray2, 0, this.port);
                    System.arraycopy(datagramPacket.getData(), datagramPacket.getOffset(), byArray2, 2, datagramPacket.getLength());
                    UdpForwardBridge.this.transport.sendVoice(byArray2);
                }
                catch (IOException iOException) {
                    if (!this.running.get() || this.socket.isClosed()) continue;
                    LOGGER.debug("[UdpForward] recv loop error on port {}: {}", (Object)this.port, (Object)iOException.getMessage());
                }
            }
        }

        void deliver(byte[] byArray) {
            int n = byArray.length - 2;
            byte[] byArray2 = new byte[n];
            System.arraycopy(byArray, 2, byArray2, 0, n);
            InetSocketAddress inetSocketAddress = UdpForwardBridge.this.hostSide ? this.localService : this.localService;
            InetSocketAddress inetSocketAddress2 = inetSocketAddress;
            if (inetSocketAddress == null) {
                inetSocketAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), this.port);
            }
            try {
                this.socket.send(new DatagramPacket(byArray2, byArray2.length, inetSocketAddress));
            }
            catch (IOException iOException) {
                LOGGER.debug("[UdpForward] deliver failed on port {}: {}", (Object)this.port, (Object)iOException.getMessage());
            }
        }

        @Override
        public void close() {
            this.running.set(false);
            try {
                this.socket.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }
}

