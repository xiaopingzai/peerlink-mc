/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink;

import org.apache.logging.log4j.Logger;

public class LogHelper {
    public static void logInfo(Logger logger, String string) {
        logger.info(string);
    }

    public static void logInfo(Logger logger, String string, Object object) {
        logger.info(string + String.valueOf(object));
    }

    public static void logInfo(Logger logger, String string, Object object, Object object2) {
        logger.info(string + String.valueOf(object) + String.valueOf(object2));
    }

    public static void logInfo(Logger logger, String string, Object ... objectArray) {
        StringBuilder stringBuilder = new StringBuilder(string);
        for (Object object : objectArray) {
            stringBuilder.append(object);
        }
        logger.info(stringBuilder.toString());
    }

    public static void logWarn(Logger logger, String string) {
        logger.warn(string);
    }

    public static void logWarn(Logger logger, String string, Object object) {
        logger.warn(string + String.valueOf(object));
    }

    public static void logWarn(Logger logger, String string, Object object, Object object2) {
        logger.warn(string + String.valueOf(object) + String.valueOf(object2));
    }

    public static void logWarn(Logger logger, String string, Object ... objectArray) {
        StringBuilder stringBuilder = new StringBuilder(string);
        for (Object object : objectArray) {
            stringBuilder.append(object);
        }
        logger.warn(stringBuilder.toString());
    }

    public static void logError(Logger logger, String string) {
        logger.error(string);
    }

    public static void logError(Logger logger, String string, Object object) {
        logger.error(string + String.valueOf(object));
    }

    public static void logError(Logger logger, String string, Object object, Object object2) {
        logger.error(string + String.valueOf(object) + String.valueOf(object2));
    }

    public static void logError(Logger logger, String string, Object ... objectArray) {
        StringBuilder stringBuilder = new StringBuilder(string);
        for (Object object : objectArray) {
            stringBuilder.append(object);
        }
        logger.error(stringBuilder.toString());
    }

    public static void logDebug(Logger logger, String string) {
        logger.debug(string);
    }

    public static void logDebug(Logger logger, String string, Object object) {
        logger.debug(string + String.valueOf(object));
    }

    public static void logDebug(Logger logger, String string, Object object, Object object2) {
        logger.debug(string + String.valueOf(object) + String.valueOf(object2));
    }

    public static void logDebug(Logger logger, String string, Object ... objectArray) {
        StringBuilder stringBuilder = new StringBuilder(string);
        for (Object object : objectArray) {
            stringBuilder.append(object);
        }
        logger.debug(stringBuilder.toString());
    }
}

