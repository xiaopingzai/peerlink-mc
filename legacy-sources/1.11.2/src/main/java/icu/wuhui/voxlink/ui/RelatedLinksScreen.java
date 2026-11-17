package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import java.awt.Desktop;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * 相关链接屏（从 1.12.2 回灌到 Forge 1.11.2）。
 * 双列外链按钮，点击用系统浏览器打开；AWT Desktop 不可用时按平台走命令行兜底。
 */
public class RelatedLinksScreen extends VoxLinkScreenBase {
    private static final String[][] LINKS = new String[][]{
        {"voxlink.links.site", "https://link.voxdev.top/"},
        {"voxlink.links.mcmod", "https://www.mcmod.cn/class/28295.html"},
        {"voxlink.links.github", "https://github.com/AUGUHDAR/VoxLink"},
        {"voxlink.links.gitee", "https://gitee.com/AUGUHDAR/VoxLink"},
        {"voxlink.links.modrinth", "https://modrinth.com/mod/voxlink"},
        {"voxlink.links.curseforge", "https://www.curseforge.com/minecraft/mc-mods/voxlink"},
        {"voxlink.links.discord", "https://discord.gg/XaAFxvzPDS"},
        {"voxlink.links.qq", "https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi&authKey=v0dYAQniGZypAJuoPZW/7FL0bfoc32h68oIHd9lqGwOvAduzcwsJNR7Mei9/YugW"}
    };

    private final GuiScreen parentScreen;

    public RelatedLinksScreen(GuiScreen guiScreen) {
        super("voxlink.links.title");
        this.parentScreen = guiScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        int top = Math.max(46, this.height / 2 - 58);
        for (int i = 0; i < LINKS.length; ++i) {
            int x = (i % 2 == 0) ? centerX - 100 : centerX + 2;
            int y = top + (i / 2) * 24;
            this.addButton(200 + i, x, y, 98, 20, I18n.format((String) LINKS[i][0]));
        }
    }

    /** 用系统浏览器打开 URL；AWT 不可用时按平台命令行兜底。 */
    static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd;
            if (os.contains("win")) {
                cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
            } else if (os.contains("mac") || os.contains("darwin")) {
                cmd = new String[]{"open", url};
            } else {
                cmd = new String[]{"xdg-open", url};
            }
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[RelatedLinks] Failed to open browser: {}", (Object) e.getMessage());
        }
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        for (Object e : this.buttonList) {
            ((GuiButton) e).drawButton(Minecraft.getMinecraft(), n, n2);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id >= 200 && guiButton.id < 200 + LINKS.length) {
            openBrowser(LINKS[guiButton.id - 200][1]);
        } else {
            super.actionPerformed(guiButton);
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
