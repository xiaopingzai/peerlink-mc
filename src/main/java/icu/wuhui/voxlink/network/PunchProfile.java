/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.PunchParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PunchProfile {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-punch");
    public final String name;
    public final int punchTimeoutMs;
    public final int firewallDetectCycles;
    public final int portPredictionMaxRange;
    public final int[] progressiveRanges;
    public final int cyclesPerRange;
    public final int easySymDualSocketCount;
    public final int easySymDualPortRange;
    public final int defaultPortRange;
    public final int widePortRange;
    public final int maxPortRange;
    public final int minPortRange;
    public final int easySymPortRange;
    public final int hostMultiSocketCount;
    public final int hostMultiMinSocketCount;
    public final int hostMultiBaseSocketCount;
    public final int hardSymSocketCount;
    public final int birthdaySocketCount;
    public final int joinerSymSocketCount;
    public final int relaySocketCount;
    public final int joinerMultiPortRange;
    public final int easySymMutualSocketCount;
    public final int easySymMutualRetrySocketCount;
    public final int coneBackupPortRange;
    public final SendParams send;
    public final int socketStunCount;
    public final int socketCreateIntervalMs;
    public final int hostRoundTimeoutMs;
    public final int reverseWindowSec;
    public final int connectionTimeoutSec;
    public final int symmetricConnectionTimeoutSec;
    public final int punchMaxAttempts;
    public final int punchRetryDelayMs;
    public final int maxCycles;
    public final int maxSymCycles;
    public final int fallbackCycles;
    private static final SendParams SEND_DEFAULT = new SendParams(200, 500, 1000, 2000, 600, 200, 3, 3, 1, 10, 800);
    private static final SendParams SEND_DEFAULT_FAST = new SendParams(200, 500, 1000, 2000, 600, 200, 1, 2, 1, 5, 800);
    private static final SendParams SEND_SPRINT = new SendParams(100, 300, 600, 1200, 400, 150, 1, 1, 1, 3, 400);
    private static final SendParams SEND_WIDE = new SendParams(150, 500, 1000, 2000, 500, 200, 1, 2, 1, 5, 800);
    private static final SendParams SEND_WEAK = new SendParams(250, 800, 1500, 3000, 500, 250, 3, 3, 2, 8, 600);
    public static final PunchProfile DEFAULT = new PunchProfile("DEFAULT", 12000, 30, 100, new int[]{10, 25, 50, 75, 100}, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 8000, 40, 45, 75, 3, 800, 8, 6, 3, SEND_DEFAULT);
    public static final PunchProfile AGGRESSIVE = new PunchProfile("AGGRESSIVE", 20000, 50, 100, new int[]{10, 25, 50, 75, 100}, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 10000, 45, 50, 85, 4, 600, 10, 8, 3, SEND_DEFAULT_FAST);
    public static final PunchProfile HARDSYM = new PunchProfile("HARDSYM", 30000, 60, 500, new int[]{20, 50, 100, 200, 500}, 2, 25, 20, 30, 50, 500, 3, 20, 84, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 12000, 55, 60, 100, 5, 500, 12, 10, 3, SEND_WIDE);
    public static final PunchProfile EASY_SYM_DUAL = new PunchProfile("EASY_SYM_DUAL", 12000, 30, 50, new int[]{5, 10, 20, 30, 50}, 2, 25, 50, 20, 50, 50, 3, 20, 25, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 8000, 40, 45, 75, 3, 800, 8, 6, 3, SEND_DEFAULT);
    public static final PunchProfile V100 = new PunchProfile("V100", 8000, 38, 100, new int[]{10, 25, 50, 75, 100}, 2, 25, 20, 30, 50, 100, 3, 20, 84, 20, 3, 84, 84, 50, 5, 30, 25, 50, 10, 2, 50, 8000, 35, 40, 70, 4, 600, 10, 8, 3, SEND_DEFAULT);
    public static final PunchProfile FAST_LANE = new PunchProfile("FAST_LANE", 6000, 15, 20, new int[]{5, 10, 20}, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 6000, 30, 35, 60, 3, 800, 8, 6, 3, SEND_SPRINT);
    public static final PunchProfile RELIABLE_CONE = new PunchProfile("RELIABLE_CONE", 15000, 40, 40, new int[]{5, 10, 20, 40}, 1, 25, 20, 30, 50, 100, 3, 20, 20, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 10000, 40, 50, 80, 4, 700, 10, 8, 3, SEND_WEAK);
    public static final PunchProfile WIDE_SWEEP = new PunchProfile("WIDE_SWEEP", 35000, 70, 800, new int[]{100, 200, 400, 800}, 1, 25, 20, 30, 50, 800, 3, 20, 84, 5, 3, 84, 32, 50, 5, 30, 25, 50, 10, 2, 50, 12000, 60, 65, 110, 5, 500, 12, 10, 3, SEND_WIDE);
    private static volatile PunchProfile current = DEFAULT;
    private static volatile String switchReason = "initial";
    private static volatile PunchParams dynamicOverride;

    private PunchProfile(String string, int n, int n2, int n3, int[] nArray, int n4, int n5, int n6, int n7, int n8, int n9, int n10, int n11, int n12, int n13, int n14, int n15, int n16, int n17, int n18, int n19, int n20, int n21, int n22, int n23, int n24, int n25, int n26, int n27, int n28, int n29, int n30, int n31, int n32, int n33, SendParams sendParams) {
        this.name = string;
        this.punchTimeoutMs = n;
        this.firewallDetectCycles = n2;
        this.portPredictionMaxRange = n3;
        this.progressiveRanges = nArray;
        this.cyclesPerRange = n4;
        this.easySymDualSocketCount = n5;
        this.easySymDualPortRange = n6;
        this.defaultPortRange = n7;
        this.widePortRange = n8;
        this.maxPortRange = n9;
        this.minPortRange = n10;
        this.easySymPortRange = n11;
        this.hostMultiSocketCount = n12;
        this.hostMultiMinSocketCount = n13;
        this.hostMultiBaseSocketCount = n14;
        this.hardSymSocketCount = n15;
        this.birthdaySocketCount = n16;
        this.joinerSymSocketCount = n17;
        this.relaySocketCount = n18;
        this.joinerMultiPortRange = n19;
        this.easySymMutualSocketCount = n20;
        this.easySymMutualRetrySocketCount = n21;
        this.coneBackupPortRange = n22;
        this.socketStunCount = n23;
        this.socketCreateIntervalMs = n24;
        this.hostRoundTimeoutMs = n25;
        this.reverseWindowSec = n26;
        this.connectionTimeoutSec = n27;
        this.symmetricConnectionTimeoutSec = n28;
        this.punchMaxAttempts = n29;
        this.punchRetryDelayMs = n30;
        this.maxCycles = n31;
        this.maxSymCycles = n32;
        this.fallbackCycles = n33;
        this.send = sendParams;
    }

    public static void applyDynamicParams(PunchParams punchParams) {
        dynamicOverride = punchParams;
    }

    public static void clearDynamicParams() {
        dynamicOverride = null;
    }

    public static int effectiveTimeoutMs() {
        PunchParams punchParams = dynamicOverride;
        return punchParams != null && punchParams.timeoutMs > 0 ? punchParams.timeoutMs : PunchProfile.current.punchTimeoutMs;
    }

    public static int effectivePortRange() {
        PunchParams punchParams = dynamicOverride;
        return punchParams != null && punchParams.portRange > 0 ? punchParams.portRange : PunchProfile.current.portPredictionMaxRange;
    }

    public static int effectiveSendInterval() {
        PunchParams punchParams = dynamicOverride;
        return punchParams != null && punchParams.sendInterval > 0 ? punchParams.sendInterval : PunchProfile.current.send.intervalMs;
    }

    public static boolean effectiveSkipDirectPunch() {
        PunchParams punchParams = dynamicOverride;
        return punchParams != null && punchParams.skipDirectPunch;
    }

    public static PunchProfile current() {
        return current;
    }

    public static boolean isAggressive() {
        return current == AGGRESSIVE;
    }

    public static void switchTo(PunchProfile punchProfile, String string) {
        if (punchProfile != null && punchProfile != current) {
            PunchProfile punchProfile2 = current;
            current = punchProfile;
            switchReason = string;
            LOGGER.info("[PunchProfile] Switch: {} -> {} reason: {}", new Object[]{punchProfile2.name, punchProfile.name, string});
        }
    }

    public static void switchToAggressive(String string) {
        PunchProfile.switchTo(AGGRESSIVE, string);
    }

    public static void switchToHardSym(String string) {
        PunchProfile.switchTo(HARDSYM, string);
    }

    public static void switchToEasySymDual(String string) {
        PunchProfile.switchTo(EASY_SYM_DUAL, string);
    }

    public static void switchToDefault(String string) {
        PunchProfile.switchTo(DEFAULT, string);
    }

    public static void switchToV100(String string) {
        PunchProfile.switchTo(V100, string);
    }

    public static String describe() {
        return PunchProfile.current.name + "(timeout=" + PunchProfile.current.punchTimeoutMs + "ms, cycles=" + PunchProfile.current.firewallDetectCycles + ", range=" + PunchProfile.current.portPredictionMaxRange + ")";
    }

    public String describeInstance() {
        return this.name + "(timeout=" + this.punchTimeoutMs + "ms, cycles=" + this.firewallDetectCycles + ", range=" + this.portPredictionMaxRange + ")";
    }

    public static final class SendParams {
        public final int intervalMs;
        public final int socketTimeoutMs;
        public final int extraWaitMs;
        public final int extraWaitLongMs;
        public final int jitterBaseMs;
        public final int jitterRangeMs;
        public final int minRounds;
        public final int minPass;
        public final int sleepShortMs;
        public final int sleepLongMs;
        public final int sweepWindowSize;

        public SendParams(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9, int n10, int n11) {
            this.intervalMs = n;
            this.socketTimeoutMs = n2;
            this.extraWaitMs = n3;
            this.extraWaitLongMs = n4;
            this.jitterBaseMs = n5;
            this.jitterRangeMs = n6;
            this.minRounds = n7;
            this.minPass = n8;
            this.sleepShortMs = n9;
            this.sleepLongMs = n10;
            this.sweepWindowSize = n11;
        }
    }
}

