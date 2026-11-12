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
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public class CreatingRoomScreen
extends VoxLinkScreenBase {
    private final GuiScreen parentScreen;
    private final RoomInfo roomInfo;
    private static final int CLOSE_BTN = 1;

    public CreatingRoomScreen(GuiScreen guiScreen, RoomInfo roomInfo) {
        super("voxlink.creating.title");
        this.parentScreen = guiScreen;
        this.roomInfo = roomInfo;
    }

    @Override
    public void func_73866_w_() {
        super.func_73866_w_();
        this.addButton(1, this.field_146294_l / 2 - 100, this.field_146295_m - 60, 200, 20, I18n.func_135052_a((String)"voxlink.gui.close_room", (Object[])new Object[0]));
    }

    @Override
    public void func_73863_a(int n, int n2, float f) {
        super.func_73863_a(n, n2, f);
        int n3 = this.field_146294_l / 2;
        if (this.roomInfo != null && this.roomInfo.getCode() != null && !this.roomInfo.getCode().isEmpty()) {
            this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.creating.room_ready", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.creating.room_ready", (Object[])new Object[0]))) / 2, this.field_146295_m / 2 - 30, -11141291);
            String string = I18n.func_135052_a((String)"voxlink.creating.room_code", (Object[])new Object[]{this.roomInfo != null ? this.roomInfo.getCode() : ""});
            this.field_146289_q.func_78276_b(string, (this.field_146294_l - this.field_146289_q.func_78256_a(string)) / 2, this.field_146295_m / 2 - 6, -1);
        } else {
            this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.creating.creating", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.creating.creating", (Object[])new Object[0]))) / 2, this.field_146295_m / 2 - 10, -5592406);
        }
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == 1) {
            if (VoxLinkMod.getRoomManager() != null) {
                VoxLinkMod.getRoomManager().closeRoom();
            }
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else {
            super.func_146284_a(guiButton);
        }
    }
}

