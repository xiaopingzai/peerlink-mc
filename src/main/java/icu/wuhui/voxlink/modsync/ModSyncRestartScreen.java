package icu.wuhui.voxlink.modsync;

import icu.wuhui.voxlink.ui.VoxLinkColors;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

/**
 * 强制重启屏：必装模组已落盘但需重启生效。全屏无 ESC 退出、无返回，
 * 唯一动作是退出游戏——把"玩家只做一个动作"做实。
 * Forge 1.12.2 适配：GuiScreen/GuiButton/I18n 替代 Fabric Screen/Button/Component。
 */
public class ModSyncRestartScreen extends VoxLinkScreenBase {
    private static final int EXIT_BTN_ID = 2001;
    private final int downloadedCount;

    public ModSyncRestartScreen(int downloadedCount) {
        super("voxlink.modsync.restart_title");
        this.downloadedCount = downloadedCount;
    }

    @Override
    protected void addBackButton() {
        // 本屏无返回按钮
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        this.addButton(EXIT_BTN_ID, centerX - 100, this.height / 2 + 40, 200, 20, this.t("voxlink.modsync.exit_game"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == EXIT_BTN_ID) {
            ModSyncLog.info("exit game requested from restart screen");
            Minecraft.getMinecraft().shutdown();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 全屏深色遮罩，保证跨版本一致
        Gui.drawRect(0, 0, this.width, this.height, 0xE60C0C10);
        super.drawScreen(mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        this.drawCenteredString(this.fontRenderer, this.t("voxlink.modsync.restart_title"), centerX, this.height / 2 - 48, 0xFF5555);
        this.drawCenteredString(
            this.fontRenderer,
            this.t("voxlink.modsync.restart_line1", this.downloadedCount),
            centerX,
            this.height / 2 - 24,
            VoxLinkColors.WHITE
        );
        this.drawCenteredString(
            this.fontRenderer,
            this.t("voxlink.modsync.restart_line2"),
            centerX,
            this.height / 2 - 8,
            VoxLinkColors.WHITE
        );
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 吞掉 ESC：本屏唯一出口是"退出游戏"按钮
    }

    @Override
    public void onGuiClosed() {
        // 吞掉关闭请求：本屏唯一出口是"退出游戏"按钮
    }
}
