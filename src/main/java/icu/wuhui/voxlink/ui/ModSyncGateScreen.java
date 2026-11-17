package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.modsync.ModSyncGuestService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * 加入前的模组同步门控选择屏（移植 26.3 AttemptingJoinScreen 的 scope/bypass/checking 交互）。
 * 玩家在此选择：仅必装检查 / 全部模组 / 跳过检查直接进入 / 取消。
 * 检查中禁用选择按钮并显示拉取文案；放行/取消由 GuestService 回调回主线程。
 */
public class ModSyncGateScreen
extends VoxLinkScreenBase {
    private static final int REQUIRED_BTN = 10;
    private static final int ALL_BTN = 11;
    private static final int SKIP_BTN = 12;
    private static final int CANCEL_BTN = 13;

    private final JoinRoomScreen parentScreen;
    private final String roomCode;
    private final String password;
    private boolean checking = false;
    private String checkingKey = "voxlink.modsync.checking";

    public ModSyncGateScreen(JoinRoomScreen parentScreen, String roomCode, String password) {
        super("voxlink.gui.title");
        this.parentScreen = parentScreen;
        this.roomCode = roomCode;
        this.password = password;
    }

    @Override
    public void initGui() {
        super.initGui();
        int cx = this.width / 2 - 100;
        int y = this.height / 2 - 30;
        this.addButton(REQUIRED_BTN, cx, y, 200, 20, I18n.format((String)"voxlink.modsync.scope_required", (Object[])new Object[0]));
        this.addButton(ALL_BTN, cx, y + 24, 200, 20, I18n.format((String)"voxlink.modsync.scope_all", (Object[])new Object[0]));
        this.addButton(SKIP_BTN, cx, y + 48, 200, 20, I18n.format((String)"voxlink.modsync.skip_check", (Object[])new Object[0]));
        this.addButton(CANCEL_BTN, cx, y + 72, 200, 20, I18n.format((String)"voxlink.gui.cancel", (Object[])new Object[0]));
    }

    private void setChoicesEnabled(boolean enabled) {
        for (Object e : this.buttonList) {
            GuiButton b = (GuiButton)e;
            if (b.id == REQUIRED_BTN || b.id == ALL_BTN || b.id == SKIP_BTN) {
                b.enabled = enabled;
            }
        }
    }

    private void pickScope(String scope) {
        if (this.checking) {
            return;
        }
        this.checking = true;
        this.checkingKey = "all".equals(scope) ? "voxlink.modsync.checking_all" : "voxlink.modsync.checking";
        this.setChoicesEnabled(false);
        ModSyncGuestService.gate(this.roomCode, scope,
            () -> Minecraft.getMinecraft().addScheduledTask(this::proceed),
            () -> Minecraft.getMinecraft().addScheduledTask(this::cancelGate));
    }

    private void proceed() {
        if (this.parentScreen != null) {
            this.parentScreen.proceedToJoin(this.roomCode, this.password);
        }
    }

    private void cancelGate() {
        if (this.parentScreen != null) {
            this.parentScreen.cancelGate();
        }
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        if (this.checking) {
            String text = I18n.format((String)this.checkingKey, (Object[])new Object[0]);
            this.fontRenderer.drawString(text, (this.width - this.fontRenderer.getStringWidth(text)) / 2, this.height / 2 - 20, 0xAAAAAA);
        } else {
            String hint = I18n.format((String)"voxlink.modsync.scope_hint", (Object[])new Object[0]);
            this.fontRenderer.drawString(hint, (this.width - this.fontRenderer.getStringWidth(hint)) / 2, this.height / 2 - 48, 0xAAAAAA);
        }
        for (Object e : this.buttonList) {
            ((GuiButton)e).drawButton(Minecraft.getMinecraft(), n, n2, f);
        }
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        int id = guiButton.id;
        if (id == REQUIRED_BTN) {
            this.pickScope("required");
        } else if (id == ALL_BTN) {
            this.pickScope("all");
        } else if (id == SKIP_BTN) {
            if (this.checking) {
                return;
            }
            ModSyncGuestService.bypass(this.roomCode);
            this.proceed();
        } else if (id == CANCEL_BTN) {
            this.cancelGate();
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void actionPerformedBack() {
        this.cancelGate();
    }
}
