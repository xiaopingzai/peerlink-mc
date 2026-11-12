/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.resources.I18n
 */
package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public abstract class VoxLinkScreenBase
extends GuiScreen {
    protected static final int BTN_W = 200;
    protected static final int BTN_H = 20;
    protected static final int BACK_BTN_ID = 1000;
    protected String titleKey;
    protected String subTitleKey;

    protected VoxLinkScreenBase(String string) {
        this(string, null);
    }

    protected VoxLinkScreenBase(String string, String string2) {
        this.titleKey = string;
        this.subTitleKey = string2;
    }

    protected void addButton(int n, int n2, int n3, int n4, int n5, String string) {
        this.field_146292_n.add(new GuiButton(n, n2, n3, n4, n5, string));
    }

    protected String t(String string) {
        return I18n.func_135052_a((String)string, (Object[])new Object[0]);
    }

    protected String t(String string, Object ... objectArray) {
        return I18n.func_135052_a((String)string, (Object[])objectArray);
    }

    public void func_73866_w_() {
        super.func_73866_w_();
        this.field_146292_n.clear();
        this.addBackButton();
    }

    protected void addBackButton() {
        this.addButton(1000, this.field_146294_l / 2 - 100, this.field_146295_m - 30, 200, 20, this.t("voxlink.gui.back"));
    }

    public void func_73863_a(int n, int n2, float f) {
        this.func_146276_q_();
        if (this.titleKey != null) {
            this.field_146289_q.func_78276_b(this.t(this.titleKey), (this.field_146294_l - this.field_146289_q.func_78256_a(this.t(this.titleKey))) / 2, 12, -1);
        }
        if (this.subTitleKey != null) {
            this.field_146289_q.func_78276_b(this.t(this.subTitleKey), (this.field_146294_l - this.field_146289_q.func_78256_a(this.t(this.subTitleKey))) / 2, 28, -5592406);
        }
        super.func_73863_a(n, n2, f);
    }

    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == 1000 && Minecraft.func_71410_x() != null) {
            this.actionPerformedBack();
        }
    }

    protected void actionPerformedBack() {
        if (Minecraft.func_71410_x() != null) {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
        }
    }

    public boolean func_73868_f() {
        return false;
    }
}

