/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 */
package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.CreateRoomScreen;
import icu.wuhui.voxlink.ui.JoinRoomScreen;
import icu.wuhui.voxlink.ui.ManageRoomScreen;
import icu.wuhui.voxlink.ui.RoomBrowserScreen;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public class VoxLinkScreen
extends VoxLinkScreenBase {
    private static final int CREATE_BTN = 1;
    private static final int JOIN_BTN = 2;
    private static final int MANAGE_BTN = 3;
    private static final int BROWSE_BTN = 4;
    private final GuiScreen parentScreen;

    public VoxLinkScreen() {
        this((GuiScreen)null);
    }

    public VoxLinkScreen(GuiScreen guiScreen) {
        super("voxlink.gui.title");
        this.parentScreen = guiScreen;
    }

    @Override
    public void func_73866_w_() {
        boolean bl;
        int n;
        super.func_73866_w_();
        int n2 = this.field_146294_l / 2;
        int n3 = n = this.field_146295_m / 2 - 66;
        this.addButton(4, n2 - 100, n3, 200, 20, I18n.func_135052_a((String)"voxlink.browse_rooms", (Object[])new Object[0]));
        n3 += 26;
        boolean bl2 = Minecraft.func_71410_x().func_71387_A();
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        boolean bl3 = bl = roomManager != null && roomManager.getCurrentRoom() != null;
        if (bl2 && !bl) {
            this.addButton(1, n2 - 100, n3, 200, 20, I18n.func_135052_a((String)"voxlink.gui.create_room", (Object[])new Object[0]));
            n3 += 26;
        }
        this.addButton(2, n2 - 100, n3, 200, 20, I18n.func_135052_a((String)"voxlink.gui.join_room", (Object[])new Object[0]));
        n3 += 26;
        if (bl) {
            this.addButton(3, n2 - 100, n3, 200, 20, I18n.func_135052_a((String)"voxlink.gui.manage_room", (Object[])new Object[0]));
        }
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == 4) {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)new RoomBrowserScreen(this));
        } else if (guiButton.field_146127_k == 1) {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)new CreateRoomScreen(this));
        } else if (guiButton.field_146127_k == 2) {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)new JoinRoomScreen(this));
        } else if (guiButton.field_146127_k == 3) {
            RoomManager roomManager = VoxLinkMod.getRoomManager();
            if (roomManager != null && roomManager.getCurrentRoom() != null) {
                Minecraft.func_71410_x().func_147108_a((GuiScreen)new ManageRoomScreen(this));
            } else {
                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.func_135052_a((String)"voxlink.error.not_in_room", (Object[])new Object[0])));
            }
        } else {
            super.func_146284_a(guiButton);
        }
    }

    @Override
    protected void addBackButton() {
        this.addButton(1000, this.field_146294_l / 2 - 100, this.field_146295_m - 30, 200, 20, I18n.func_135052_a((String)"voxlink.gui.back", (Object[])new Object[0]));
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

