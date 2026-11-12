/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 */
package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class VoxLinkChat {
    public static void print(ITextComponent iTextComponent) {
        if (iTextComponent == null) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.func_71410_x();
            if (minecraft == null || minecraft.field_71456_v == null) {
                return;
            }
            minecraft.field_71456_v.func_146158_b().func_146227_a(iTextComponent);
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.debug("[VoxLinkChat] display failed: {}", (Object)exception.getMessage());
        }
    }

    public static void print(String string) {
        VoxLinkChat.print((ITextComponent)new TextComponentString(string));
    }

    private VoxLinkChat() {
    }
}

