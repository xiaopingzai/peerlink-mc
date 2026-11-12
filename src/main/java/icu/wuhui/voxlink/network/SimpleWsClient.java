/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public final class SimpleWsClient {
    private final URI uri;
    private final Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean open = false;
    private volatile boolean closed = false;
    private final Object writeLock = new Object();

    private SimpleWsClient(URI uRI, Listener listener) {
        this.uri = uRI;
        this.listener = listener;
    }

    public static CompletableFuture<SimpleWsClient> connectAsync(final URI uRI, final Listener listener, final long l) {
        final CompletableFuture<SimpleWsClient> completableFuture = new CompletableFuture<SimpleWsClient>();
        Thread thread = new Thread(new Runnable(){

            @Override
            public void run() {
                try {
                    SimpleWsClient simpleWsClient = new SimpleWsClient(uRI, listener);
                    simpleWsClient.doConnect(l);
                    completableFuture.complete(simpleWsClient);
                }
                catch (Throwable throwable) {
                    completableFuture.completeExceptionally(throwable);
                }
            }
        }, "voxlink-ws-connect");
        thread.setDaemon(true);
        thread.start();
        return completableFuture;
    }

    private void doConnect(long l) throws Exception {
        String string;
        int n = this.uri.getPort() > 0 ? this.uri.getPort() : (this.uri.getScheme().equalsIgnoreCase("wss") ? 443 : 80);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(this.uri.getHost(), n), (int)l);
        socket.setSoTimeout((int)l);
        this.socket = socket;
        this.out = socket.getOutputStream();
        String string2 = this.uri.getRawPath();
        if (string2 == null || string2.isEmpty()) {
            string2 = "/";
        }
        if (this.uri.getRawQuery() != null) {
            string2 = string2 + "?" + this.uri.getRawQuery();
        }
        byte[] byArray = new byte[16];
        new SecureRandom().nextBytes(byArray);
        String string3 = Base64.getEncoder().encodeToString(byArray);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("GET ").append(string2).append(" HTTP/1.1\r\n");
        stringBuilder.append("Host: ").append(this.uri.getHost());
        if (n != 80 && n != 443) {
            stringBuilder.append(':').append(n);
        }
        stringBuilder.append("\r\n");
        stringBuilder.append("Upgrade: websocket\r\n");
        stringBuilder.append("Connection: Upgrade\r\n");
        stringBuilder.append("Sec-WebSocket-Key: ").append(string3).append("\r\n");
        stringBuilder.append("Sec-WebSocket-Version: 13\r\n");
        stringBuilder.append("User-Agent: VoxLink/1.12.2\r\n");
        stringBuilder.append("\r\n");
        this.out.write(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
        this.out.flush();
        InputStream inputStream = socket.getInputStream();
        String string4 = this.readLine(inputStream);
        if (string4 == null || !string4.startsWith("HTTP/1.1 101")) {
            throw new IOException("WebSocket handshake failed: " + string4);
        }
        boolean bl = false;
        while ((string = this.readLine(inputStream)) != null && !string.isEmpty()) {
            if (!string.toLowerCase().startsWith("sec-websocket-accept:")) continue;
            bl = true;
        }
        if (!bl) {
            throw new IOException("WebSocket handshake missing Sec-WebSocket-Accept");
        }
        this.open = true;
        this.closed = false;
        Thread thread = new Thread(new Runnable(){

            @Override
            public void run() {
                SimpleWsClient.this.readLoop();
            }
        }, "voxlink-ws-reader");
        thread.setDaemon(true);
        thread.start();
        this.listener.onOpen();
    }

    private String readLine(InputStream inputStream) throws IOException {
        int n;
        StringBuilder stringBuilder = new StringBuilder();
        while ((n = inputStream.read()) != -1 && n != 10) {
            if (n != 13) {
                stringBuilder.append((char)n);
            }
            if (stringBuilder.length() <= 8192) continue;
        }
        return stringBuilder.length() == 0 && n == -1 ? null : stringBuilder.toString();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void readLoop() {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            InputStream inputStream = this.socket.getInputStream();
            while (!this.closed) {
                int n;
                int n2 = inputStream.read();
                if (n2 < 0) {
                    throw new EOFException("stream closed");
                }
                int n3 = inputStream.read();
                if (n3 < 0) {
                    throw new EOFException("stream closed");
                }
                boolean bl = (n2 & 0x80) != 0;
                int n4 = n2 & 0xF;
                boolean bl2 = (n3 & 0x80) != 0;
                long l = n3 & 0x7F;
                if (l == 126L) {
                    l = (long)inputStream.read() << 8 | (long)inputStream.read();
                } else if (l == 127L) {
                    l = 0L;
                    for (int i = 0; i < 8; ++i) {
                        l = l << 8 | (long)inputStream.read();
                    }
                }
                byte[] byArray = null;
                if (bl2) {
                    byArray = new byte[4];
                    this.readFully(inputStream, byArray);
                }
                byte[] byArray2 = new byte[(int)l];
                this.readFully(inputStream, byArray2);
                if (bl2) {
                    for (n = 0; n < byArray2.length; ++n) {
                        int n5 = n;
                        byArray2[n5] = (byte)(byArray2[n5] ^ byArray[n & 3]);
                    }
                }
                switch (n4) {
                    case 1: {
                        stringBuilder.append(new String(byArray2, StandardCharsets.UTF_8));
                        if (!bl) break;
                        this.listener.onText(stringBuilder.toString(), true);
                        stringBuilder.setLength(0);
                        break;
                    }
                    case 2: {
                        break;
                    }
                    case 8: {
                        n = 1005;
                        String string = "";
                        if (byArray2.length >= 2) {
                            n = (byArray2[0] & 0xFF) << 8 | byArray2[1] & 0xFF;
                            if (byArray2.length > 2) {
                                string = new String(byArray2, 2, byArray2.length - 2, StandardCharsets.UTF_8);
                            }
                        }
                        try {
                            this.sendCloseFrame(1000, "bye");
                        }
                        catch (Exception exception) {
                            // empty catch block
                        }
                        this.closed = true;
                        this.open = false;
                        this.listener.onClose(n, string);
                        return;
                    }
                    case 9: {
                        this.sendFrame(10, byArray2);
                        break;
                    }
                    case 10: {
                        break;
                    }
                }
            }
        }
        catch (Throwable throwable) {
            if (!this.closed) {
                this.closed = true;
                this.open = false;
                this.listener.onError(throwable);
                this.listener.onClose(1006, "abnormal");
            }
        }
        finally {
            this.closeSocket();
        }
    }

    private void readFully(InputStream inputStream, byte[] byArray) throws IOException {
        int n;
        for (int i = 0; i < byArray.length; i += n) {
            n = inputStream.read(byArray, i, byArray.length - i);
            if (n >= 0) continue;
            throw new EOFException();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void sendFrame(int n, byte[] byArray) throws IOException {
        Object object = this.writeLock;
        synchronized (object) {
            if (this.out == null) {
                throw new IOException("closed");
            }
            this.out.write(0x80 | n);
            int n2 = byArray.length;
            byte[] byArray2 = new byte[4];
            new SecureRandom().nextBytes(byArray2);
            if (n2 < 126) {
                this.out.write(0x80 | n2);
            } else if (n2 < 65536) {
                this.out.write(254);
                this.out.write(n2 >> 8 & 0xFF);
                this.out.write(n2 & 0xFF);
            } else {
                this.out.write(255);
                for (int i = 7; i >= 0; --i) {
                    this.out.write(n2 >> 8 * i & 0xFF);
                }
            }
            this.out.write(byArray2);
            byte[] byArray3 = new byte[n2];
            for (int i = 0; i < n2; ++i) {
                byArray3[i] = (byte)(byArray[i] ^ byArray2[i & 3]);
            }
            this.out.write(byArray3);
            this.out.flush();
        }
    }

    private void sendCloseFrame(int n, String string) throws IOException {
        byte[] byArray = string.getBytes(StandardCharsets.UTF_8);
        byte[] byArray2 = new byte[2 + byArray.length];
        byArray2[0] = (byte)(n >> 8 & 0xFF);
        byArray2[1] = (byte)(n & 0xFF);
        System.arraycopy(byArray, 0, byArray2, 2, byArray.length);
        this.sendFrame(8, byArray2);
    }

    public boolean isOpen() {
        return this.open && !this.closed;
    }

    public void sendText(String string) throws IOException {
        this.sendFrame(1, string.getBytes(StandardCharsets.UTF_8));
    }

    public void close(int n, String string) {
        this.closed = true;
        this.open = false;
        try {
            this.sendCloseFrame(n, string);
        }
        catch (Exception exception) {
            // empty catch block
        }
        this.closeSocket();
    }

    private void closeSocket() {
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    public static void requireTimeout(long l) {
    }

    public static interface Listener {
        public void onOpen();

        public void onText(CharSequence var1, boolean var2);

        public void onError(Throwable var1);

        public void onClose(int var1, String var2);
    }
}

