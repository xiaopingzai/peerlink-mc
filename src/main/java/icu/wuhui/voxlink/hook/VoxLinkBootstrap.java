/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class VoxLinkBootstrap {
    private static boolean initialized = false;

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> clazz = Class.forName("icu.wuhui.voxlink.VoxLinkMod");
            Object obj = clazz.newInstance();
            Class<?> clazz2 = Class.forName("icu.wuhui.voxlink.config.VoxLinkConfig");
            Method method = clazz2.getMethod("load", new Class[0]);
            Object object = method.invoke(null, new Object[0]);
            Field field = clazz.getDeclaredField("config");
            field.setAccessible(true);
            field.set(null, object);
            Class<?> clazz3 = Class.forName("icu.wuhui.voxlink.network.SignalingClient");
            Constructor<?> constructor = clazz3.getConstructor(clazz2);
            Object obj2 = constructor.newInstance(object);
            Field field2 = clazz.getDeclaredField("signalingClient");
            field2.setAccessible(true);
            field2.set(null, obj2);
            Class<?> clazz4 = Class.forName("icu.wuhui.voxlink.VoxLinkClient");
            Method method2 = clazz4.getMethod("registerEvents", new Class[0]);
            method2.invoke(null, new Object[0]);
            Method method3 = clazz4.getMethod("initClient", new Class[0]);
            method3.invoke(null, new Object[0]);
            System.out.println("[VoxLinkCV] Bootstrap initialized successfully");
        }
        catch (Throwable throwable) {
            System.out.println("[VoxLinkCV] Bootstrap failed: " + throwable);
            throwable.printStackTrace();
        }
    }
}

