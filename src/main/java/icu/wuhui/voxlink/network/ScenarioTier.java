/*
 * Decompiled with CFR 0.152.
 */
package icu.wuhui.voxlink.network;

public final class ScenarioTier {
    private ScenarioTier() {
    }

    public static Tier classify(boolean bl, boolean bl2, boolean bl3, boolean bl4, boolean bl5) {
        if (bl && bl2) {
            return Tier.HARD_DUAL_SYM;
        }
        if (bl3 || bl4 || bl5) {
            return Tier.HARD_ONE_SYM;
        }
        if (bl || bl2) {
            return Tier.HARD_ONE_SYM;
        }
        return Tier.NORMAL;
    }

    public static boolean isHard(Tier tier) {
        return tier == Tier.HARD_DUAL_SYM || tier == Tier.HARD_ONE_SYM;
    }

    public static String key(Tier tier) {
        return tier == null ? "normal" : tier.name().toLowerCase();
    }

    public static enum Tier {
        HARD_DUAL_SYM,
        HARD_ONE_SYM,
        NORMAL;

    }
}

