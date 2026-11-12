/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  net.minecraftforge.fml.common.Loader
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VoxLinkConfig {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_SERVER_URL = "https://link.voxdev.top";
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 5;
    private static final int DEFAULT_SIGNAL_POLL_INTERVAL = 3000;
    private static final int DEFAULT_CONNECTION_TIMEOUT = 20000;
    private static final int DEFAULT_MAX_RECONNECT = 3;
    private static final int MIN_HEARTBEAT_INTERVAL = 5;
    private static final int MIN_SIGNAL_POLL_INTERVAL = 3000;
    private static final int MIN_CONNECTION_TIMEOUT = 10000;
    private static final int MIN_MAX_RECONNECT = 3;
    private static final int MAX_HEARTBEAT_INTERVAL = 60;
    private static final int MAX_SIGNAL_POLL_INTERVAL = 10000;
    private static final int MAX_CONNECTION_TIMEOUT = 60000;
    private static final int MAX_MAX_RECONNECT = 10;
    private static final int CURRENT_CONFIG_VERSION = 1;
    private volatile int configVersion = 1;
    private volatile String serverUrl = "https://link.voxdev.top";
    private volatile boolean autoUPnP = true;
    private volatile boolean offlineMode = true;
    private volatile int heartbeatInterval = 5;
    private volatile int signalPollInterval = 3000;
    private volatile int connectionTimeout = 20000;
    private volatile int maxReconnectAttempts = 3;
    private volatile boolean relayEnabled = true;
    private volatile boolean parallelP2P = true;
    private volatile boolean updateCheckEnabled = true;
    private volatile boolean useWebSocket = true;

    public static VoxLinkConfig load() {
        Path path = new File(Loader.instance().getConfigDir(), "voxlink.json").toPath();
        if (Files.exists(path, new LinkOption[0])) {
            try {
                String string = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                VoxLinkConfig voxLinkConfig = new VoxLinkConfig();
                JsonObject jsonObject = new JsonParser().parse(string).isJsonObject() ? new JsonParser().parse(string).getAsJsonObject() : new JsonObject();
                JsonObject jsonObject2 = jsonObject;
                if (jsonObject.has("configVersion")) {
                    voxLinkConfig.configVersion = jsonObject.get("configVersion").getAsInt();
                }
                voxLinkConfig.serverUrl = jsonObject.has("serverUrl") && !jsonObject.get("serverUrl").isJsonNull() ? jsonObject.get("serverUrl").getAsString() : DEFAULT_SERVER_URL;
                voxLinkConfig.autoUPnP = jsonObject.has("autoUPnP") ? jsonObject.get("autoUPnP").getAsBoolean() : true;
                voxLinkConfig.offlineMode = jsonObject.has("offlineMode") ? jsonObject.get("offlineMode").getAsBoolean() : true;
                voxLinkConfig.heartbeatInterval = jsonObject.has("heartbeatInterval") ? jsonObject.get("heartbeatInterval").getAsInt() : 5;
                voxLinkConfig.signalPollInterval = jsonObject.has("signalPollInterval") ? jsonObject.get("signalPollInterval").getAsInt() : 3000;
                voxLinkConfig.connectionTimeout = jsonObject.has("connectionTimeout") ? jsonObject.get("connectionTimeout").getAsInt() : 20000;
                voxLinkConfig.maxReconnectAttempts = jsonObject.has("maxReconnectAttempts") ? jsonObject.get("maxReconnectAttempts").getAsInt() : 3;
                voxLinkConfig.relayEnabled = jsonObject.has("relayEnabled") ? jsonObject.get("relayEnabled").getAsBoolean() : true;
                voxLinkConfig.parallelP2P = jsonObject.has("parallelP2P") ? jsonObject.get("parallelP2P").getAsBoolean() : true;
                voxLinkConfig.updateCheckEnabled = jsonObject.has("updateCheckEnabled") ? jsonObject.get("updateCheckEnabled").getAsBoolean() : true;
                voxLinkConfig.useWebSocket = jsonObject.has("useWebSocket") ? jsonObject.get("useWebSocket").getAsBoolean() : true;
                boolean bl = voxLinkConfig.useWebSocket;
                if (voxLinkConfig.serverUrl == null || voxLinkConfig.serverUrl.isEmpty()) {
                    voxLinkConfig.serverUrl = DEFAULT_SERVER_URL;
                }
                if (voxLinkConfig.serverUrl.contains("index.php")) {
                    LOGGER.warn("Detected legacy URL format: {}, reset to default", (Object)voxLinkConfig.serverUrl);
                    voxLinkConfig.serverUrl = DEFAULT_SERVER_URL;
                }
                int n = jsonObject.has("configVersion") ? jsonObject.get("configVersion").getAsInt() : 0;
                int n2 = n;
                if (n < 1) {
                    voxLinkConfig.relayEnabled = true;
                    LOGGER.info("Legacy config migration: relayEnabled forced to true");
                }
                voxLinkConfig.configVersion = 1;
                voxLinkConfig.validate();
                voxLinkConfig.save();
                LOGGER.info("Config loaded");
                return voxLinkConfig;
            }
            catch (Exception exception) {
                LOGGER.warn("Config load failed, using defaults: {}", (Object)exception.getMessage());
            }
        }
        VoxLinkConfig voxLinkConfig = new VoxLinkConfig();
        voxLinkConfig.save();
        return voxLinkConfig;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public synchronized void save() {
        Path path = new File(Loader.instance().getConfigDir(), "voxlink.json").toPath();
        Path path2 = new File(Loader.instance().getConfigDir(), "voxlink.json.tmp").toPath();
        try {
            Files.write(path2, GSON.toJson((Object)this).getBytes(StandardCharsets.UTF_8), new OpenOption[0]);
            try {
                Files.move(path2, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException atomicMoveNotSupportedException) {
                Files.move(path2, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException iOException) {
            LOGGER.error("Config save failed: {}", (Object)iOException.getMessage());
        }
        finally {
            try {
                Files.deleteIfExists(path2);
            }
            catch (Exception exception) {}
        }
    }

    private void validate() {
        if (this.serverUrl != null) {
            this.serverUrl = this.serverUrl.replaceAll("/+$", "");
        }
        // 域名迁移：旧域名落盘时改写为默认
        if (this.serverUrl != null && this.serverUrl.contains("wuhui.icu")) {
            LOGGER.warn("Deprecated serverUrl {} migrated to {}", this.serverUrl, DEFAULT_SERVER_URL);
            this.serverUrl = DEFAULT_SERVER_URL;
        }
        if (this.heartbeatInterval < 5) {
            this.heartbeatInterval = 5;
        }
        if (this.signalPollInterval < 3000) {
            this.signalPollInterval = 3000;
        }
        if (this.connectionTimeout < 10000) {
            this.connectionTimeout = 10000;
        }
        if (this.maxReconnectAttempts < 3) {
            this.maxReconnectAttempts = 3;
        }
        if (this.heartbeatInterval > 60) {
            this.heartbeatInterval = 60;
        }
        if (this.signalPollInterval > 10000) {
            this.signalPollInterval = 10000;
        }
        if (this.connectionTimeout > 60000) {
            this.connectionTimeout = 60000;
        }
        if (this.maxReconnectAttempts > 10) {
            this.maxReconnectAttempts = 10;
        }
    }

    public String getServerUrl() {
        return this.serverUrl;
    }

    public void setServerUrl(String string) {
        this.serverUrl = string;
    }

    public boolean isAutoUPnP() {
        return this.autoUPnP;
    }

    public void setAutoUPnP(boolean bl) {
        this.autoUPnP = bl;
    }

    public boolean isOfflineMode() {
        return this.offlineMode;
    }

    public void setOfflineMode(boolean bl) {
        this.offlineMode = bl;
    }

    public int getHeartbeatInterval() {
        return this.heartbeatInterval;
    }

    public int getSignalPollInterval() {
        return this.signalPollInterval;
    }

    public int getConnectionTimeout() {
        return this.connectionTimeout;
    }

    public int getMaxReconnectAttempts() {
        return this.maxReconnectAttempts;
    }

    public boolean isRelayEnabled() {
        return this.relayEnabled;
    }

    public void setRelayEnabled(boolean bl) {
        this.relayEnabled = bl;
    }

    public boolean isParallelP2P() {
        return this.parallelP2P;
    }

    public void setParallelP2P(boolean bl) {
        this.parallelP2P = bl;
    }

    public boolean isUpdateCheckEnabled() {
        return this.updateCheckEnabled;
    }

    public void setUpdateCheckEnabled(boolean bl) {
        this.updateCheckEnabled = bl;
    }

    public boolean isUseWebSocket() {
        return this.useWebSocket;
    }

    public void setUseWebSocket(boolean bl) {
        this.useWebSocket = bl;
    }

    public int getConfigVersion() {
        return this.configVersion;
    }
}

