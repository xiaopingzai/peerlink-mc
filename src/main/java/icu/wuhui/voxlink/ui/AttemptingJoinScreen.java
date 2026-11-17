/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.resources.I18n
 */
package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.modsync.ModSyncGuestService;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public class AttemptingJoinScreen
extends VoxLinkScreenBase {
    private final GuiScreen parentScreen;
    private final RoomInfo roomInfo;
    private final List<String> logLines = new ArrayList<String>();
    private final List<Integer> logLevels = new ArrayList<Integer>();
    private long logVersion = -1L;
    private int tickCounter = 0;
    private volatile boolean roomLost = false;

    public AttemptingJoinScreen(GuiScreen guiScreen, RoomInfo roomInfo) {
        super("voxlink.attempting_join.title");
        this.parentScreen = guiScreen;
        this.roomInfo = roomInfo;
        // 新一次加入会话：清空上一轮日志，门控提示随后在 initGui 播报。
        UiLogBus.reset();
    }

    public void onRoomLost() {
        this.roomLost = true;
    }

    @Override
    public void initGui() {
        super.initGui();
        UiLogBus.attach();
        ModSyncGuestService.pushPendingGateNote();
        this.addButton(1, this.width / 2 - 100, this.height - 60, 200, 20, I18n.format((String)"voxlink.gui.cancel", (Object[])new Object[0]));
    }

    public void updateScreen() {
        super.updateScreen();
        ++this.tickCounter;
        Object var1_1 = null;
        if (ConnectionHelper.isMcTrulyConnected()) {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        } else if ((this.roomLost || ConnectionHelper.isConnectionRejected()) && this.roomLost && this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        }
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        int n3 = this.width / 2;
        int n4 = this.height / 2 - 20;
        if (this.roomLost) {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.attempting_join.room_lost", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.attempting_join.room_lost", (Object[])new Object[0]))) / 2, n4, -43691);
        } else {
            String string;
            String string2 = I18n.format((String)"voxlink.attempting_join.joining", (Object[])new Object[]{this.roomInfo != null ? this.roomInfo.getCode() : ""});
            this.fontRenderer.drawString(string2, (this.width - this.fontRenderer.getStringWidth(string2)) / 2, n4, -1);
            String string3 = string = this.roomInfo != null ? this.roomInfo.getConnectionMode() : null;
            if (string != null && !string.isEmpty()) {
                this.fontRenderer.drawString(string, (this.width - this.fontRenderer.getStringWidth(string)) / 2, n4 + 22, -5592406);
            }
            String latest = UiLogBus.latestMessage();
            if (latest != null && !latest.isEmpty()) {
                this.fontRenderer.drawString(latest, (this.width - this.fontRenderer.getStringWidth(latest)) / 2, n4 + 34, VoxLinkColors.MUTED);
            }
        }
        this.drawLogPanel();
    }

    private void refreshLogSnapshot() {
        long ver = UiLogBus.version();
        if (ver != this.logVersion) {
            UiLogBus.snapshot(this.logLines, this.logLevels);
            this.logVersion = ver;
        }
    }

    private void drawLogPanel() {
        this.refreshLogSnapshot();
        if (this.logLines.isEmpty()) {
            return;
        }
        int panelW = 120;
        int panelX = this.width - panelW - 6;
        int panelY = 40;
        int lineH = 10;
        int maxLines = Math.max(1, (this.height - panelY - 50) / lineH);
        int shown = Math.min(this.logLines.size(), maxLines);
        int panelH = shown * lineH + 6;
        this.drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        int start = this.logLines.size() - shown;
        for (int i = 0; i < shown; i++) {
            String line = this.logLines.get(start + i);
            int level = this.logLevels.size() > start + i ? this.logLevels.get(start + i) : 0;
            this.fontRenderer.drawString(
                this.fontRenderer.trimStringToWidth(line, panelW - 8),
                panelX + 4, panelY + 3 + i * lineH, this.logColor(level));
        }
    }

    private int logColor(int level) {
        switch (level) {
            case 1: return VoxLinkColors.SUCCESS;
            case 2: return VoxLinkColors.WARNING;
            case 3: return VoxLinkColors.ERROR;
            default: return VoxLinkColors.MUTED;
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 1) {
            VoxLinkMod.getRoomManager().leaveRoom();
            if (this.parentScreen != null) {
                Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
            } else {
                Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
            }
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void actionPerformedBack() {
        VoxLinkMod.getRoomManager().leaveRoom();
        if (this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        }
    }
}

