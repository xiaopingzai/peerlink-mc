package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * 1.12.2 legacy 简易配置屏。
 * 当前挂载：加入前必装 Mod 检查开关（joinRequiredModsCheck）。
 * 本环境无法运行客户端，仅保证编译级通过，界面表现需真机复核。
 */
public class ConfigScreen
extends VoxLinkScreenBase {
    private static final int JOIN_CHECK_BTN = 21;
    private final GuiScreen parentScreen;

    public ConfigScreen(GuiScreen guiScreen) {
        super("voxlink.simple_config");
        this.parentScreen = guiScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        int y = this.height / 2 - 20;
        this.addButton(JOIN_CHECK_BTN, centerX - 100, y, 200, 20, this.buildJoinCheckLabel());
    }

    private String buildJoinCheckLabel() {
        String onOff = I18n.format(VoxLinkMod.getConfig().isJoinRequiredModsCheck() ? "voxlink.terracotta.on" : "voxlink.terracotta.off");
        return I18n.format("voxlink.modsync.toggle.join") + ": " + onOff;
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == JOIN_CHECK_BTN) {
            boolean v = !VoxLinkMod.getConfig().isJoinRequiredModsCheck();
            VoxLinkMod.getConfig().setJoinRequiredModsCheck(v);
            VoxLinkMod.getConfig().save();
            guiButton.displayString = this.buildJoinCheckLabel();
            return;
        }
        super.actionPerformed(guiButton);
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
