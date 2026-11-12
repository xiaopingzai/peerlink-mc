/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.gui.GuiTextField
 *  net.minecraft.client.resources.I18n
 */
package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.AttemptingJoinScreen;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

public class JoinRoomScreen
extends VoxLinkScreenBase {
    private static final int JOIN_BTN = 2;
    private final GuiScreen parentScreen;
    private GuiTextField codeField;
    private GuiTextField passwordField;
    private boolean joining = false;

    public JoinRoomScreen(GuiScreen guiScreen) {
        super("voxlink.gui.join_room");
        this.parentScreen = guiScreen;
    }

    @Override
    public void func_73866_w_() {
        super.func_73866_w_();
        int n = this.field_146294_l / 2;
        int n2 = this.field_146295_m / 2 - 40;
        this.codeField = new GuiTextField(1, this.field_146289_q, n - 100, n2, 200, 20);
        this.codeField.func_146203_f(16);
        this.passwordField = new GuiTextField(2, this.field_146289_q, n - 100, n2 + 28, 200, 20);
        this.passwordField.func_146203_f(32);
        this.addButton(2, n - 100, n2 + 56, 200, 20, I18n.func_135052_a((String)"voxlink.gui.join", (Object[])new Object[0]));
    }

    @Override
    public void func_73863_a(int n, int n2, float f) {
        super.func_73863_a(n, n2, f);
        int n3 = this.field_146294_l / 2;
        int n4 = this.field_146295_m / 2 - 64;
        this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.gui.room_code", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.gui.room_code", (Object[])new Object[0]))) / 2, n4, -5592406);
        this.codeField.func_146194_f();
        this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.gui.password_optional", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.gui.password_optional", (Object[])new Object[0]))) / 2, n4 + 18, -5592406);
        this.passwordField.func_146194_f();
        if (this.joining) {
            this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.joining.joining", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.joining.joining", (Object[])new Object[0]))) / 2, n4 + 66, -5592406);
        }
    }

    protected void func_73869_a(char c, int n) {
        if (this.codeField.func_146201_a(c, n) || this.passwordField.func_146201_a(c, n)) {
            return;
        }
        if (n == 28) {
            this.onJoin();
            return;
        }
        super.func_73869_a(c, n);
    }

    protected void func_73864_a(int n, int n2, int n3) {
        super.func_73864_a(n, n2, n3);
        JoinRoomScreen.handleFieldClick(this.codeField, n, n2);
        JoinRoomScreen.handleFieldClick(this.passwordField, n, n2);
    }

    private static void handleFieldClick(GuiTextField guiTextField, int n, int n2) {
        boolean bl = n >= guiTextField.field_146209_f && n < guiTextField.field_146209_f + guiTextField.func_146200_o() && n2 >= guiTextField.field_146210_g && n2 < guiTextField.field_146210_g + 12;
        guiTextField.func_146195_b(bl);
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == 2) {
            this.onJoin();
        } else {
            super.func_146284_a(guiButton);
        }
    }

    private void onJoin() {
        if (this.joining) {
            return;
        }
        String string = this.codeField.func_146179_b().trim().toUpperCase();
        if (string.isEmpty()) {
            VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.error.invalid_room_code", (Object[])new Object[0]));
            return;
        }
        this.joining = true;
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (roomManager == null) {
            VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.error.not_initialized", (Object[])new Object[0]));
            this.joining = false;
            return;
        }
        String string2 = this.passwordField.func_146179_b();
        roomManager.joinRoom(string, string2).whenComplete((roomInfo, throwable) -> {
            Minecraft minecraft = Minecraft.func_71410_x();
            minecraft.func_152344_a(() -> {
                if (throwable != null) {
                    this.joining = false;
                    VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.join.failed", (Object[])new Object[]{throwable.getMessage()}));
                    return;
                }
                minecraft.func_147108_a((GuiScreen)new AttemptingJoinScreen((GuiScreen)(this.parentScreen != null ? this.parentScreen : this), (RoomInfo)roomInfo));
            });
        });
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
        }
    }

    public void func_73876_c() {
        this.codeField.func_146178_a();
        this.passwordField.func_146178_a();
    }
}

