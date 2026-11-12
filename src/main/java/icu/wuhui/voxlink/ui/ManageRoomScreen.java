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
    public void func_73866_w_() {
        super.func_73866_w_();
        int n = this.field_146294_l / 2;
        int n2 = this.field_146295_m / 2 - 30;
        RoomInfo roomInfo = this.getRoom();
        if (roomInfo != null && roomInfo.isHost()) {
            this.addButton(2, n - 100, n2, 200, 20, I18n.func_135052_a((String)"voxlink.gui.close_room", (Object[])new Object[0]));
            this.addButton(1, n - 100, n2 + 26, 200, 20, I18n.func_135052_a((String)"voxlink.gui.leave_room", (Object[])new Object[0]));
        } else {
            this.addButton(1, n - 100, n2, 200, 20, I18n.func_135052_a((String)"voxlink.gui.leave_room", (Object[])new Object[0]));
        }
        this.addButton(3, n - 100, n2 + 52, 200, 20, I18n.func_135052_a((String)"voxlink.gui.room_info", (Object[])new Object[0]));
    }

    private RoomInfo getRoom() {
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        return roomManager != null ? roomManager.getCurrentRoom() : null;
    }

    @Override
    public void func_73863_a(int n, int n2, float f) {
        super.func_73863_a(n, n2, f);
        RoomInfo roomInfo = this.getRoom();
        int n3 = this.field_146294_l / 2;
        if (roomInfo != null) {
            String string = I18n.func_135052_a((String)"voxlink.room_code", (Object[])new Object[]{roomInfo.getCode()});
            this.field_146289_q.func_78276_b(string, (this.field_146294_l - this.field_146289_q.func_78256_a(string)) / 2, this.field_146295_m / 2 - 70, -1);
            String string2 = I18n.func_135052_a((String)"voxlink.room_players", (Object[])new Object[]{roomInfo.getCurrentPlayers(), roomInfo.getMaxPlayers()});
            this.field_146289_q.func_78276_b(string2, (this.field_146294_l - this.field_146289_q.func_78256_a(string2)) / 2, this.field_146295_m / 2 - 50, -5592406);
        } else {
            this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.error.not_in_room", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.error.not_in_room", (Object[])new Object[0]))) / 2, this.field_146295_m / 2 - 10, -43691);
        }
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (guiButton.field_146127_k == 1) {
            if (roomManager != null) {
                roomManager.leaveRoom();
            }
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else if (guiButton.field_146127_k == 2) {
            if (roomManager != null) {
                roomManager.closeRoom();
            }
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else if (guiButton.field_146127_k == 3) {
            if (roomManager != null) {
                roomManager.showRoomInfo();
            }
        } else {
            super.func_146284_a(guiButton);
        }
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
        }
    }
}

