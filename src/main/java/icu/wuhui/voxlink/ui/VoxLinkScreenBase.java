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
        this.buttonList.add(new GuiButton(n, n2, n3, n4, n5, string));
    }

    protected String t(String string) {
        return I18n.format((String)string, (Object[])new Object[0]);
    }

    protected String t(String string, Object ... objectArray) {
        return I18n.format((String)string, (Object[])objectArray);
    }

    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        this.addBackButton();
    }

    protected void addBackButton() {
        this.addButton(1000, this.width / 2 - 100, this.height - 30, 200, 20, this.t("voxlink.gui.back"));
    }

    public void drawScreen(int n, int n2, float f) {
        this.drawDefaultBackground();
        if (this.titleKey != null) {
            this.fontRenderer.drawString(this.t(this.titleKey), (this.width - this.fontRenderer.getStringWidth(this.t(this.titleKey))) / 2, 12, -1);
        }
        if (this.subTitleKey != null) {
            this.fontRenderer.drawString(this.t(this.subTitleKey), (this.width - this.fontRenderer.getStringWidth(this.t(this.subTitleKey))) / 2, 28, -5592406);
        }
        super.drawScreen(n, n2, f);
    }

    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 1000 && Minecraft.getMinecraft() != null) {
            this.actionPerformedBack();
        }
    }

    protected void actionPerformedBack() {
        if (Minecraft.getMinecraft() != null) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        }
    }

    public boolean doesGuiPauseGame() {
        return false;
    }
}

