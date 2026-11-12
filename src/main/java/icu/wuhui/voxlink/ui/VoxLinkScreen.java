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
    public void func_73866_w_() {
        super.func_73866_w_();
        int centerX = this.field_146294_l / 2;
        int y = this.field_146295_m / 2 - 66;
        this.addButton(BROWSE_BTN, centerX - 100, y, 200, 20, I18n.func_135052_a("voxlink.browse_rooms", new Object[0]));
        y += 26;
        boolean singleplayer = Minecraft.func_71410_x().func_71387_A();
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        boolean inRoom = roomManager != null && roomManager.getCurrentRoom() != null;
        if (singleplayer && !inRoom) {
            this.addButton(CREATE_BTN, centerX - 100, y, 200, 20, I18n.func_135052_a("voxlink.gui.create_room", new Object[0]));
            y += 26;
        }
        this.addButton(JOIN_BTN, centerX - 100, y, 200, 20, I18n.func_135052_a("voxlink.gui.join_room", new Object[0]));
        y += 26;
        if (inRoom) {
            this.addButton(MANAGE_BTN, centerX - 100, y, 200, 20, I18n.func_135052_a("voxlink.gui.manage_room", new Object[0]));
        }
        super.addButton(TERRA_BTN, this.field_146294_l / 2 - 100, 260, 200, 20, I18n.func_135052_a("voxlink.download_terracotta", new Object[0]));
        super.addButton(CONFIG_BTN, this.field_146294_l / 2 - 100, 284, 200, 20, I18n.func_135052_a("voxlink.simple_config", new Object[0]));
        super.addButton(LOGS_BTN, this.field_146294_l / 2 - 100, 308, 200, 20, I18n.func_135052_a("voxlink.upload_logs", new Object[0]));
        super.addButton(RELAY_BTN, this.field_146294_l / 2 - 100, 332, 200, 20, I18n.func_135052_a("voxlink.p2p_relay", new Object[0]));
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == TERRA_BTN) {
            VoxLinkMod.LOGGER.info("Download terracotta: https://link.voxdev.top");
            return;
        }
        if (guiButton.field_146127_k == CONFIG_BTN) {
            VoxLinkMod.LOGGER.info("Simple config not implemented yet");
            return;
        }
        if (guiButton.field_146127_k == LOGS_BTN) {
            LogUploadState.setLogUploadEnabled(!LogUploadState.isLogUploadEnabled());
            VoxLinkMod.LOGGER.info("Log upload enabled: {}", LogUploadState.isLogUploadEnabled());
            return;
        }
        if (guiButton.field_146127_k == RELAY_BTN) {
            VoxLinkConfig config = VoxLinkMod.getConfig();
            config.setRelayEnabled(!config.isRelayEnabled());
            config.save();
            return;
        }
        if (guiButton.field_146127_k == BROWSE_BTN) {
            Minecraft.func_71410_x().func_147108_a(new RoomBrowserScreen(this));
        } else if (guiButton.field_146127_k == CREATE_BTN) {
            Minecraft.func_71410_x().func_147108_a(new CreateRoomScreen(this));
        } else if (guiButton.field_146127_k == JOIN_BTN) {
            Minecraft.func_71410_x().func_147108_a(new JoinRoomScreen(this));
        } else if (guiButton.field_146127_k == MANAGE_BTN) {
            RoomManager roomManager = VoxLinkMod.getRoomManager();
            if (roomManager != null && roomManager.getCurrentRoom() != null) {
                Minecraft.func_71410_x().func_147108_a(new ManageRoomScreen(this));
            } else {
                VoxLinkChat.print(new TextComponentString(I18n.func_135052_a("voxlink.error.not_in_room", new Object[0])));
            }
        } else {
            super.func_146284_a(guiButton);
        }
    }

    @Override
    protected void addBackButton() {
        this.addButton(1000, this.field_146294_l / 2 - 100, this.field_146295_m - 30, 200, 20, I18n.func_135052_a("voxlink.gui.back", new Object[0]));
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
