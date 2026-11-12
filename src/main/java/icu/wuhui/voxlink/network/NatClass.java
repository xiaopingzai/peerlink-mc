/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.ScenarioTier;
import icu.wuhui.voxlink.network.StunProbe;

public enum NatClass {
    UNKNOWN,
    CONE,
    EASY_SYM,
    HARD_SYM;


    public boolean isSymmetric() {
        return this == EASY_SYM || this == HARD_SYM;
    }

    public static NatClass fromStunProbeResult(StunProbe.NatType natType) {
        if (natType == null) {
            return UNKNOWN;
        }
        switch (natType) {
            case FULL_CONE: 
            case RESTRICTED_CONE: 
            case PORT_RESTRICTED_CONE: {
                return CONE;
            }
            case SYMMETRIC_EASY_INC: 
            case SYMMETRIC_EASY_DEC: {
                return EASY_SYM;
            }
            case SYMMETRIC: {
                return HARD_SYM;
            }
        }
        return UNKNOWN;
    }

    public static PunchProfile recommendProfile(NatClass natClass, NatClass natClass2) {
        return NatClass.recommendProfile(natClass, natClass2, ScenarioTier.Tier.NORMAL);
    }

    public static PunchProfile recommendProfile(NatClass natClass, NatClass natClass2, ScenarioTier.Tier tier) {
        if (natClass == null || natClass2 == null || natClass == UNKNOWN || natClass2 == UNKNOWN) {
            return PunchProfile.AGGRESSIVE;
        }
        if (natClass == HARD_SYM && natClass2 == HARD_SYM) {
            return PunchProfile.HARDSYM;
        }
        if (natClass == EASY_SYM && natClass2 == HARD_SYM || natClass == HARD_SYM && natClass2 == EASY_SYM) {
            return PunchProfile.HARDSYM;
        }
        if (natClass == EASY_SYM && natClass2 == EASY_SYM) {
            return PunchProfile.EASY_SYM_DUAL;
        }
        if (natClass == CONE && natClass2 == CONE) {
            return PunchProfile.FAST_LANE;
        }
        if (natClass == CONE && natClass2 == EASY_SYM || natClass == EASY_SYM && natClass2 == CONE) {
            return PunchProfile.V100;
        }
        if (natClass == CONE && natClass2 == HARD_SYM || natClass == HARD_SYM && natClass2 == CONE) {
            return PunchProfile.AGGRESSIVE;
        }
        return PunchProfile.DEFAULT;
    }
}

