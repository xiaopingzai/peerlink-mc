/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.PunchResult;

public final class PunchFailureClassifier {
    private PunchFailureClassifier() {
    }

    public static FailureReason classify(PunchResult punchResult) {
        if (punchResult == null) {
            return FailureReason.NO_RESPONSE;
        }
        if (punchResult.portPredictionActive && punchResult.socketsReceivedPunch == 0 && punchResult.socketsReceivedAck == 0 && !punchResult.firewallDetected) {
            return FailureReason.PREDICTION_OFF;
        }
        if (punchResult.predictionDelta > 100) {
            return FailureReason.PREDICTION_OFF;
        }
        if (punchResult.socketsReceivedPunch == 0 && punchResult.socketsReceivedAck == 0) {
            return punchResult.firewallDetected ? FailureReason.FIREWALL_DETECTED : FailureReason.NO_RESPONSE;
        }
        if (punchResult.socketsReceivedPunch > 0 && punchResult.socketsReceivedAck == 0) {
            return FailureReason.RESPONSE_NO_ACK;
        }
        if (punchResult.socketsReceivedAck > 0 && punchResult.successSocket == null) {
            return FailureReason.ACK_TIMEOUT;
        }
        return punchResult.socketsReceivedPunch > 0 && punchResult.socketsReceivedPunch < punchResult.socketsTried ? FailureReason.PARTIAL_SUCCESS : FailureReason.NO_RESPONSE;
    }

    public static enum FailureReason {
        NO_RESPONSE,
        RESPONSE_NO_ACK,
        ACK_TIMEOUT,
        PARTIAL_SUCCESS,
        PREDICTION_OFF,
        FIREWALL_DETECTED;

    }
}

