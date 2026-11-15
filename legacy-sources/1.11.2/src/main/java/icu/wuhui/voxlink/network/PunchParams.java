/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.PunchProfile;

public final class PunchParams {
    public int portRange;
    public int timeoutMs;
    public int sendInterval;
    public int ackRetries;
    public boolean skipDirectPunch;
    public boolean reuseSuccessfulSockets;
    public int[] successfulPortRange;
    public int sendMinRounds;
    public int sendMinPass;

    public PunchParams(int n, int n2, int n3, int n4, boolean bl, boolean bl2, int[] nArray) {
        this.portRange = n;
        this.timeoutMs = n2;
        this.sendInterval = n3;
        this.ackRetries = n4;
        this.skipDirectPunch = bl;
        this.reuseSuccessfulSockets = bl2;
        this.successfulPortRange = nArray;
        this.sendMinRounds = 0;
        this.sendMinPass = 0;
    }

    public static PunchParams fromProfile(PunchProfile punchProfile) {
        return new PunchParams(punchProfile.portPredictionMaxRange, punchProfile.punchTimeoutMs, punchProfile.send.intervalMs, 1, false, false, null);
    }
}

