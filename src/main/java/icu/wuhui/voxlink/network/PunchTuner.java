/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.logging.log4j.LogManager
 *  org.apache.logging.log4j.Logger
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.network.PunchParams;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.PunchResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PunchTuner {
    private static final Logger LOGGER = LogManager.getLogger((String)"voxlink-punch");
    private static final int MAX_PORT_RANGE = 500;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final int MIN_SEND_INTERVAL_MS = 50;
    private static final int LATE_CYCLE_TIMEOUT_MS = 5000;
    private static final int ACK_RETRIES_ON_TIMEOUT = 3;
    private static final int PREDICTION_DELTA_THRESHOLD = 100;
    private static final int PORT_RANGE_MULTIPLIER = 2;
    private static final int TIMEOUT_INCREMENT_MS = 4000;
    private static final int SEND_INTERVAL_DIVISOR = 2;

    private PunchTuner() {
    }

    public static PunchParams nextParams(PunchProfile punchProfile, NatClass natClass, NatClass natClass2, int n, int n2, PunchFailureClassifier.FailureReason failureReason, PunchResult punchResult) {
        PunchParams punchParams = PunchParams.fromProfile(punchProfile);
        if (failureReason == null) {
            return PunchTuner.applyLateCycleLimit(punchParams, n, n2);
        }
        switch (failureReason) {
            case NO_RESPONSE: {
                punchParams.portRange = Math.min(punchParams.portRange * 2, 500);
                punchParams.timeoutMs = Math.min(punchParams.timeoutMs + 4000, 30000);
                punchParams.sendMinRounds = 3;
                punchParams.sendMinPass = 3;
                break;
            }
            case PREDICTION_OFF: {
                punchParams = PunchParams.fromProfile(PunchProfile.HARDSYM);
                punchParams.portRange = Math.min(punchParams.portRange * 2, 500);
                punchParams.sendMinRounds = 3;
                punchParams.sendMinPass = 3;
                break;
            }
            case RESPONSE_NO_ACK: {
                punchParams.sendInterval = Math.max(punchParams.sendInterval / 2, 50);
                punchParams.sendMinPass = 3;
                break;
            }
            case FIREWALL_DETECTED: {
                punchParams.skipDirectPunch = true;
                break;
            }
            case PARTIAL_SUCCESS: {
                if (punchResult == null || punchResult.predictionDelta <= 0) break;
                punchParams.reuseSuccessfulSockets = true;
                punchParams.successfulPortRange = new int[]{punchResult.predictionDelta - 5, punchResult.predictionDelta + 5};
                break;
            }
            case ACK_TIMEOUT: {
                punchParams.ackRetries = 3;
                punchParams.sendMinPass = 3;
            }
        }
        if (punchResult != null && punchResult.elapsedMs > 0L) {
            if (punchResult.elapsedMs > 10000L) {
                punchParams.timeoutMs = Math.min(punchParams.timeoutMs * 3 / 2, 30000);
            } else if (punchResult.elapsedMs < 3000L && failureReason == PunchFailureClassifier.FailureReason.NO_RESPONSE) {
                punchParams.portRange = Math.min(punchParams.portRange * 2, 500);
            }
        }
        punchParams = PunchTuner.applyLateCycleLimit(punchParams, n, n2);
        LOGGER.info("[PunchTuner] cycle={} failure={} -> portRange={} timeout={} sendInterval={} ackRetries={} skipDirect={} reuse={}", new Object[]{n, failureReason, punchParams.portRange, punchParams.timeoutMs, punchParams.sendInterval, punchParams.ackRetries, punchParams.skipDirectPunch, punchParams.reuseSuccessfulSockets});
        return punchParams;
    }

    private static PunchParams applyLateCycleLimit(PunchParams punchParams, int n, int n2) {
        if (n >= n2 - 2 && punchParams.timeoutMs > 5000) {
            punchParams.timeoutMs = Math.max(5000, punchParams.timeoutMs * 3 / 4);
        }
        return punchParams;
    }
}

