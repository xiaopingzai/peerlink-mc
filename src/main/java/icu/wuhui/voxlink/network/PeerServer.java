/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraftforge.fml.common.Loader
 *  net.minecraftforge.fml.common.ModContainer
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PeerServer {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-peer");
    private static final Gson GSON = new Gson();
    private static HttpServer httpServer;
    private static int port;
    private static ExecutorService peerExecutor;
    private static volatile String cachedGameVersion;
    private static volatile int cachedProtocolVersion;
    private static volatile List<Map<String, String>> cachedMods;

    private static ExecutorService newVirtualThreadExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor", new Class[0]);
            return (ExecutorService)method.invoke(null, new Object[0]);
        }
        catch (Exception exception) {
            return Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "voxlink-peer");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    public static synchronized int start() {
        if (httpServer != null) {
            return port;
        }
        try {
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            peerExecutor = PeerServer.newVirtualThreadExecutor();
            httpServer.setExecutor(peerExecutor);
            httpServer.createContext("/info", httpExchange -> {
                String string = httpExchange.getRequestURI().getQuery();
                String string2 = null;
                if (string != null) {
                    for (String string3 : string.split("&")) {
                        String[] stringArray = string3.split("=", 2);
                        if (stringArray.length != 2 || !"token".equals(stringArray[0])) continue;
                        string2 = URLDecoder.decode(stringArray[1], "UTF-8");
                        break;
                    }
                }
                RoomInfo roomInfo = VoxLinkMod.getRoomManager() != null ? VoxLinkMod.getRoomManager().getCurrentRoom() : null;
                RoomInfo roomInfo2 = roomInfo;
                if (roomInfo == null || string2 == null || !string2.equals(roomInfo.getToken())) {
                    byte[] jsonObject = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    httpExchange.getResponseHeaders().set("Content-Type", "application/json");
                    httpExchange.sendResponseHeaders(403, jsonObject.length);
                    try (OutputStream byArray = httpExchange.getResponseBody();){
                        byArray.write(jsonObject);
                    }
                    return;
                }
                JsonObject jsonObject = PeerServer.buildInfo();
                byte[] byArray = GSON.toJson((JsonElement)jsonObject).getBytes(StandardCharsets.UTF_8);
                httpExchange.getResponseHeaders().set("Content-Type", "application/json");
                httpExchange.sendResponseHeaders(200, byArray.length);
                try (OutputStream outputStream = httpExchange.getResponseBody();){
                    outputStream.write(byArray);
                }
            });
            httpServer.start();
            port = httpServer.getAddress().getPort();
            PeerServer.refreshCache();
            LOGGER.info("Peer server started, port {}", (Object)port);
            return port;
        }
        catch (IOException iOException) {
            LOGGER.error("Peer server start failed: {}", (Object)iOException.getMessage());
            return -1;
        }
    }

    public static synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
            port = -1;
        }
        if (peerExecutor != null) {
            peerExecutor.shutdown();
            peerExecutor.shutdownNow();
            peerExecutor = null;
        }
    }

    public static int getPort() {
        return port;
    }

    public static void refreshCache() {
        Object object;
        try {
            object = Minecraft.func_71410_x();
            if (object != null) {
                if (object.func_152345_ab()) {
                    cachedGameVersion = object.func_175600_c();
                } else {
                    object.func_152344_a(() -> PeerServer.lambda$refreshCache$2((Minecraft)object));
                }
            }
        }
        catch (NoClassDefFoundError noClassDefFoundError) {
            // empty catch block
        }
        try {
            cachedProtocolVersion = 340;
        }
        catch (Exception exception) {
            LOGGER.debug("Failed to get protocol version: {}", (Object)exception.getMessage());
        }
        try {
            object = new ArrayList();
            for (ModContainer modContainer : Loader.instance().getActiveModList()) {
                LinkedHashMap<String, String> linkedHashMap = new LinkedHashMap<String, String>();
                linkedHashMap.put("id", modContainer.getModId());
                linkedHashMap.put("version", modContainer.getVersion());
                ((ArrayList)object).add(linkedHashMap);
            }
            cachedMods = Collections.unmodifiableList(object);
        }
        catch (Exception exception) {
            LOGGER.debug("Failed to get mod list: {}", (Object)exception.getMessage());
        }
    }

    private static JsonObject buildInfo() {
        JsonObject jsonObject = new JsonObject();
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (roomManager == null) {
            jsonObject.addProperty("error", "RoomManager not initialized");
            return jsonObject;
        }
        RoomInfo roomInfo = roomManager.getCurrentRoom();
        if (roomInfo != null) {
            jsonObject.addProperty("hasRoom", Boolean.valueOf(true));
            jsonObject.addProperty("code", roomInfo.getCode());
            jsonObject.addProperty("name", roomInfo.getName());
            jsonObject.addProperty("currentPlayers", (Number)roomInfo.getCurrentPlayers());
            jsonObject.addProperty("maxPlayers", (Number)roomInfo.getMaxPlayers());
            jsonObject.addProperty("hasPassword", Boolean.valueOf(roomInfo.hasPassword()));
            jsonObject.addProperty("category", roomInfo.getCategory() != null ? roomInfo.getCategory() : "other");
            jsonObject.addProperty("natType", roomInfo.getNatType());
            jsonObject.addProperty("hostPort", (Number)roomInfo.getHostPort());
            jsonObject.addProperty("bedrockPort", (Number)roomInfo.getBedrockPort());
            jsonObject.addProperty("protocolVersion", (Number)roomInfo.getServerProtocolVersion());
        } else {
            jsonObject.addProperty("hasRoom", Boolean.valueOf(false));
        }
        jsonObject.addProperty("gameVersion", cachedGameVersion);
        jsonObject.addProperty("serverProtocolVersion", (Number)cachedProtocolVersion);
        jsonObject.addProperty("geyserLoaded", Boolean.valueOf(false));
        jsonObject.addProperty("voxlinkVersion", "1.1.9");
        jsonObject.add("mods", GSON.toJsonTree(cachedMods));
        return jsonObject;
    }

    private static /* synthetic */ void lambda$refreshCache$2(Minecraft minecraft) {
        try {
            cachedGameVersion = minecraft.func_175600_c();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    static {
        port = -1;
        peerExecutor = null;
        cachedGameVersion = "";
        cachedProtocolVersion = 0;
        cachedMods = Collections.emptyList();
    }
}

