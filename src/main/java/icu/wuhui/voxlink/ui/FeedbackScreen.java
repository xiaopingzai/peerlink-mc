package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * 反馈选择界面（移植 26.3 FeedbackScreen 到 Forge 1.12.2）。
 * 三选一：站内反馈表单、GitHub Issues、Gitee Issues。
 * 我的工单入口依赖 TicketClient，待票据客户端移植后再接入。
 */
public class FeedbackScreen extends VoxLinkScreenBase {
    private static final String GITHUB_ISSUES_URL = "https://github.com/AUGUHDAR/VoxLink/issues";
    private static final String GITEE_ISSUES_URL = "https://gitee.com/AUGUHDAR/VoxLink/issues";

    private static final int BTN_SITE = 200;
    private static final int BTN_GITHUB = 201;
    private static final int BTN_GITEE = 202;

    private final GuiScreen parentScreen;

    public FeedbackScreen(GuiScreen parent) {
        super("voxlink.fb.title", "voxlink.fb.subtitle");
        this.parentScreen = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        int top = Math.max(56, this.height / 2 - 40);
        this.addButton(BTN_SITE, centerX - 100, top, 200, 20, I18n.format("voxlink.fb.site"));
        this.addButton(BTN_GITHUB, centerX - 100, top + 24, 200, 20, I18n.format("voxlink.fb.github"));
        this.addButton(BTN_GITEE, centerX - 100, top + 48, 200, 20, I18n.format("voxlink.fb.gitee"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_SITE) {
            Minecraft.getMinecraft().displayGuiScreen(new FeedbackFormScreen(this, this.parentScreen));
        } else if (button.id == BTN_GITHUB) {
            RelatedLinksScreen.openBrowser(GITHUB_ISSUES_URL);
        } else if (button.id == BTN_GITEE) {
            RelatedLinksScreen.openBrowser(GITEE_ISSUES_URL);
        } else {
            super.actionPerformed(button);
        }
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen) null);
        }
    }
}
