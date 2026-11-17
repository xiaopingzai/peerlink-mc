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
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public class ManageRoomScreen
extends VoxLinkScreenBase {
    private static final int LEAVE_BTN = 1;
    private static final int CLOSE_BTN = 2;
    private static final int INFO_BTN = 3;
    private final GuiScreen parentScreen;

    public ManageRoomScreen(GuiScreen guiScreen) {
        super("voxlink.gui.manage_room");
        this.parentScreen = guiScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        int n = this.width / 2;
        int n2 = this.height / 2 - 30;
        RoomInfo roomInfo = this.getRoom();
        if (roomInfo != null && roomInfo.isHost()) {
            this.addButton(2, n - 100, n2, 200, 20, I18n.format((String)"voxlink.gui.close_room", (Object[])new Object[0]));
            this.addButton(1, n - 100, n2 + 26, 200, 20, I18n.format((String)"voxlink.gui.leave_room", (Object[])new Object[0]));
        } else {
            this.addButton(1, n - 100, n2, 200, 20, I18n.format((String)"voxlink.gui.leave_room", (Object[])new Object[0]));
        }
        this.addButton(3, n - 100, n2 + 52, 200, 20, I18n.format((String)"voxlink.gui.room_info", (Object[])new Object[0]));
    }

    private RoomInfo getRoom() {
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        return roomManager != null ? roomManager.getCurrentRoom() : null;
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        RoomInfo roomInfo = this.getRoom();
        int n3 = this.width / 2;
        if (roomInfo != null) {
            String string = I18n.format((String)"voxlink.room_code", (Object[])new Object[]{roomInfo.getCode()});
            this.fontRenderer.drawString(string, (this.width - this.fontRenderer.getStringWidth(string)) / 2, this.height / 2 - 70, -1);
            String string2 = I18n.format((String)"voxlink.room_players", (Object[])new Object[]{roomInfo.getCurrentPlayers(), roomInfo.getMaxPlayers()});
            this.fontRenderer.drawString(string2, (this.width - this.fontRenderer.getStringWidth(string2)) / 2, this.height / 2 - 50, -5592406);
        } else {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.error.not_in_room", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.error.not_in_room", (Object[])new Object[0]))) / 2, this.height / 2 - 10, -43691);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (guiButton.id == 1) {
            if (roomManager != null) {
                roomManager.leaveRoom();
            }
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else if (guiButton.id == 2) {
            if (roomManager != null) {
                roomManager.closeRoom();
            }
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else if (guiButton.id == 3) {
            if (roomManager != null) {
                roomManager.showRoomInfo();
            }
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        }
    }
}

