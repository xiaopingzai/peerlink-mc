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
    public void initGui() {
        super.initGui();
        int n = this.width / 2;
        int n2 = this.height / 2 - 40;
        this.codeField = new GuiTextField(1, this.fontRenderer, n - 100, n2, 200, 20);
        this.codeField.setMaxStringLength(16);
        this.passwordField = new GuiTextField(2, this.fontRenderer, n - 100, n2 + 28, 200, 20);
        this.passwordField.setMaxStringLength(32);
        this.addButton(2, n - 100, n2 + 56, 200, 20, I18n.format((String)"voxlink.gui.join", (Object[])new Object[0]));
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        int n3 = this.width / 2;
        int n4 = this.height / 2 - 64;
        this.fontRenderer.drawString(I18n.format((String)"voxlink.gui.room_code", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.gui.room_code", (Object[])new Object[0]))) / 2, n4, -5592406);
        this.codeField.drawTextBox();
        this.fontRenderer.drawString(I18n.format((String)"voxlink.gui.password_optional", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.gui.password_optional", (Object[])new Object[0]))) / 2, n4 + 18, -5592406);
        this.passwordField.drawTextBox();
        if (this.joining) {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.joining.joining", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.joining.joining", (Object[])new Object[0]))) / 2, n4 + 66, -5592406);
        }
    }

    protected void keyTyped(char c, int n) {
        if (this.codeField.textboxKeyTyped(c, n) || this.passwordField.textboxKeyTyped(c, n)) {
            return;
        }
        if (n == 28) {
            this.onJoin();
            return;
        }
        super.keyTyped(c, n);
    }

    protected void mouseClicked(int n, int n2, int n3) {
        super.mouseClicked(n, n2, n3);
        JoinRoomScreen.handleFieldClick(this.codeField, n, n2);
        JoinRoomScreen.handleFieldClick(this.passwordField, n, n2);
    }

    private static void handleFieldClick(GuiTextField guiTextField, int n, int n2) {
        boolean bl = n >= guiTextField.x && n < guiTextField.x + guiTextField.getWidth() && n2 >= guiTextField.y && n2 < guiTextField.y + 12;
        guiTextField.setFocused(bl);
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 2) {
            this.onJoin();
        } else {
            super.actionPerformed(guiButton);
        }
    }

    private void onJoin() {
        if (this.joining) {
            return;
        }
        String string = this.codeField.getText().trim().toUpperCase();
        if (string.isEmpty()) {
            VoxLinkChat.print(I18n.format((String)"voxlink.error.invalid_room_code", (Object[])new Object[0]));
            return;
        }
        this.joining = true;
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (roomManager == null) {
            VoxLinkChat.print(I18n.format((String)"voxlink.error.not_initialized", (Object[])new Object[0]));
            this.joining = false;
            return;
        }
        String string2 = this.passwordField.getText();
        roomManager.joinRoom(string, string2).whenComplete((roomInfo, throwable) -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.addScheduledTask(() -> {
                if (throwable != null) {
                    this.joining = false;
                    VoxLinkChat.print(I18n.format((String)"voxlink.join.failed", (Object[])new Object[]{throwable.getMessage()}));
                    return;
                }
                minecraft.displayGuiScreen((GuiScreen)new AttemptingJoinScreen((GuiScreen)(this.parentScreen != null ? this.parentScreen : this), (RoomInfo)roomInfo));
            });
        });
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        }
    }

    public void updateScreen() {
        this.codeField.updateCursorCounter();
        this.passwordField.updateCursorCounter();
    }
}

