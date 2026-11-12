/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.reflect.TypeToken
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.uuid;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import icu.wuhui.voxlink.network.Http8;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UUIDPolicyManager {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-uuid");
    private static final Gson GSON = new Gson();
    private static final int UUID_CACHE_MAX_SIZE = 1000;
    private static final int HEX_UUID_LENGTH = 32;
    private static final Map<String, UUID> uuidCache = Collections.synchronizedMap(new LinkedHashMap<String, UUID>(128, 0.75f, true){

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, UUID> entry) {
            return this.size() > 1000;
        }
    });
    private static final Map<String, String> policyMap = new ConcurrentHashMap<String, String>();
    private static final Map<String, Long> lastFetchTime = new ConcurrentHashMap<String, Long>();
    private static final long FETCH_COOLDOWN_MS = 60000L;

    public static UUID hookEntry(String string) {
        String string2 = policyMap.getOrDefault(string, policyMap.getOrDefault("*", null));
        if (string2 == null) {
            return null;
        }
        if ("offline".equals(string2)) {
            return null;
        }
        if (string2.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            try {
                return UUID.fromString(string2);
            }
            catch (IllegalArgumentException illegalArgumentException) {
                return null;
            }
        }
        if ("online".equals(string2)) {
            UUID uUID = uuidCache.get(string);
            return uUID != null ? uUID : UUIDPolicyManager.fetchOfficialUUIDSync(string);
        }
        return null;
    }

    private static UUID fetchOfficialUUIDSync(String string) {
        block6: {
            long l = System.currentTimeMillis();
            Long l2 = lastFetchTime.get(string);
            if (l2 != null && l - l2 < 60000L) {
                return uuidCache.get(string);
            }
            lastFetchTime.put(string, l);
            try {
                Http8.Response response = Http8.getAsync("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(string, "UTF-8"), "VoxLink/1.12.2", 3000L).get(3500L, TimeUnit.MILLISECONDS);
                if (response.statusCode != 200) break block6;
                try {
                    Type type = new TypeToken<Map<String, String>>(){}.getType();
                    Map map = (Map)GSON.fromJson(response.body, type);
                    String string2 = (String)map.get("id");
                    if (string2 != null && string2.length() == 32) {
                        String string3 = string2.substring(0, 8) + "-" + string2.substring(8, 12) + "-" + string2.substring(12, 16) + "-" + string2.substring(16, 20) + "-" + string2.substring(20);
                        UUID uUID = UUID.fromString(string3);
                        uuidCache.put(string, uUID);
                        LOGGER.debug("Cached official UUID for {}", (Object)string);
                        return uUID;
                    }
                }
                catch (Exception exception) {
                    LOGGER.debug("Mojang response parse failed ({}): {}", (Object)string, (Object)exception.getMessage());
                }
            }
            catch (Exception exception) {
                LOGGER.debug("Failed to get official UUID for {}: {}", (Object)string, (Object)exception.getMessage());
            }
        }
        return null;
    }

    public static void setPolicy(String string, String string2) {
        policyMap.put(string, string2);
    }

    public static void setDefaultPolicy(String string) {
        policyMap.put("*", string);
    }

    public static void removePolicy(String string) {
        policyMap.remove(string);
    }
}

