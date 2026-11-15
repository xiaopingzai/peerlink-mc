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
    public void func_73866_w_() {
        super.func_73866_w_();
        this.addButton(1, this.field_146294_l / 2 - 100, this.field_146295_m - 60, 200, 20, I18n.func_135052_a((String)"voxlink.gui.cancel", (Object[])new Object[0]));
    }

    public void func_73876_c() {
        super.func_73876_c();
        ++this.tickCounter;
        Object var1_1 = null;
        if (ConnectionHelper.isMcTrulyConnected()) {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
        } else if ((this.roomLost || ConnectionHelper.isConnectionRejected()) && this.roomLost && this.parentScreen != null) {
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        }
    }

    @Override
    public void func_73863_a(int n, int n2, float f) {
        super.func_73863_a(n, n2, f);
        int n3 = this.field_146294_l / 2;
        int n4 = this.field_146295_m / 2 - 20;
        if (this.roomLost) {
            this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.attempting_join.room_lost", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.attempting_join.room_lost", (Object[])new Object[0]))) / 2, n4, -43691);
        } else {
            String string;
            String string2 = I18n.func_135052_a((String)"voxlink.attempting_join.joining", (Object[])new Object[]{this.roomInfo != null ? this.roomInfo.getCode() : ""});
            this.field_146289_q.func_78276_b(string2, (this.field_146294_l - this.field_146289_q.func_78256_a(string2)) / 2, n4, -1);
            String string3 = string = this.roomInfo != null ? this.roomInfo.getConnectionMode() : null;
            if (string != null && !string.isEmpty()) {
                this.field_146289_q.func_78276_b(string, (this.field_146294_l - this.field_146289_q.func_78256_a(string)) / 2, n4 + 22, -5592406);
            }
        }
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == 1) {
            VoxLinkMod.getRoomManager().leaveRoom();
            if (this.parentScreen != null) {
                Minecraft.func_71410_x().func_147108_a(this.parentScreen);
            } else {
                Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
            }
        } else {
            super.func_146284_a(guiButton);
        }
    }

    @Override
    protected void actionPerformedBack() {
        VoxLinkMod.getRoomManager().leaveRoom();
        if (this.parentScreen != null) {
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
        }
    }
}

