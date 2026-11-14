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
    public void initGui() {
        super.initGui();
        this.addButton(1, this.width / 2 - 100, this.height - 60, 200, 20, I18n.format((String)"voxlink.gui.close_room", (Object[])new Object[0]));
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        int n3 = this.width / 2;
        if (this.roomInfo != null && this.roomInfo.getCode() != null && !this.roomInfo.getCode().isEmpty()) {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.creating.room_ready", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.creating.room_ready", (Object[])new Object[0]))) / 2, this.height / 2 - 30, -11141291);
            String string = I18n.format((String)"voxlink.creating.room_code", (Object[])new Object[]{this.roomInfo != null ? this.roomInfo.getCode() : ""});
            this.fontRenderer.drawString(string, (this.width - this.fontRenderer.getStringWidth(string)) / 2, this.height / 2 - 6, -1);
        } else {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.creating.creating", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.creating.creating", (Object[])new Object[0]))) / 2, this.height / 2 - 10, -5592406);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 1) {
            if (VoxLinkMod.getRoomManager() != null) {
                VoxLinkMod.getRoomManager().closeRoom();
            }
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            super.actionPerformed(guiButton);
        }
    }
}

