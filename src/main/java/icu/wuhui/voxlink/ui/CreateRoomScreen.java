/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.gui.GuiTextField
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.server.integrated.IntegratedServer
 *  net.minecraft.world.GameType
 */
package icu.wuhui.voxlink.ui;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.CreatingRoomScreen;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameType;

public class CreateRoomScreen
extends VoxLinkScreenBase {
    private static final int CREATE_BTN = 2;
    private static final int CATEGORY_BTN = 50;
    private static final String[] CATS = new String[]{"survival", "creative", "pvp", "rpg", "redstone", "minigame", "social", "other"};
    private static final String[] CAT_KEYS = new String[]{"voxlink.gui.cat.survival", "voxlink.gui.cat.creative", "voxlink.gui.cat.pvp", "voxlink.gui.cat.rpg", "voxlink.gui.cat.redstone", "voxlink.gui.cat.minigame", "voxlink.gui.cat.social", "voxlink.gui.cat.other"};
    private int catIdx = 0;
    private final GuiScreen parentScreen;
    private GuiTextField nameField;
    private GuiTextField passwordField;
    private GuiTextField maxPlayersField;
    private boolean creating = false;

    public CreateRoomScreen(GuiScreen guiScreen) {
        super("voxlink.gui.create_room");
        this.parentScreen = guiScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        int n = this.width / 2;
        int n2 = this.height / 2 - 60;
        this.nameField = new GuiTextField(1, this.fontRenderer, n - 100, n2, 200, 20);
        this.nameField.setMaxStringLength(32);
        this.passwordField = new GuiTextField(2, this.fontRenderer, n - 100, n2 + 28, 200, 20);
        this.passwordField.setMaxStringLength(32);
        this.maxPlayersField = new GuiTextField(3, this.fontRenderer, n - 100, n2 + 56, 200, 20);
        this.maxPlayersField.setMaxStringLength(2);
        this.maxPlayersField.setText("8");
        this.addButton(50, n - 100, n2 + 84, 200, 20, I18n.format((String)"voxlink.gui.category", (Object[])new Object[0]) + ": " + I18n.format((String)CAT_KEYS[this.catIdx], (Object[])new Object[0]));
        this.addButton(2, n - 100, n2 + 110, 200, 20, I18n.format((String)"voxlink.gui.create", (Object[])new Object[0]));
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        super.drawScreen(n, n2, f);
        int n3 = this.width / 2;
        int n4 = this.height / 2 - 84;
        this.fontRenderer.drawString(I18n.format((String)"voxlink.gui.room_name", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.gui.room_name", (Object[])new Object[0]))) / 2, n4, -5592406);
        this.nameField.drawTextBox();
        this.fontRenderer.drawString(I18n.format((String)"voxlink.gui.password_optional", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.gui.password_optional", (Object[])new Object[0]))) / 2, n4 + 40, -5592406);
        this.passwordField.drawTextBox();
        this.fontRenderer.drawString(I18n.format((String)"voxlink.gui.max_players", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.gui.max_players", (Object[])new Object[0]))) / 2, n4 + 68, -5592406);
        this.maxPlayersField.drawTextBox();
        if (this.creating) {
            this.fontRenderer.drawString(I18n.format((String)"voxlink.creating.creating", (Object[])new Object[0]), (this.width - this.fontRenderer.getStringWidth(I18n.format((String)"voxlink.creating.creating", (Object[])new Object[0]))) / 2, n4 + 110, -5592406);
        }
    }

    protected void keyTyped(char c, int n) {
        if (this.nameField.textboxKeyTyped(c, n) || this.passwordField.textboxKeyTyped(c, n) || this.maxPlayersField.textboxKeyTyped(c, n)) {
            return;
        }
        super.keyTyped(c, n);
    }

    protected void mouseClicked(int n, int n2, int n3) {
        super.mouseClicked(n, n2, n3);
        CreateRoomScreen.handleFieldClick(this.nameField, n, n2);
        CreateRoomScreen.handleFieldClick(this.passwordField, n, n2);
        CreateRoomScreen.handleFieldClick(this.maxPlayersField, n, n2);
    }

    private static void handleFieldClick(GuiTextField guiTextField, int n, int n2) {
        boolean bl = n >= guiTextField.x && n < guiTextField.x + guiTextField.getWidth() && n2 >= guiTextField.y && n2 < guiTextField.y + 12;
        guiTextField.setFocused(bl);
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        if (guiButton.id == 2) {
            this.onCreate();
        } else if (guiButton.id == 50) {
            this.catIdx = (this.catIdx + 1) % CATS.length;
            guiButton.displayString = I18n.format((String)"voxlink.gui.category", (Object[])new Object[0]) + ": " + I18n.format((String)CAT_KEYS[this.catIdx], (Object[])new Object[0]);
        } else {
            super.actionPerformed(guiButton);
        }
    }

    private void onCreate() {
        Object object;
        int n;
        if (this.creating) {
            return;
        }
        String string = this.nameField.getText().trim();
        if (string.isEmpty()) {
            string = Minecraft.getMinecraft().getSession().getUsername() + "'s Room";
        }
        try {
            n = Integer.parseInt(this.maxPlayersField.getText().trim());
        }
        catch (NumberFormatException numberFormatException) {
            n = 8;
        }
        if (n < 1) {
            n = 1;
        }
        if (n > 99) {
            n = 99;
        }
        this.creating = true;
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (roomManager == null) {
            VoxLinkChat.print(I18n.format((String)"voxlink.error.not_initialized", (Object[])new Object[0]));
            this.creating = false;
            return;
        }
        IntegratedServer integratedServer = Minecraft.getMinecraft().getIntegratedServer();
        if (integratedServer == null || !Minecraft.getMinecraft().isIntegratedServerRunning()) {
            VoxLinkChat.print(I18n.format((String)"voxlink.gui.please_singleplayer", (Object[])new Object[0]));
            this.creating = false;
            return;
        }
        int n2 = 25565;
        try {
            object = integratedServer.getClass().getMethod("shareToLAN", GameType.class, Boolean.TYPE);
            String string2 = (String)((Method)object).invoke(integratedServer, GameType.SURVIVAL, true);
            if (string2 != null) {
                n2 = Integer.parseInt(string2.trim());
            }
        }
        catch (Exception exception) {
            VoxLinkChat.print(I18n.format((String)"voxlink.gui.share_lan_failed", (Object[])new Object[]{exception.getMessage()}));
            try {
                Method method = MinecraftServer.class.getMethod("getServerPort", new Class[0]);
                n2 = (Integer)method.invoke(integratedServer, new Object[0]);
            }
            catch (Exception exception2) {
                // empty catch block
            }
        }
        VoxLinkChat.print(I18n.format((String)"voxlink.gui.mc_port", (Object[])new Object[]{n2}));
        object = this.passwordField.getText();
        roomManager.createRoom(string, (String)object, n, n2, true, CATS[this.catIdx], "survival").whenComplete((roomInfo, throwable) -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.addScheduledTask(() -> {
                if (throwable != null) {
                    this.creating = false;
                    VoxLinkChat.print(I18n.format((String)"voxlink.create.failed", (Object[])new Object[]{throwable.getMessage()}));
                    return;
                }
                minecraft.displayGuiScreen((GuiScreen)new CreatingRoomScreen(this, (RoomInfo)roomInfo));
            });
        });
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
        } else {
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen)null);
        }
    }

    public void updateScreen() {
        this.nameField.updateCursorCounter();
        this.passwordField.updateCursorCounter();
        this.maxPlayersField.updateCursorCounter();
    }
}

