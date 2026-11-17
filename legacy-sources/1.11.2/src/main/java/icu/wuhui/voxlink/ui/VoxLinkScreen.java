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
import icu.wuhui.voxlink.network.TicketClient;
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
    private static final int LINKS_BTN = 16;
    private static final int FEEDBACK_BTN = 17;
    private final GuiScreen parentScreen;

    public VoxLinkScreen() {
        this((GuiScreen)null);
    }

    public VoxLinkScreen(GuiScreen guiScreen) {
        super("voxlink.gui.title");
        this.parentScreen = guiScreen;
    }

    @Override
    public void initGui() {
        boolean bl;
        int n;
        super.initGui();
        // 首次进入主屏静默轮询一次工单回复状态（失败仅 debug，不阻塞界面）。
        TicketClient.pollOnceIfNeeded(null);
        int n2 = this.width / 2;
        int n3 = n = this.height / 2 - 66;
        this.addButton(4, n2 - 100, n3, 200, 20, I18n.format((String)"voxlink.browse_rooms", (Object[])new Object[0]));
        n3 += 26;
        boolean bl2 = Minecraft.getMinecraft().isIntegratedServerRunning();
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        boolean bl3 = bl = roomManager != null && roomManager.getCurrentRoom() != null;
        if (bl2 && !bl) {
            this.addButton(1, n2 - 100, n3, 200, 20, I18n.format((String)"voxlink.gui.create_room", (Object[])new Object[0]));
            n3 += 26;
        }
        this.addButton(2, n2 - 100, n3, 200, 20, I18n.format((String)"voxlink.gui.join_room", (Object[])new Object[0]));
        n3 += 26;
        if (bl) {
            this.addButton(3, n2 - 100, n3, 200, 20, I18n.format((String)"voxlink.gui.manage_room", (Object[])new Object[0]));
        }
        this.addButton(FEEDBACK_BTN, n2 - 100, this.height - 82, 200, 20, I18n.format((String)"voxlink.feedback", (Object[])new Object[0]));
        this.addButton(LINKS_BTN, n2 - 100, this.height - 58, 200, 20, I18n.format((String)"voxlink.links.title", (Object[])new Object[0]));
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 4) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)new RoomBrowserScreen(this));
        } else if (guiButton.id == 1) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)new CreateRoomScreen(this));
        } else if (guiButton.id == 2) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)new JoinRoomScreen(this));
        } else if (guiButton.id == 3) {
            RoomManager roomManager = VoxLinkMod.getRoomManager();
            if (roomManager != null && roomManager.getCurrentRoom() != null) {
                Minecraft.getMinecraft().displayGuiScreen((GuiScreen)new ManageRoomScreen(this));
            } else {
                VoxLinkChat.print((ITextComponent)new TextComponentString(I18n.format((String)"voxlink.error.not_in_room", (Object[])new Object[0])));
            }
        } else if (guiButton.id == LINKS_BTN) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)new RelatedLinksScreen(this));
        } else if (guiButton.id == FEEDBACK_BTN) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)new FeedbackScreen(this));
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void addBackButton() {
        this.addButton(1000, this.width / 2 - 100, this.height - 30, 200, 20, I18n.format((String)"voxlink.gui.back", (Object[])new Object[0]));
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

