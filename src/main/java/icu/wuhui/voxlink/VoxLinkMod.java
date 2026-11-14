/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraftforge.fml.common.Loader
 *  net.minecraftforge.fml.common.Mod
 *  net.minecraftforge.fml.common.Mod$EventHandler
 *  net.minecraftforge.fml.common.ModContainer
 *  net.minecraftforge.fml.common.event.FMLInitializationEvent
 *  net.minecraftforge.fml.common.event.FMLPreInitializationEvent
 *  net.minecraftforge.fml.common.event.FMLServerStartingEvent
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink;

import icu.wuhui.voxlink.VoxLinkClient;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.room.RoomManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid="voxlink", name="VoxLink CV", version="1.1.9", acceptedMinecraftVersions="[1.12,1.12.2]", clientSideOnly=true)
public class VoxLinkMod {
    public static final String MOD_ID = "voxlink";
    public static final String NAME = "VoxLink";
    public static final String VERSION = "1.1.9";
    public static final String MOD_VERSION = "1.1.9";
    public static final Logger LOGGER = LogManager.getLogger((String)"voxlink");
    private static volatile VoxLinkConfig config;
    private static volatile SignalingClient signalingClient;
    private static volatile RoomManager roomManager;
    private static volatile TopologyClient topologyClient;
    private static final AtomicBoolean shutdownDone;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent fMLPreInitializationEvent) {
        LOGGER.info("VoxLink 1.12.2 initializing (client)");
        config = VoxLinkConfig.load();
        try {
            signalingClient = new SignalingClient(config);
        }
        catch (Exception exception) {
            LOGGER.error("SignalingClient init failed, multiplayer unavailable: {}", (Object)exception.getMessage());
            signalingClient = null;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){

            @Override
            public void run() {
                VoxLinkMod.doShutdown();
            }
        }, "VoxLink-ShutdownHook"));
        LOGGER.info("VoxLink initialized");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent fMLServerStartingEvent) {
        try {
            fMLServerStartingEvent.getServer().func_71229_d(false);
        }
        catch (Exception exception) {
            LOGGER.debug("[VoxLink] server starting hook failed: {}", (Object)exception.getMessage());
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent fMLInitializationEvent) {
        try {
            VoxLinkClient.registerEvents();
            VoxLinkClient.initClient();
        }
        catch (Exception exception) {
            LOGGER.error("VoxLink client init failed: {}", (Object)exception.getMessage());
        }
    }

    public static void initFabric() {
        LOGGER.info("VoxLink 1.12.2 (Fabric) initializing");
        config = VoxLinkConfig.load();
        try {
            signalingClient = new SignalingClient(config);
        }
        catch (Exception exception) {
            LOGGER.error("SignalingClient init failed: {}", (Object)exception.getMessage());
            signalingClient = null;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){

            @Override
            public void run() {
                VoxLinkMod.doShutdown();
            }
        }, "VoxLink-ShutdownHook"));
        LOGGER.info("VoxLink initialized (Fabric)");
    }

    public static void doShutdown() {
        if (!shutdownDone.compareAndSet(false, true)) {
            return;
        }
        if (roomManager != null) {
            if (roomManager.isInRoom()) {
                try {
                    CompletableFuture.runAsync(new Runnable(){

                        @Override
                        public void run() {
                            roomManager.leaveRoomSync();
                        }
                    }).exceptionally(throwable -> {
                        LOGGER.warn("leaveRoomSync async failed: {}", (Object)throwable.getMessage());
                        return null;
                    });
                }
                catch (Exception exception) {
                    LOGGER.warn("leaveRoomSync submit failed: {}", (Object)exception.getMessage());
                }
            }
            try {
                roomManager.shutdown();
            }
            catch (Exception exception) {
                LOGGER.warn("roomManager.shutdown exception: {}", (Object)exception.getMessage());
            }
        }
        try {
            if (topologyClient != null) {
                topologyClient.onRoomLeft();
            }
        }
        catch (Exception exception) {
            LOGGER.warn("topologyClient.onRoomLeft exception: {}", (Object)exception.getMessage());
        }
        try {
            if (signalingClient != null) {
                signalingClient.shutdown();
            }
        }
        catch (Exception exception) {
            LOGGER.warn("signalingClient.shutdown exception: {}", (Object)exception.getMessage());
        }
        try {
            P2PBridge.disconnect();
        }
        catch (Exception exception) {
            LOGGER.warn("P2PBridge.disconnect exception: {}", (Object)exception.getMessage());
        }
        try {
            PeerServer.stop();
        }
        catch (Exception exception) {
            LOGGER.warn("PeerServer.stop exception: {}", (Object)exception.getMessage());
        }
        try {
            StunProbe.shutdown();
        }
        catch (Exception exception) {
            LOGGER.warn("StunProbe.shutdown exception: {}", (Object)exception.getMessage());
        }
        try {
            ConnectionFallback.shutdown();
        }
        catch (Exception exception) {
            LOGGER.warn("ConnectionFallback.shutdown exception: {}", (Object)exception.getMessage());
        }
        try {
            UdpHolePuncher.shutdown();
        }
        catch (Exception exception) {
            LOGGER.warn("UdpHolePuncher.shutdown exception: {}", (Object)exception.getMessage());
        }
        try {
            TopologyClient.shutdown();
        }
        catch (Exception exception) {
            LOGGER.warn("TopologyClient.shutdown exception: {}", (Object)exception.getMessage());
        }
    }

    public static VoxLinkConfig getConfig() {
        return config;
    }

    public static SignalingClient getSignalingClient() {
        return signalingClient;
    }

    public static RoomManager getRoomManager() {
        return roomManager;
    }

    public static void setRoomManager(RoomManager roomManager) {
        VoxLinkMod.roomManager = roomManager;
    }

    public static TopologyClient getTopologyClient() {
        return topologyClient;
    }

    public static void setTopologyClient(TopologyClient topologyClient) {
        VoxLinkMod.topologyClient = topologyClient;
    }

    public static String getModVersionString() {
        try {
            return ((ModContainer)Loader.instance().getIndexedModList().get(MOD_ID)).getVersion();
        }
        catch (Exception exception) {
            return "1.1.9";
        }
    }

    static {
        shutdownDone = new AtomicBoolean(false);
    }
}

