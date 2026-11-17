package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.network.TicketClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 我的工单列表（移植 26.3 TicketListScreen 到 Forge 1.12.2）。
 * 本地存单号的历史工单（新→旧），带状态标签与分页；点击进入工单详情（追问/查看回复）。
 */
public class TicketListScreen extends VoxLinkScreenBase {
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private static final int PAGE_SIZE = 6;

    private static final int BTN_PREV = 100;
    private static final int BTN_NEXT = 101;
    private static final int BTN_ROW_BASE = 200;

    private final GuiScreen parentScreen;
    private List<TicketClient.LocalTicket> rows = new ArrayList<TicketClient.LocalTicket>();
    private int page = 0;

    public TicketListScreen(GuiScreen parent) {
        super("voxlink.ticket.list_title");
        this.parentScreen = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.rows = TicketClient.snapshot();
        // 玩家在客户端删除只表示"本地不再关心"，删掉后就不该还占一行。
        List<TicketClient.LocalTicket> kept = new ArrayList<TicketClient.LocalTicket>();
        for (TicketClient.LocalTicket t : this.rows) {
            if (!t.deleted) {
                kept.add(t);
            }
        }
        this.rows = kept;
        this.rebuild();
    }

    private void rebuild() {
        // 保留基类的返回按钮，重建列表与翻页按钮。
        List<GuiButton> keep = new ArrayList<GuiButton>();
        for (GuiButton b : this.buttonList) {
            if (b.id == BACK_BTN_ID) {
                keep.add(b);
            }
        }
        this.buttonList.clear();
        this.buttonList.addAll(keep);

        int centerX = this.width / 2;
        int totalPages = Math.max(1, (this.rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (this.page >= totalPages) {
            this.page = totalPages - 1;
        }
        if (this.page < 0) {
            this.page = 0;
        }

        int listTop = this.height / 2 - 78;
        int start = this.page * PAGE_SIZE;
        int end = Math.min(this.rows.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            TicketClient.LocalTicket t = this.rows.get(i);
            int y = listTop + (i - start) * 24;
            this.addButton(BTN_ROW_BASE + i, centerX - 110, y, 220, 20, rowLabel(t));
        }

        int navY = listTop + PAGE_SIZE * 24 + 4;
        if (totalPages > 1) {
            this.addButton(BTN_PREV, centerX - 110, navY, 106, 20, I18n.format("voxlink.ticket.prev_page"));
            this.addButton(BTN_NEXT, centerX + 4, navY, 106, 20, I18n.format("voxlink.ticket.next_page"));
        }
    }

    private String rowLabel(TicketClient.LocalTicket t) {
        long ts = t.lastTimeMs != 0 ? t.lastTimeMs : t.timeMs;
        String time = TIME_FMT.format(new Date(ts));
        String tag;
        if (t.deleted) {
            tag = I18n.format("voxlink.ticket.tag_deleted");
        } else if (t.hasUnread) {
            tag = I18n.format("voxlink.ticket.tag_unread");
        } else if (t.replyCount > 0) {
            tag = I18n.format("voxlink.ticket.tag_viewed");
        } else {
            tag = I18n.format("voxlink.ticket.tag_waiting");
        }
        return "#" + t.id + "  " + tag + "  " + time;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_PREV) {
            if (this.page > 0) {
                this.page--;
                this.rebuild();
            }
        } else if (button.id == BTN_NEXT) {
            int totalPages = Math.max(1, (this.rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (this.page < totalPages - 1) {
                this.page++;
                this.rebuild();
            }
        } else if (button.id >= BTN_ROW_BASE) {
            int idx = button.id - BTN_ROW_BASE;
            if (idx >= 0 && idx < this.rows.size()) {
                TicketClient.LocalTicket t = this.rows.get(idx);
                Minecraft.getMinecraft().displayGuiScreen(new TicketDetailScreen(this, this.parentScreen, t.id));
            }
        } else {
            super.actionPerformed(button);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        super.drawScreen(mouseX, mouseY, partial);
        int centerX = this.width / 2;
        if (this.rows.isEmpty()) {
            this.drawCentered(I18n.format("voxlink.ticket.empty"), centerX, this.height / 2 - 10, VoxLinkColors.MUTED);
        } else if (this.rows.size() > 1) {
            this.drawCentered(I18n.format("voxlink.ticket.count", this.rows.size()), centerX, 28, VoxLinkColors.GRAY);
        }
    }

    private void drawCentered(String text, int centerX, int y, int color) {
        this.fontRenderer.drawString(text, centerX - this.fontRenderer.getStringWidth(text) / 2, y, color);
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
