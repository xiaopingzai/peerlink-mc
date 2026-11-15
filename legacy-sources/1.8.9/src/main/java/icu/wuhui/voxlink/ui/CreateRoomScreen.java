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
    public void func_73866_w_() {
        super.func_73866_w_();
        int n = this.field_146294_l / 2;
        int n2 = this.field_146295_m / 2 - 60;
        this.nameField = new GuiTextField(1, this.field_146289_q, n - 100, n2, 200, 20);
        this.nameField.func_146203_f(32);
        this.passwordField = new GuiTextField(2, this.field_146289_q, n - 100, n2 + 28, 200, 20);
        this.passwordField.func_146203_f(32);
        this.maxPlayersField = new GuiTextField(3, this.field_146289_q, n - 100, n2 + 56, 200, 20);
        this.maxPlayersField.func_146203_f(2);
        this.maxPlayersField.func_146180_a("8");
        this.addButton(50, n - 100, n2 + 84, 200, 20, I18n.func_135052_a((String)"voxlink.gui.category", (Object[])new Object[0]) + ": " + I18n.func_135052_a((String)CAT_KEYS[this.catIdx], (Object[])new Object[0]));
        this.addButton(2, n - 100, n2 + 110, 200, 20, I18n.func_135052_a((String)"voxlink.gui.create", (Object[])new Object[0]));
    }

    @Override
    public void func_73863_a(int n, int n2, float f) {
        super.func_73863_a(n, n2, f);
        int n3 = this.field_146294_l / 2;
        int n4 = this.field_146295_m / 2 - 84;
        this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.gui.room_name", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.gui.room_name", (Object[])new Object[0]))) / 2, n4, -5592406);
        this.nameField.func_146194_f();
        this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.gui.password_optional", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.gui.password_optional", (Object[])new Object[0]))) / 2, n4 + 40, -5592406);
        this.passwordField.func_146194_f();
        this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.gui.max_players", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.gui.max_players", (Object[])new Object[0]))) / 2, n4 + 68, -5592406);
        this.maxPlayersField.func_146194_f();
        if (this.creating) {
            this.field_146289_q.func_78276_b(I18n.func_135052_a((String)"voxlink.creating.creating", (Object[])new Object[0]), (this.field_146294_l - this.field_146289_q.func_78256_a(I18n.func_135052_a((String)"voxlink.creating.creating", (Object[])new Object[0]))) / 2, n4 + 110, -5592406);
        }
    }

    protected void func_73869_a(char c, int n) {
        if (this.nameField.func_146201_a(c, n) || this.passwordField.func_146201_a(c, n) || this.maxPlayersField.func_146201_a(c, n)) {
            return;
        }
        super.func_73869_a(c, n);
    }

    protected void func_73864_a(int n, int n2, int n3) {
        super.func_73864_a(n, n2, n3);
        CreateRoomScreen.handleFieldClick(this.nameField, n, n2);
        CreateRoomScreen.handleFieldClick(this.passwordField, n, n2);
        CreateRoomScreen.handleFieldClick(this.maxPlayersField, n, n2);
    }

    private static void handleFieldClick(GuiTextField guiTextField, int n, int n2) {
        boolean bl = n >= guiTextField.field_146209_f && n < guiTextField.field_146209_f + guiTextField.func_146200_o() && n2 >= guiTextField.field_146210_g && n2 < guiTextField.field_146210_g + 12;
        guiTextField.func_146195_b(bl);
    }

    @Override
    protected void func_146284_a(GuiButton guiButton) {
        if (guiButton.field_146127_k == 2) {
            this.onCreate();
        } else if (guiButton.field_146127_k == 50) {
            this.catIdx = (this.catIdx + 1) % CATS.length;
            guiButton.field_146126_j = I18n.func_135052_a((String)"voxlink.gui.category", (Object[])new Object[0]) + ": " + I18n.func_135052_a((String)CAT_KEYS[this.catIdx], (Object[])new Object[0]);
        } else {
            super.func_146284_a(guiButton);
        }
    }

    private void onCreate() {
        Object object;
        int n;
        if (this.creating) {
            return;
        }
        String string = this.nameField.func_146179_b().trim();
        if (string.isEmpty()) {
            string = Minecraft.func_71410_x().func_110432_I().func_111285_a() + "'s Room";
        }
        try {
            n = Integer.parseInt(this.maxPlayersField.func_146179_b().trim());
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
            VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.error.not_initialized", (Object[])new Object[0]));
            this.creating = false;
            return;
        }
        IntegratedServer integratedServer = Minecraft.func_71410_x().func_71401_C();
        if (integratedServer == null || !Minecraft.func_71410_x().func_71387_A()) {
            VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.gui.please_singleplayer", (Object[])new Object[0]));
            this.creating = false;
            return;
        }
        int n2 = 25565;
        try {
            object = integratedServer.getClass().getMethod("func_71206_a", GameType.class, Boolean.TYPE);
            String string2 = (String)((Method)object).invoke(integratedServer, GameType.SURVIVAL, true);
            if (string2 != null) {
                n2 = Integer.parseInt(string2.trim());
            }
        }
        catch (Exception exception) {
            VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.gui.share_lan_failed", (Object[])new Object[]{exception.getMessage()}));
            try {
                Method method = MinecraftServer.class.getMethod("func_71215_F", new Class[0]);
                n2 = (Integer)method.invoke(integratedServer, new Object[0]);
            }
            catch (Exception exception2) {
                // empty catch block
            }
        }
        VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.gui.mc_port", (Object[])new Object[]{n2}));
        object = this.passwordField.func_146179_b();
        roomManager.createRoom(string, (String)object, n, n2, true, CATS[this.catIdx], "survival").whenComplete((roomInfo, throwable) -> {
            Minecraft minecraft = Minecraft.func_71410_x();
            minecraft.func_152344_a(() -> {
                if (throwable != null) {
                    this.creating = false;
                    VoxLinkChat.print(I18n.func_135052_a((String)"voxlink.create.failed", (Object[])new Object[]{throwable.getMessage()}));
                    return;
                }
                minecraft.func_147108_a((GuiScreen)new CreatingRoomScreen(this, (RoomInfo)roomInfo));
            });
        });
    }

    @Override
    protected void actionPerformedBack() {
        if (this.parentScreen != null) {
            Minecraft.func_71410_x().func_147108_a(this.parentScreen);
        } else {
            Minecraft.func_71410_x().func_147108_a((GuiScreen)null);
        }
    }

    public void func_73876_c() {
        this.nameField.func_146178_a();
        this.passwordField.func_146178_a();
        this.maxPlayersField.func_146178_a();
    }
}

