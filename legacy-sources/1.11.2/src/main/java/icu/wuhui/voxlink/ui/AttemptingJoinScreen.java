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

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public class AttemptingJoinScreen
extends VoxLinkScreenBase {
    private final GuiScreen parentScreen;
    private final RoomInfo roomInfo;
    private int tickCounter = 0;
    private volatile boolean roomLost = false;

    public AttemptingJoinScreen(GuiScreen guiScreen, RoomInfo roomInfo) {
        super("voxlink.attempting_join.title");
        this.parentScreen = guiScreen;
        this.roomInfo = roomInfo;
    }

    public void onRoomLost() {
        this.roomLost = true;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.addButton(1, this.width / 2 - 100, this.height - 60, 200, 20, I18n.format((String)"voxlink.gui.cancel", (Object[])new Object[0]));
    }

    public void updateScreen() {
        super.updateScreen();
        ++this.tickCounter;
        Object var1_1 = null;
        if (ConnectionHelper.isMcTrulyConnected()) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        } else if ((this.roomLost || ConnectionHelper.isConnectionRejected()) && this.roomLost && this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        }
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        int n3 = this.width / 2;
        int n4 = this.height / 2 - 20;
        if (this.roomLost) {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.attempting_join.room_lost", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.attempting_join.room_lost", (Object[])new Object[0]))) / 2, n4, -43691);
        } else {
            String string;
            String string2 = I18n.format((String)"voxlink.attempting_join.joining", (Object[])new Object[]{this.roomInfo != null ? this.roomInfo.getCode() : ""});
            this.fontRenderer.drawString(string2, (this.width - this.fontRenderer.getStringWidth(string2)) / 2, n4, -1);
            String string3 = string = this.roomInfo != null ? this.roomInfo.getConnectionMode() : null;
            if (string != null && !string.isEmpty()) {
                this.fontRenderer.drawString(string, (this.width - this.fontRenderer.getStringWidth(string)) / 2, n4 + 22, -5592406);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 1) {
            VoxLinkMod.getRoomManager().leaveRoom();
            if (this.parentScreen != null) {
                Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
            } else {
                Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
            }
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void actionPerformedBack() {
        VoxLinkMod.getRoomManager().leaveRoom();
        if (this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        }
    }
}

