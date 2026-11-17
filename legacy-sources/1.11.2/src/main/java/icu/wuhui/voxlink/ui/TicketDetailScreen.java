package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.TicketClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 工单详情（移植 26.3 TicketDetailScreen 到 Forge 1.12.2，简化版）。
 * 描述 + 消息时间线（分页）+ 追问（文字）+ 软删 + 撤回自己上一条。
 * 打开即上报已读；管理员回复为纯文字。附件选择沿用反馈表单口径，本轮不接系统文件选择器，
 * 服务端返回的附件名仅做只读展示。
 */
public class TicketDetailScreen extends VoxLinkScreenBase {
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private static final int PAGE_SIZE = 5;
    private static final int MSG_LINE_W = 226;

    private static final int BTN_SEND = 300;
    private static final int BTN_RETRACT = 301;
    private static final int BTN_DELETE = 302;

    private final GuiScreen listScreen;
    private final GuiScreen parentScreen;
    private final String ticketId;

    private TicketClient.Detail detail;
    private boolean loadFailed = false;
    private int page = 0;

    private GuiTextField replyBox;
    private GuiButton sendButton;
    private GuiButton retractButton;

    private boolean sending = false;
    private boolean retracting = false;
    private long rateLimitedUntilMs = 0L;
    private String statusMessage = "";
    private int statusColor = VoxLinkColors.GRAY;

    public TicketDetailScreen(GuiScreen listScreen, GuiScreen parent, String ticketId) {
        super("#" + ticketId);
        this.listScreen = listScreen;
        this.parentScreen = parent;
        this.ticketId = ticketId;
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        int inputY = this.height - 72;
        this.replyBox = new GuiTextField(1, this.fontRenderer, centerX - 110, inputY, 166, 20);
        this.replyBox.setMaxStringLength(2000);
        this.replyBox.setFocused(true);

        this.sendButton = new GuiButton(BTN_SEND, centerX + 60, inputY, 50, 20,
            I18n.format("voxlink.ticket.send"));
        this.sendButton.enabled = !this.sending && System.currentTimeMillis() >= this.rateLimitedUntilMs;
        this.buttonList.add(this.sendButton);

        int rowY = inputY + 24;
        this.retractButton = new GuiButton(BTN_RETRACT, centerX - 35, rowY, 70, 20,
            I18n.format("voxlink.ticket.retract"));
        this.buttonList.add(this.retractButton);
        this.addButton(BTN_DELETE, centerX - 110, rowY, 70, 20, I18n.format("voxlink.ticket.delete"));
        this.updateRetractButton();

        if (this.detail == null && !this.loadFailed) {
            this.refresh();
        }
    }

    /** 自己发的最后一条（有服务端 id 的）才是可撤的那条。 */
    private String lastOwnMessageId() {
        if (this.detail == null) {
            return null;
        }
        for (int i = this.detail.messages.size() - 1; i >= 0; --i) {
            TicketClient.Msg m = this.detail.messages.get(i);
            if (m != null && !"admin".equals(m.from) && m.id != null && !m.id.isEmpty()) {
                return m.id;
            }
        }
        return null;
    }

    private void updateRetractButton() {
        if (this.retractButton != null) {
            this.retractButton.enabled = !this.retracting && this.lastOwnMessageId() != null;
        }
    }

    private void retractLastOwn() {
        String msgId = this.lastOwnMessageId();
        if (msgId == null || this.retracting) {
            return;
        }
        this.retracting = true;
        this.updateRetractButton();
        this.statusMessage = "";
        String url = VoxLinkMod.getConfig().getServerUrl();
        TicketClient.retract(url, this.ticketId, msgId, result -> Minecraft.getMinecraft().addScheduledTask(() -> {
            this.retracting = false;
            if (result.success) {
                setStatus("voxlink.ticket.retracted", VoxLinkColors.SUCCESS);
                this.detail = null; // 条数变了，本地那份不能再信，重新拉。
                this.refresh();
            } else {
                this.statusMessage = I18n.format("voxlink.ticket.err_failed") + " (" + result.errorCode + ")";
                this.statusColor = VoxLinkColors.ERROR;
                this.updateRetractButton();
            }
        }));
    }

    private void refresh() {
        String url = VoxLinkMod.getConfig().getServerUrl();
        TicketClient.fetchDetail(url, this.ticketId, d -> Minecraft.getMinecraft().addScheduledTask(() -> {
            if (d == null) {
                this.loadFailed = true;
            } else {
                this.detail = d;
                this.loadFailed = false;
            }
            if (this.detail != null) {
                TicketClient.markViewed(url, this.ticketId);
            }
            this.updateRetractButton();
        }));
    }

    private void sendReply() {
        if (this.sending || System.currentTimeMillis() < this.rateLimitedUntilMs) {
            return;
        }
        String text = this.replyBox.getText().trim();
        if (text.isEmpty()) {
            setStatus("voxlink.ticket.err_empty", VoxLinkColors.ERROR);
            return;
        }
        this.sending = true;
        this.sendButton.enabled = false;
        this.statusMessage = "";
        String url = VoxLinkMod.getConfig().getServerUrl();
        TicketClient.reply(url, this.ticketId, text, new ArrayList<java.nio.file.Path>(), result ->
            Minecraft.getMinecraft().addScheduledTask(() -> {
                this.sending = false;
                if (result.success) {
                    this.replyBox.setText("");
                    this.statusMessage = "";
                    this.refresh();
                } else if ("RATE_LIMITED".equals(result.errorCode)) {
                    int ra = result.retryAfterSec > 0 ? result.retryAfterSec : 600;
                    this.rateLimitedUntilMs = System.currentTimeMillis() + ra * 1000L;
                    setStatus("voxlink.ticket.rate_limited", VoxLinkColors.WARNING);
                } else {
                    this.statusMessage = I18n.format("voxlink.ticket.err_failed") + " (" + result.errorCode + ")";
                    this.statusColor = VoxLinkColors.ERROR;
                }
                if (this.sendButton != null) {
                    this.sendButton.enabled = System.currentTimeMillis() >= this.rateLimitedUntilMs;
                }
            }));
    }

    private void deleteTicket() {
        TicketClient.deleteLocal(VoxLinkMod.getConfig().getServerUrl(), this.ticketId);
        Minecraft.getMinecraft().displayGuiScreen(this.listScreen);
    }

    private void setStatus(String key, int color) {
        this.statusMessage = I18n.format(key);
        this.statusColor = color;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        super.drawScreen(mouseX, mouseY, partial);
        int centerX = this.width / 2;

        if (this.detail == null) {
            this.drawCentered(I18n.format(this.loadFailed ? "voxlink.ticket.load_failed" : "voxlink.ticket.loading"),
                centerX, this.height / 2 - 10, this.loadFailed ? VoxLinkColors.ERROR : VoxLinkColors.MUTED);
            return;
        }

        TicketClient.Detail d = this.detail;
        String status = TIME_FMT.format(new Date(d.timeMs))
            + (d.deleted ? "  " + I18n.format("voxlink.ticket.tag_deleted") : "");
        this.drawCentered(status, centerX, 27, VoxLinkColors.GRAY);

        // 时间线：首条为工单描述，其后为消息。
        List<String[]> timeline = new ArrayList<String[]>();
        timeline.add(new String[]{"me", d.description, attachNames(d.attachments)});
        for (TicketClient.Msg m : d.messages) {
            timeline.add(new String[]{"admin".equals(m.from) ? "admin" : "me", m.text, attachNames(m.attachments)});
        }
        int totalPages = Math.max(1, (timeline.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (this.page >= totalPages) {
            this.page = totalPages - 1;
        }
        if (this.page < 0) {
            this.page = 0;
        }

        int y = 42;
        int start = this.page * PAGE_SIZE;
        int end = Math.min(timeline.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            String[] entry = timeline.get(i);
            boolean isAdmin = "admin".equals(entry[0]);
            String head = isAdmin
                ? I18n.format("voxlink.ticket.from_admin")
                : I18n.format("voxlink.ticket.from_me");
            this.fontRenderer.drawString(head, centerX - MSG_LINE_W / 2, y,
                isAdmin ? VoxLinkColors.WARNING : VoxLinkColors.SUCCESS);
            y += 11;
            for (String line : wrapLines(entry[1], MSG_LINE_W, 2)) {
                this.fontRenderer.drawString(line, centerX - MSG_LINE_W / 2, y, VoxLinkColors.WHITE);
                y += 11;
            }
            if (!entry[2].isEmpty()) {
                this.fontRenderer.drawString(entry[2], centerX - MSG_LINE_W / 2, y, VoxLinkColors.GRAY);
                y += 11;
            }
            y += 4;
        }

        if (totalPages > 1) {
            this.drawCentered(I18n.format("voxlink.ticket.page", this.page + 1, totalPages),
                centerX, this.height - 92, VoxLinkColors.GRAY);
        }
        if (!this.statusMessage.isEmpty()) {
            this.drawCentered(this.statusMessage, centerX, this.height - 50, this.statusColor);
        }
    }

    private static String attachNames(List<TicketClient.Attach> atts) {
        if (atts == null || atts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(I18n.format("voxlink.ticket.attach_prefix"));
        for (int i = 0; i < atts.size() && i < 4; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(atts.get(i).name);
        }
        if (atts.size() > 4) {
            sb.append(" +").append(atts.size() - 4);
        }
        return sb.toString();
    }

    /** 按像素宽度换行，最多 maxLines 行（末行截断）。 */
    private List<String> wrapLines(String text, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<String>();
        String remain = text == null ? "" : text;
        while (!remain.isEmpty() && out.size() < maxLines) {
            int take = remain.length();
            while (take > 1 && this.fontRenderer.getStringWidth(remain.substring(0, take)) > maxWidth) {
                take--;
            }
            boolean last = out.size() == maxLines - 1;
            if (last && take < remain.length()) {
                String clipped = remain.substring(0, take);
                while (take > 1 && this.fontRenderer.getStringWidth(clipped + "…") > maxWidth) {
                    take--;
                    clipped = remain.substring(0, take);
                }
                out.add(clipped + "…");
                return out;
            }
            out.add(remain.substring(0, take));
            remain = remain.substring(take);
        }
        return out;
    }

    private void drawCentered(String text, int centerX, int y, int color) {
        this.fontRenderer.drawString(text, centerX - this.fontRenderer.getStringWidth(text) / 2, y, color);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_SEND) {
            this.sendReply();
        } else if (button.id == BTN_RETRACT) {
            this.retractLastOwn();
        } else if (button.id == BTN_DELETE) {
            this.deleteTicket();
        } else {
            super.actionPerformed(button);
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (this.replyBox != null && this.replyBox.textboxKeyTyped(c, key)) {
            return;
        }
        if (key == 28) {
            this.sendReply();
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
        if (this.replyBox != null) {
            boolean focused = x >= this.replyBox.x && x < this.replyBox.x + this.replyBox.getWidth()
                && y >= this.replyBox.y && y < this.replyBox.y + 12;
            this.replyBox.setFocused(focused);
        }
    }

    @Override
    public void updateScreen() {
        if (this.replyBox != null) {
            this.replyBox.updateCursorCounter();
        }
    }

    @Override
    protected void actionPerformedBack() {
        Minecraft.getMinecraft().displayGuiScreen(this.listScreen);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
