/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.PunchStrategy;

public final class PunchStrategySelector {
    private PunchStrategySelector() {
    }

    public static PunchStrategy select(NatClass natClass, NatClass natClass2, int n, boolean bl) {
        if (bl) {
            return PunchStrategy.DIRECT_ONLY;
        }
        if (natClass == null || natClass2 == null) {
            return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
        }
        if (natClass == NatClass.UNKNOWN || natClass2 == NatClass.UNKNOWN) {
            NatClass natClass3 = natClass == NatClass.UNKNOWN ? natClass2 : natClass;
            NatClass natClass4 = natClass3;
            if (natClass3.isSymmetric()) {
                return PunchStrategy.REVERSE_FIRST;
            }
            return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
        }
        if (natClass == NatClass.CONE && natClass2 == NatClass.EASY_SYM || natClass == NatClass.EASY_SYM && natClass2 == NatClass.CONE) {
            return PunchStrategy.REVERSE_FIRST;
        }
        if (natClass == NatClass.CONE && natClass2 == NatClass.HARD_SYM || natClass == NatClass.HARD_SYM && natClass2 == NatClass.CONE) {
            return n == 0 ? PunchStrategy.REVERSE_ONLY : PunchStrategy.REVERSE_THEN_FORWARD;
        }
        if (natClass == NatClass.EASY_SYM && natClass2 == NatClass.HARD_SYM || natClass == NatClass.HARD_SYM && natClass2 == NatClass.EASY_SYM) {
            return PunchStrategy.REVERSE_FIRST;
        }
        if (natClass == NatClass.HARD_SYM && natClass2 == NatClass.HARD_SYM) {
            return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
        }
        if (natClass == NatClass.EASY_SYM && natClass2 == NatClass.EASY_SYM) {
            return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
        }
        if (natClass == NatClass.CONE && natClass2 == NatClass.CONE) {
            return PunchStrategy.DIRECT_ONLY;
        }
        return PunchStrategy.DIRECT_WITH_REVERSE_PARALLEL;
    }
}

