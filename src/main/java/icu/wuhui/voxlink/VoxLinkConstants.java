/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraftforge.fml.common.Loader
 */
package icu.wuhui.voxlink;

import net.minecraftforge.fml.common.Loader;

public final class VoxLinkConstants {
    public static final String MOD_VERSION = "1.1.9";
    public static final String GAME_VERSION = "1.12x";
    public static final String LOADER = "forge";
    public static final int PROTOCOL_VERSION = 340;

    public static String getGameVersion() {
        try {
            return Loader.instance().getMCVersionString().replace("Minecraft ", "");
        }
        catch (Exception exception) {
            return GAME_VERSION;
        }
    }

    private VoxLinkConstants() {
    }
}

