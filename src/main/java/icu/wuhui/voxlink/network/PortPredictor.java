/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PortPredictor {
    private static final int SAMPLES_HIGH = 10;
    private static final int RANGE_HIGH = 32;
    private static final int SAMPLES_MID = 5;
    private static final int RANGE_MID = 64;
    private static final int SAMPLES_LOW = 3;
    private static final int RANGE_LOW = 100;
    private static final int RANGE_DEFAULT = 200;
    private static final double EPSILON = 1.0E-9;
    private static final double ALPHA = 0.4;
    private static final double LR_WEIGHT = 0.6;
    private static final double DELTA_WEIGHT = 0.4;
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;

    private PortPredictor() {
    }

    private static int confidenceRange(int n) {
        if (n >= 10) {
            return 32;
        }
        if (n >= 5) {
            return 64;
        }
        return n >= 3 ? 100 : 200;
    }

    public static int linearRegressionPredict(List<Integer> list) {
        if (list != null && !list.isEmpty()) {
            int n = list.size();
            if (n == 1) {
                return list.get(0);
            }
            double d = 0.0;
            double d2 = 0.0;
            double d3 = 0.0;
            double d4 = 0.0;
            for (int i = 0; i < n; ++i) {
                d += (double)i;
                d2 += (double)list.get(i).intValue();
                d3 += (double)i * (double)list.get(i).intValue();
                d4 += (double)i * (double)i;
            }
            double d5 = (double)n * d4 - d * d;
            if (Math.abs(d5) < 1.0E-9) {
                return (int)Math.round(d2 / (double)n);
            }
            double d6 = ((double)n * d3 - d * d2) / d5;
            double d7 = (d2 - d6 * d) / (double)n;
            int n2 = (int)Math.round(d6 * (double)n + d7);
            VoxLinkMod.LOGGER.info("[PortPredictor] Linear regression: slope={}, intercept={}, predicted={} (n={})", new Object[]{String.format("%.2f", d6), String.format("%.2f", d7), n2, n});
            return n2;
        }
        return -1;
    }

    public static int deltaPredict(List<Integer> list) {
        if (list != null && list.size() >= 2) {
            int n;
            ArrayList<Integer> arrayList = new ArrayList<Integer>();
            for (int i = 1; i < list.size(); ++i) {
                arrayList.add(list.get(i) - list.get(i - 1));
            }
            ArrayList arrayList2 = new ArrayList(arrayList);
            Collections.sort(arrayList2);
            int n2 = arrayList2.size() / 4;
            List list2 = arrayList2.subList(n2, arrayList2.size() - n2);
            if (list2.isEmpty()) {
                list2 = arrayList2;
            }
            double d = ((Integer)list2.get(0)).intValue();
            double d2 = 0.4;
            for (n = 1; n < list2.size(); ++n) {
                d += d2 * ((double)((Integer)list2.get(n)).intValue() - d);
            }
            n = (int)Math.round(d);
            int n3 = list.get(list.size() - 1);
            int n4 = n3 + n;
            VoxLinkMod.LOGGER.info("[PortPredictor] Delta prediction: delta={}, lastPort={}, predicted={} (n={})", new Object[]{n, n3, n4, list.size()});
            return n4;
        }
        return -1;
    }

    public static PredictResult predict(List<Integer> list) {
        if (list != null && !list.isEmpty()) {
            int n;
            if (list.size() == 1) {
                return new PredictResult(list.get(0), 200, "single_sample");
            }
            int n2 = PortPredictor.linearRegressionPredict(list);
            int n3 = PortPredictor.deltaPredict(list);
            int n4 = n2 > 0 && n3 > 0 ? (int)Math.round((double)n2 * 0.6 + (double)n3 * 0.4) : (n = n2 > 0 ? n2 : n3);
            if (n < 1024) {
                n = 1024;
            }
            if (n > 65535) {
                n = 65535;
            }
            int n5 = PortPredictor.confidenceRange(list.size());
            VoxLinkMod.LOGGER.info("[PortPredictor] Combined: lr={}, delta={}, final={}, range=\u00b1{}", new Object[]{n2, n3, n, n5});
            return new PredictResult(n, n5, "combined");
        }
        return new PredictResult(-1, 200, "no_samples");
    }

    public static List<Integer> generateTargetPorts(int n, int n2) {
        ArrayList<Integer> arrayList = new ArrayList<Integer>();
        int n3 = Math.max(1024, n - n2);
        int n4 = Math.min(65535, n + n2);
        arrayList.add(n);
        for (int i = 1; i <= n2; ++i) {
            int n5 = n + i;
            int n6 = n - i;
            if (n5 <= n4) {
                arrayList.add(n5);
            }
            if (n6 < n3) continue;
            arrayList.add(n6);
        }
        return arrayList;
    }

    public static class PredictResult {
        public final int predictedPort;
        public final int range;
        public final String strategy;

        PredictResult(int n, int n2, String string) {
            this.predictedPort = n;
            this.range = n2;
            this.strategy = string;
        }
    }
}

