/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.hook;

import java.net.InetAddress;

public class ViaForgeHook {
    private static boolean tried = false;

    public static void setupLocalTranslation() {
        if (tried) {
            return;
        }
        tried = true;
        try {
            Class<?> clazz = Class.forName("com.viaversion.viaforge.common.ViaForgeCommon");
            Object object = clazz.getMethod("getManager", new Class[0]).invoke(null, new Object[0]);
            Object object2 = clazz.getMethod("getTargetVersion", new Class[0]).invoke(object, new Object[0]);
            if (object2 == null) {
                System.out.println("[VoxLinkCV] ViaForge target version is null, skip");
                return;
            }
            Class<?> clazz2 = Class.forName("com.viaversion.viaforge.common.platform.VersionTracker");
            InetAddress inetAddress = InetAddress.getByName("127.0.0.1");
            clazz2.getMethod("storeServerProtocolVersion", InetAddress.class, Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion")).invoke(null, inetAddress, object2);
            System.out.println("[VoxLinkCV] ViaForge localhost translation set to: " + object2);
        }
        catch (Throwable throwable) {
            System.out.println("[VoxLinkCV] ViaForge hook not active: " + throwable.getMessage());
        }
    }
}

