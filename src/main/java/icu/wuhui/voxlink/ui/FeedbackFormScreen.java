package icu.wuhui.voxlink.ui;

import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.FeedbackUploader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 站内反馈表单（移植 26.3 FeedbackFormScreen 到 Forge 1.12.2，简化版）。
 * 描述 + 可选附加游戏日志，提交到信令服务器 /feedback/submit（服务端按 IP 限频 10 分钟 1 次）。
 * 说明：1.12.2 无多行编辑框与系统文件选择器，附件选择/拖放暂未移植，本屏仅随描述附带日志文件。
 */
public class FeedbackFormScreen extends VoxLinkScreenBase {
    private static final int FIELD_W = 200;
    private static final int FIELD_H = 20;

    private static final int BTN_LOGS = 2;
    private static final int BTN_SUBMIT = 3;

    private final GuiScreen chooser;
    private final GuiScreen parent;

    private GuiTextField descField;
    private GuiButton logButton;
    private GuiButton submitButton;

    private boolean includeLogs = true;
    private boolean submitting = false;
    private boolean submitted = false;
    /** 限频截止时间戳（毫秒）：被服务端 429 后据此禁用提交按钮并显示剩余时间 */
    private long rateLimitedUntilMs = 0L;

    private String statusMessage = "";
    private int statusColor = VoxLinkColors.GRAY;

    public FeedbackFormScreen(GuiScreen chooser, GuiScreen parent) {
        super("voxlink.fb.form_title");
        this.chooser = chooser;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        this.descField = new GuiTextField(1, this.fontRenderer, centerX - FIELD_W / 2, 38, FIELD_W, FIELD_H);
        this.descField.setMaxStringLength(2000);
        this.descField.setFocused(true);

        this.logButton = new GuiButton(BTN_LOGS, centerX - 100, 66, 200, 20, logLabel());
        this.buttonList.add(this.logButton);

        this.submitButton = new GuiButton(BTN_SUBMIT, centerX - 100, this.height - 46, 200, 20,
            I18n.format("voxlink.fb.submit"));
        this.submitButton.enabled = !this.submitting;
        this.buttonList.add(this.submitButton);
    }

    private String logLabel() {
        return I18n.format("voxlink.fb.include_logs",
            I18n.format(this.includeLogs ? "voxlink.fb.on" : "voxlink.fb.off"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        super.drawScreen(mouseX, mouseY, partial);
        int centerX = this.width / 2;

        this.fontRenderer.drawString(I18n.format("voxlink.fb.desc_hint"),
            centerX - FIELD_W / 2, 26, VoxLinkColors.MUTED);
        this.descField.drawTextBox();

        // 写作引导（固定三行 + 正文行）
        int y = 96;
        this.fontRenderer.drawString(I18n.format("voxlink.fb.guide_title"),
            centerX - this.fontRenderer.getStringWidth(I18n.format("voxlink.fb.guide_title")) / 2,
            y, VoxLinkColors.WARNING);
        y += 11;
        this.fontRenderer.drawString(I18n.format("voxlink.fb.guide_sug"),
            centerX - this.fontRenderer.getStringWidth(I18n.format("voxlink.fb.guide_sug")) / 2,
            y, VoxLinkColors.GRAY);
        y += 11;
        this.fontRenderer.drawString(I18n.format("voxlink.fb.guide_bug"),
            centerX - this.fontRenderer.getStringWidth(I18n.format("voxlink.fb.guide_bug")) / 2,
            y, VoxLinkColors.GRAY);
        y += 11;
        this.fontRenderer.drawString(I18n.format("voxlink.fb.tips_body"),
            centerX - this.fontRenderer.getStringWidth(I18n.format("voxlink.fb.tips_body")) / 2,
            y, VoxLinkColors.GRAY);

        this.drawStatus(centerX);
    }

    private void drawStatus(int centerX) {
        int y = this.height - 66;

        if (this.submitted) {
            this.drawCentered(I18n.format("voxlink.fb.success"), centerX, y, VoxLinkColors.SUCCESS);
            y += 10;
            this.drawCentered(I18n.format("voxlink.fb.rate_note"), centerX, y, VoxLinkColors.MUTED);
            return;
        }

        if (this.rateLimitedUntilMs > 0L) {
            long leftMs = this.rateLimitedUntilMs - System.currentTimeMillis();
            if (leftMs <= 0L) {
                this.rateLimitedUntilMs = 0L;
            } else {
                long totalSec = (leftMs + 999L) / 1000L;
                this.drawCentered(I18n.format("voxlink.fb.rate_wait", totalSec / 60L, totalSec % 60L),
                    centerX, y, VoxLinkColors.WARNING);
                y += 10;
                if (this.submitButton != null) {
                    this.submitButton.enabled = false;
                }
                return;
            }
        }

        List<Path> logs = this.includeLogs ? FeedbackUploader.defaultLogFiles(gameDir()) : null;
        long[] total = new long[]{0L};
        List<Path> files = FeedbackUploader.mergeFiles(null, logs, total);
        if (!files.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Path p : files) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(p.getFileName());
            }
            this.drawCentered(names.toString(), centerX, y, VoxLinkColors.INFO);
            y += 10;
            if (total[0] > FeedbackUploader.MAX_TOTAL_BYTES) {
                this.drawCentered(I18n.format("voxlink.fb.too_large"), centerX, y, VoxLinkColors.ERROR);
                if (this.submitButton != null) {
                    this.submitButton.enabled = false;
                }
            }
        }

        if (!this.statusMessage.isEmpty()) {
            this.drawCentered(this.statusMessage, centerX, this.height - 66, this.statusColor);
        } else if (this.submitting) {
            this.drawCentered(I18n.format("voxlink.fb.submitting"), centerX, this.height - 66,
                VoxLinkColors.MUTED);
        }
    }

    private void drawCentered(String text, int centerX, int y, int color) {
        this.fontRenderer.drawString(text, centerX - this.fontRenderer.getStringWidth(text) / 2, y, color);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (this.descField.textboxKeyTyped(c, key)) {
            return;
        }
        if (key == 28) {
            this.submitFeedback();
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
        boolean focused = x >= this.descField.x && x < this.descField.x + this.descField.getWidth()
            && y >= this.descField.y && y < this.descField.y + 12;
        this.descField.setFocused(focused);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_LOGS) {
            this.includeLogs = !this.includeLogs;
            this.logButton.displayString = logLabel();
        } else if (button.id == BTN_SUBMIT) {
            this.submitFeedback();
        } else {
            super.actionPerformed(button);
        }
    }

    private void submitFeedback() {
        if (this.submitting || this.submitted) {
            return;
        }
        if (System.currentTimeMillis() < this.rateLimitedUntilMs) {
            this.statusMessage = "";
            return;
        }

        String desc = this.descField.getText().trim();
        List<Path> logs = this.includeLogs ? FeedbackUploader.defaultLogFiles(gameDir()) : null;
        long[] total = new long[]{0L};
        List<Path> files = FeedbackUploader.mergeFiles(null, logs, total);
        if (desc.isEmpty() && files.isEmpty()) {
            setStatusKey("voxlink.fb.err_empty", VoxLinkColors.ERROR);
            return;
        }
        if (total[0] > FeedbackUploader.MAX_TOTAL_BYTES) {
            setStatusKey("voxlink.fb.too_large", VoxLinkColors.ERROR);
            return;
        }

        this.submitting = true;
        this.submitButton.enabled = false;
        this.statusMessage = "";

        String url = VoxLinkMod.getConfig().getServerUrl();
        FeedbackUploader.submit(url, desc, buildClientInfo(), null, logs, result ->
            Minecraft.getMinecraft().addScheduledTask(() -> {
                this.submitting = false;
                if (result.success) {
                    this.submitted = true;
                } else if ("RATE_LIMITED".equals(result.errorCode)) {
                    int retry = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
                    this.rateLimitedUntilMs = System.currentTimeMillis() + retry * 1000L;
                    this.statusMessage = "";
                } else if ("FEEDBACK_TOO_LARGE".equals(result.errorCode)
                    || "TICKET_TOO_LARGE".equals(result.errorCode)) {
                    setStatusKey("voxlink.fb.too_large", VoxLinkColors.ERROR);
                } else {
                    this.statusMessage = I18n.format("voxlink.fb.err_failed") + " (" + result.errorCode + ")";
                    this.statusColor = VoxLinkColors.ERROR;
                }
            }));
    }

    private void setStatusKey(String key, int color) {
        this.statusMessage = I18n.format(key);
        this.statusColor = color;
    }

    private static String buildClientInfo() {
        try {
            JsonObject info = new JsonObject();
            info.addProperty("gameVersion", "1.12.2");
            info.addProperty("loader", "Forge");
            info.addProperty("modVersion", VoxLinkMod.MOD_VERSION);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getSession() != null && mc.getSession().getUsername() != null) {
                info.addProperty("nickname", mc.getSession().getUsername());
            }
            return info.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Path gameDir() {
        return Minecraft.getMinecraft().gameDir.toPath();
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parent != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parent);
        } else if (this.chooser != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.chooser);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen) null);
        }
    }

    @Override
    public void updateScreen() {
        if (this.descField != null) {
            this.descField.updateCursorCounter();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
