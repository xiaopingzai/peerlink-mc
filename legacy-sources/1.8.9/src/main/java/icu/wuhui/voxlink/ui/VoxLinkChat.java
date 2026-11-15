/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.ChatComponentText
 *  net.minecraft.util.IChatComponent
 */
package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.LogHelper;
import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public final class VoxLinkChat {
    public static void print(IChatComponent iChatComponent) {
        if (iChatComponent == null) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.func_71410_x();
            if (minecraft == null || minecraft.field_71456_v == null) {
                return;
            }
            minecraft.field_71456_v.func_146158_b().func_146227_a(iChatComponent);
        }
        catch (Exception exception) {
            LogHelper.logDebug(VoxLinkMod.LOGGER, "[VoxLinkChat] display failed: {}", (Object)exception.getMessage());
        }
    }

    public static void print(String string) {
        VoxLinkChat.print((IChatComponent)new ChatComponentText(string));
    }

    private VoxLinkChat() {
    }
}

