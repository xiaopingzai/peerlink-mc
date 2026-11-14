/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.entity.EntityPlayerSP
 *  net.minecraft.client.gui.GuiDisconnected
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.multiplayer.GuiConnecting
 *  net.minecraft.client.multiplayer.ServerData
 *  net.minecraft.client.network.NetHandlerPlayClient
 *  net.minecraft.entity.player.EntityPlayer
 *  net.minecraft.network.NetworkManager
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class ConnectionHelper {
    private static final int CONNECT_RESET_SEC = 30;
    private static final AtomicBoolean connecting = new AtomicBoolean(false);
    private static volatile EntityPlayer prevPlayerStrong = null;
    private static volatile long connectInitiatedAt = 0L;
    private static volatile ScheduledFuture<?> resetTask = null;
    private static final ScheduledExecutorService RESET_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VoxLink-ConnReset");
        thread.setDaemon(true);
        return thread;
    });

    private ConnectionHelper() {
    }

    public static void resetConnecting() {
        connecting.set(false);
    }

    public static boolean isConnecting() {
        return connecting.get();
    }

    public static boolean isMcTrulyConnected() {
        if (connectInitiatedAt == 0L) {
            return false;
        }
        if (System.currentTimeMillis() - connectInitiatedAt < 200L) {
            return false;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        NetHandlerPlayClient netHandlerPlayClient = minecraft.getConnection();
        if (netHandlerPlayClient == null) {
            return false;
        }
        NetworkManager networkManager = netHandlerPlayClient.getNetworkManager();
        if (networkManager != null && networkManager.isChannelOpen()) {
            EntityPlayerSP entityPlayerSP = minecraft.player;
            return entityPlayerSP == null ? false : prevPlayerStrong == null || entityPlayerSP != prevPlayerStrong;
        }
        return false;
    }

    public static boolean isConnectionRejected() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof GuiDisconnected) {
            return true;
        }
        NetHandlerPlayClient netHandlerPlayClient = minecraft.getConnection();
        return netHandlerPlayClient != null && !netHandlerPlayClient.getNetworkManager().isChannelOpen() && !(minecraft.currentScreen instanceof GuiConnecting);
    }

    public static void clearConnectInitiated() {
        connectInitiatedAt = 0L;
        prevPlayerStrong = null;
        connecting.set(false);
        ScheduledFuture<?> scheduledFuture = resetTask;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            resetTask = null;
        }
    }

    public static void connectToServer(int n, RoomInfo roomInfo) {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.addScheduledTask(() -> {
            if (n > 0) {
                if (!connecting.compareAndSet(false, true)) {
                    VoxLinkMod.LOGGER.warn("[ConnectionHelper] Already connecting, ignore duplicate call");
                    return;
                }
                try {
                    long l;
                    prevPlayerStrong = minecraft.player;
                    connectInitiatedAt = l = System.currentTimeMillis();
                    roomInfo.setLocalBridgePort(n);
                    String string = "127.0.0.1:" + n;
                    ServerData serverData = new ServerData(roomInfo.getName(), string, false);
                    minecraft.displayGuiScreen((GuiScreen)new GuiConnecting(minecraft.currentScreen, minecraft, serverData));
                    resetTask = RESET_SCHEDULER.schedule(() -> {
                        if (connecting.get() && connectInitiatedAt == l) {
                            VoxLinkMod.LOGGER.info("[ConnectionHelper] 30s timeout, auto reset connecting flag");
                            connecting.set(false);
                        }
                    }, 30L, TimeUnit.SECONDS);
                }
                catch (Exception exception) {
                    connecting.set(false);
                    ConnectionHelper.clearConnectInitiated();
                    VoxLinkMod.LOGGER.error("[ConnectionHelper] Connection failed: {}", (Object)exception.getMessage());
                    String string = roomInfo.getConnectionMode();
                    String string2 = string != null && !string.isEmpty() ? string : "voxlink.connection.cannot_establish";
                    ConnectionHelper.sendError(minecraft, "\u8fde\u63a5\u5931\u8d25\uff1a" + string2);
                    VoxLinkMod.getRoomManager().leaveRoom("\u8fde\u63a5\u5931\u8d25");
                }
            } else {
                connecting.set(false);
                ConnectionHelper.clearConnectInitiated();
                String string = roomInfo.getConnectionMode();
                String string3 = string != null && !string.isEmpty() ? string : "voxlink.connection.cannot_establish";
                ConnectionHelper.sendError(minecraft, "\u8fde\u63a5\u5931\u8d25\uff1a" + string3);
                VoxLinkMod.getRoomManager().leaveRoom("\u8fde\u63a5\u5931\u8d25");
            }
        });
    }

    private static void sendError(Minecraft minecraft, String string) {
        if (minecraft.player != null) {
            VoxLinkChat.print((ITextComponent)new TextComponentString(string));
        }
    }
}

