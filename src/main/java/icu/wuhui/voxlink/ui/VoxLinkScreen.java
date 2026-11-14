package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkClient;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.config.LogUploadState;
import icu.wuhui.voxlink.config.VoxLinkConfig;
import icu.wuhui.voxlink.room.RoomManager;
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
    private static final int TERRA_BTN = 11;
    private static final int CONFIG_BTN = 12;
    private static final int LOGS_BTN = 13;
    private static final int RELAY_BTN = 14;
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
        super.initGui();
        int centerX = this.width / 2;
        int y = this.height / 2 - 66;
        this.addButton(BROWSE_BTN, centerX - 100, y, 200, 20, I18n.format("voxlink.browse_rooms", new Object[0]));
        y += 26;
        boolean singleplayer = Minecraft.getMinecraft().isIntegratedServerRunning();
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        boolean inRoom = roomManager != null && roomManager.getCurrentRoom() != null;
        if (singleplayer && !inRoom) {
            this.addButton(CREATE_BTN, centerX - 100, y, 200, 20, I18n.format("voxlink.gui.create_room", new Object[0]));
            y += 26;
        }
        this.addButton(JOIN_BTN, centerX - 100, y, 200, 20, I18n.format("voxlink.gui.join_room", new Object[0]));
        y += 26;
        if (inRoom) {
            this.addButton(MANAGE_BTN, centerX - 100, y, 200, 20, I18n.format("voxlink.gui.manage_room", new Object[0]));
        }
        super.addButton(TERRA_BTN, this.width / 2 - 100, 260, 200, 20, I18n.format("voxlink.download_terracotta", new Object[0]));
        super.addButton(CONFIG_BTN, this.width / 2 - 100, 284, 200, 20, I18n.format("voxlink.simple_config", new Object[0]));
        super.addButton(LOGS_BTN, this.width / 2 - 100, 308, 200, 20, I18n.format("voxlink.upload_logs", new Object[0]));
        super.addButton(RELAY_BTN, this.width / 2 - 100, 332, 200, 20, I18n.format("voxlink.p2p_relay", new Object[0]));
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == TERRA_BTN) {
            VoxLinkMod.LOGGER.info("Download terracotta: https://link.voxdev.top");
            return;
        }
        if (guiButton.id == CONFIG_BTN) {
            VoxLinkMod.LOGGER.info("Simple config not implemented yet");
            return;
        }
        if (guiButton.id == LOGS_BTN) {
            LogUploadState.setLogUploadEnabled(!LogUploadState.isLogUploadEnabled());
            VoxLinkMod.LOGGER.info("Log upload enabled: {}", LogUploadState.isLogUploadEnabled());
            return;
        }
        if (guiButton.id == RELAY_BTN) {
            VoxLinkConfig config = VoxLinkMod.getConfig();
            config.setRelayEnabled(!config.isRelayEnabled());
            config.save();
            return;
        }
        if (guiButton.id == BROWSE_BTN) {
            Minecraft.getMinecraft().displayGuiScreen(new RoomBrowserScreen(this));
        } else if (guiButton.id == CREATE_BTN) {
            Minecraft.getMinecraft().displayGuiScreen(new CreateRoomScreen(this));
        } else if (guiButton.id == JOIN_BTN) {
            Minecraft.getMinecraft().displayGuiScreen(new JoinRoomScreen(this));
        } else if (guiButton.id == MANAGE_BTN) {
            RoomManager roomManager = VoxLinkMod.getRoomManager();
            if (roomManager != null && roomManager.getCurrentRoom() != null) {
                Minecraft.getMinecraft().displayGuiScreen(new ManageRoomScreen(this));
            } else {
                VoxLinkChat.print(new TextComponentString(I18n.format("voxlink.error.not_in_room", new Object[0])));
            }
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void addBackButton() {
        this.addButton(1000, this.width / 2 - 100, this.height - 30, 200, 20, I18n.format("voxlink.gui.back", new Object[0]));
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
