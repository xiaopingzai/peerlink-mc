/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.PunchFailureClassifier;
import java.net.DatagramSocket;

public final class PunchResult {
    public final DatagramSocket successSocket;
    public final int socketsTried;
    public final int socketsReceivedPunch;
    public final int socketsReceivedAck;
    public final int predictionDelta;
    public final long elapsedMs;
    public final boolean firewallDetected;
    public final boolean portPredictionActive;
    public final PunchFailureClassifier.FailureReason reason;

    private PunchResult(DatagramSocket datagramSocket, int n, int n2, int n3, int n4, long l, boolean bl, boolean bl2, PunchFailureClassifier.FailureReason failureReason) {
        this.successSocket = datagramSocket;
        this.socketsTried = n;
        this.socketsReceivedPunch = n2;
        this.socketsReceivedAck = n3;
        this.predictionDelta = n4;
        this.elapsedMs = l;
        this.firewallDetected = bl;
        this.portPredictionActive = bl2;
        this.reason = failureReason;
    }

    public static PunchResult success(DatagramSocket datagramSocket, int n, int n2, int n3, int n4, long l) {
        return new PunchResult(datagramSocket, n, n2, n3, n4, l, false, false, null);
    }

    public static PunchResult failure(int n, int n2, int n3, int n4, long l, boolean bl) {
        return new PunchResult(null, n, n2, n3, n4, l, bl, false, null);
    }

    public boolean isSuccess() {
        return this.successSocket != null;
    }

    public DatagramSocket getSuccessSocket() {
        return this.successSocket;
    }

    public PunchResult withPortPrediction() {
        return new PunchResult(null, this.socketsTried, this.socketsReceivedPunch, this.socketsReceivedAck, this.predictionDelta, this.elapsedMs, this.firewallDetected, true, this.reason);
    }

    public PunchResult withReason(PunchFailureClassifier.FailureReason failureReason) {
        return new PunchResult(this.successSocket, this.socketsTried, this.socketsReceivedPunch, this.socketsReceivedAck, this.predictionDelta, this.elapsedMs, this.firewallDetected, this.portPredictionActive, failureReason);
    }
}

