/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 */
package icu.wuhui.voxlink.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.room.RoomInfo;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.ui.AttemptingJoinScreen;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import icu.wuhui.voxlink.ui.VoxLinkScreenBase;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public class RoomBrowserScreen
extends VoxLinkScreenBase {
    private static final int PAGE_SIZE = 3;
    private static final int PREV_BTN = 100;
    private static final int NEXT_BTN = 101;
    private static final int CATEGORY_BASE = 200;
    private static final String[][] CATEGORIES = new String[][]{{"all", "all"}, {"survival", "voxlink.gui.cat.survival"}, {"creative", "voxlink.gui.cat.creative"}, {"redstone", "voxlink.gui.cat.redstone"}, {"pvp", "voxlink.gui.cat.pvp"}, {"rpg", "voxlink.gui.cat.rpg"}, {"minigame", "voxlink.gui.cat.minigame"}, {"social", "voxlink.gui.cat.social"}, {"other", "voxlink.gui.cat.other"}};
    private static final int CARD_W = 320;
    private static final int CARD_H = 46;
    private static final int CARD_GAP = 6;
    private static final int CARD_TOP = 78;
    private final GuiScreen parentScreen;
    private String selectedCategory = "all";
    private int page = 1;
    private int totalPages = 1;
    private final List<RoomEntry> rooms = new ArrayList<RoomEntry>();
    private boolean loading = false;
    private String error = null;
    private int hoverCard = -1;

    public RoomBrowserScreen(GuiScreen guiScreen) {
        super("voxlink.gui.title", I18n.format((String)"voxlink.gui.lobby", (Object[])new Object[0]));
        this.parentScreen = guiScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        int n = 62;
        int n2 = 16;
        int n3 = 3;
        int n4 = 5;
        for (int i = 0; i < CATEGORIES.length; ++i) {
            int n5 = i / n4;
            int n6 = i % n4;
            int n7 = Math.min(n4, CATEGORIES.length - n5 * n4);
            int n8 = n7 * n + (n7 - 1) * n3;
            int n9 = (this.width - n8) / 2;
            int n10 = 26 + n5 * (n2 + 2);
            String string = "all".equals(CATEGORIES[i][0]) ? I18n.format((String)"voxlink.gui.all", (Object[])new Object[0]) : I18n.format((String)CATEGORIES[i][1], (Object[])new Object[0]);
            this.addButton(200 + i, n9 + n6 * (n + n3), n10, n, n2, string);
        }
        this.addButton(100, this.width / 2 - 110, this.height - 32, 60, 20, I18n.format((String)"voxlink.gui.prev_page", (Object[])new Object[0]));
        this.addButton(101, this.width / 2 + 50, this.height - 32, 60, 20, I18n.format((String)"voxlink.gui.next_page", (Object[])new Object[0]));
        if (this.rooms.isEmpty() && !this.loading) {
            this.loadRooms();
        }
    }

    private void loadRooms() {
        SignalingClient signalingClient = VoxLinkMod.getSignalingClient();
        if (signalingClient == null) {
            this.error = I18n.format((String)"voxlink.error.not_initialized", (Object[])new Object[0]);
            this.loading = false;
            return;
        }
        this.loading = true;
        this.error = null;
        String string = "all".equals(this.selectedCategory) ? null : this.selectedCategory;
        signalingClient.listRooms(this.page, 3, string, null).whenComplete((apiResponse, throwable) -> Minecraft.getMinecraft().addScheduledTask(() -> {
            this.loading = false;
            if (throwable != null) {
                this.error = "\u52a0\u8f7d\u5931\u8d25: " + throwable.getMessage();
                return;
            }
            try {
                if (apiResponse == null || apiResponse.data == null) {
                    this.error = "\u670d\u52a1\u5668\u8fd4\u56de\u4e3a\u7a7a";
                    return;
                }
                this.rooms.clear();
                JsonObject jsonObject = apiResponse.data;
                int n = jsonObject.has("total") ? jsonObject.get("total").getAsInt() : 0;
                this.totalPages = Math.max(1, (int)Math.ceil((double)n / 3.0));
                JsonArray jsonArray = jsonObject.getAsJsonArray("rooms");
                for (JsonElement jsonElement : jsonArray) {
                    JsonObject jsonObject2 = jsonElement.getAsJsonObject();
                    RoomEntry roomEntry = new RoomEntry();
                    roomEntry.code = RoomBrowserScreen.str(jsonObject2, "code");
                    roomEntry.name = RoomBrowserScreen.str(jsonObject2, "name");
                    roomEntry.category = RoomBrowserScreen.str(jsonObject2, "category");
                    roomEntry.loader = RoomBrowserScreen.str(jsonObject2, "loader");
                    roomEntry.gameVersion = RoomBrowserScreen.str(jsonObject2, "game_version", "gameVersion");
                    roomEntry.players = RoomBrowserScreen.intOr(jsonObject2, 0, "currentPlayers", "players");
                    roomEntry.maxPlayers = RoomBrowserScreen.intOr(jsonObject2, 0, "max_players", "maxPlayers");
                    roomEntry.hasPassword = RoomBrowserScreen.boolOr(jsonObject2, false, "has_password", "hasPassword");
                    this.rooms.add(roomEntry);
                }
            }
            catch (Exception exception) {
                this.error = "\u89e3\u6790\u5931\u8d25: " + exception.getMessage();
            }
        }));
    }

    private static String str(JsonObject jsonObject, String ... stringArray) {
        for (String string : stringArray) {
            if (!jsonObject.has(string) || jsonObject.get(string).isJsonNull()) continue;
            return jsonObject.get(string).getAsString();
        }
        return "";
    }

    private static int intOr(JsonObject jsonObject, int n, String ... stringArray) {
        for (String string : stringArray) {
            if (!jsonObject.has(string) || jsonObject.get(string).isJsonNull()) continue;
            return jsonObject.get(string).getAsInt();
        }
        return n;
    }

    private static boolean boolOr(JsonObject jsonObject, boolean bl, String ... stringArray) {
        for (String string : stringArray) {
            if (!jsonObject.has(string) || jsonObject.get(string).isJsonNull()) continue;
            return jsonObject.get(string).getAsBoolean();
        }
        return bl;
    }

    private void drawCentered(String string, int n, int n2) {
        this.fontRenderer.drawString(string, (this.width - this.fontRenderer.getStringWidth(string)) / 2, n, n2);
    }

    @Override
    public void drawScreen(int n, int n2, float f) {
        this.drawDefaultBackground();
        GuiScreen.drawRect((int)0, (int)0, (int)this.width, (int)this.height, (int)-1879048192);
        String string = I18n.format((String)"voxlink.gui.lobby", (Object[])new Object[0]);
        this.fontRenderer.drawString(string, (this.width - this.fontRenderer.getStringWidth(string)) / 2, 10, 0xFFFFFF);
        String string2 = "VoxLink 1.1.9";
        this.fontRenderer.drawString(string2, this.width - this.fontRenderer.getStringWidth(string2) - 6, 10, 0x888888);
        int n3 = (this.width - 320) / 2;
        this.hoverCard = -1;
        for (int i = 0; i < this.rooms.size() && i < 3; ++i) {
            boolean bl;
            RoomEntry roomEntry = this.rooms.get(i);
            int n4 = 78 + i * 52;
            boolean bl2 = bl = n >= n3 && n <= n3 + 320 && n2 >= n4 && n2 <= n4 + 46;
            if (bl) {
                this.hoverCard = i;
            }
            int n5 = bl ? -1069522784 : -1071636432;
            GuiScreen.drawRect((int)n3, (int)n4, (int)(n3 + 320), (int)(n4 + 46), (int)n5);
            this.fontRenderer.drawString(roomEntry.name.isEmpty() ? roomEntry.code : roomEntry.name, n3 + 8, n4 + 6, 0xFFFFFF);
            String string3 = roomEntry.gameVersion + (roomEntry.loader.isEmpty() ? "" : "  " + roomEntry.loader);
            if (!string3.isEmpty()) {
                this.fontRenderer.drawString(string3, n3 + 320 - 8 - this.fontRenderer.getStringWidth(string3), n4 + 6, 0x88CCFF);
            }
            String string4 = roomEntry.code;
            if (!roomEntry.category.isEmpty()) {
                string4 = string4 + "  " + this.catName(roomEntry.category);
            }
            string4 = string4 + "  " + roomEntry.players + "/" + roomEntry.maxPlayers;
            if (roomEntry.hasPassword) {
                string4 = string4 + "  " + I18n.format((String)"voxlink.gui.locked", (Object[])new Object[0]);
            }
            this.fontRenderer.drawString(string4, n3 + 8, n4 + 24, 0xAAAAAA);
            String string5 = I18n.format((String)"voxlink.gui.click_to_join", (Object[])new Object[0]);
            this.fontRenderer.drawString(string5, n3 + 320 - 8 - this.fontRenderer.getStringWidth(string5), n4 + 24, 0x666666);
        }
        if (this.loading) {
            this.drawCentered(I18n.format((String)"voxlink.gui.loading", (Object[])new Object[0]), 118, 0xAAAAAA);
        } else if (this.error != null) {
            this.drawCentered(this.error, 118, 0xFF5555);
        } else if (this.rooms.isEmpty()) {
            this.drawCentered(I18n.format((String)"voxlink.gui.no_rooms", (Object[])new Object[0]), 118, 0xAAAAAA);
        }
        String string6 = I18n.format((String)"voxlink.gui.page_fmt", (Object[])new Object[]{this.page, this.totalPages});
        this.drawCentered(string6, this.height - 26, 0xAAAAAA);
        for (Object e : this.buttonList) {
            ((GuiButton)e).drawButton(Minecraft.getMinecraft(), n, n2, f);
        }
    }

    private String catName(String string) {
        for (String[] stringArray : CATEGORIES) {
            if (!stringArray[0].equals(string)) continue;
            return I18n.format((String)stringArray[1], (Object[])new Object[0]);
        }
        return string;
    }

    protected void mouseClicked(int n, int n2, int n3) {
        super.mouseClicked(n, n2, n3);
        if (n3 == 0 && this.hoverCard >= 0 && this.hoverCard < this.rooms.size()) {
            RoomEntry roomEntry = this.rooms.get(this.hoverCard);
            this.joinRoom(roomEntry.code, roomEntry.hasPassword ? "" : null);
        }
    }

    private void joinRoom(String string, String string2) {
        if (string == null || string.isEmpty()) {
            return;
        }
        RoomManager roomManager = VoxLinkMod.getRoomManager();
        if (roomManager == null) {
            VoxLinkChat.print((ITextComponent)new TextComponentString("[VoxLink] \u623f\u95f4\u7ba1\u7406\u5668\u672a\u5c31\u7eea"));
            return;
        }
        roomManager.joinRoom(string, string2).whenComplete((roomInfo, throwable) -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.addScheduledTask(() -> {
                if (throwable != null) {
                    VoxLinkChat.print((ITextComponent)new TextComponentString("[VoxLink] \u52a0\u5165\u5931\u8d25: " + throwable.getMessage()));
                    return;
                }
                minecraft.displayGuiScreen((GuiScreen)new AttemptingJoinScreen(this, (RoomInfo)roomInfo));
            });
        });
    }

    @Override
    protected void actionPerformed(GuiButton guiButton) {
        int n = guiButton.id;
        if (n == 100) {
            if (this.page > 1) {
                --this.page;
                this.loadRooms();
            }
        } else if (n == 101) {
            if (this.page < this.totalPages) {
                ++this.page;
                this.loadRooms();
            }
        } else if (n >= 200 && n < 200 + CATEGORIES.length) {
            int n2 = n - 200;
            if (!CATEGORIES[n2][0].equals(this.selectedCategory)) {
                this.selectedCategory = CATEGORIES[n2][0];
                this.page = 1;
                this.loadRooms();
            }
        } else {
            super.actionPerformed(guiButton);
        }
    }

    @Override
    protected void addBackButton() {
        this.addButton(1000, this.width / 2 - 100, this.height - 56, 200, 20, "\u8fd4\u56de");
    }

    @Override
    protected void actionPerformedBack() {
        Minecraft.getMinecraft().displayGuiScreen(this.parentScreen);
    }

    static class RoomEntry {
        String code = "";
        String name = "";
        String category = "";
        String loader = "";
        String gameVersion = "";
        int players = 0;
        int maxPlayers = 0;
        boolean hasPassword = false;

        RoomEntry() {
        }
    }
}

