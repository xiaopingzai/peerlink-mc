/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiButton
 *  net.minecraft.client.gui.GuiIngameMenu
 *  net.minecraft.client.gui.GuiMainMenu
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.client.settings.KeyBinding
 *  net.minecraft.util.text.ITextComponent
 *  net.minecraft.util.text.TextComponentString
 *  net.minecraft.util.text.TextFormatting
 *  net.minecraftforge.client.event.GuiScreenEvent$ActionPerformedEvent$Post
 *  net.minecraftforge.client.event.GuiScreenEvent$InitGuiEvent$Post
 *  net.minecraftforge.common.MinecraftForge
 *  net.minecraftforge.fml.client.registry.ClientRegistry
 *  net.minecraftforge.fml.common.eventhandler.SubscribeEvent
 *  net.minecraftforge.fml.common.gameevent.PlayerEvent$PlayerLoggedInEvent
 *  net.minecraftforge.fml.common.gameevent.PlayerEvent$PlayerLoggedOutEvent
 *  net.minecraftforge.fml.common.gameevent.TickEvent$ClientTickEvent
 *  net.minecraftforge.fml.common.gameevent.TickEvent$Phase
 *  net.minecraftforge.fml.relauncher.Side
 *  net.minecraftforge.fml.relauncher.SideOnly
 */
package icu.wuhui.voxlink;

import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.room.RoomManager;
import icu.wuhui.voxlink.room.StunDetector;
import icu.wuhui.voxlink.ui.VoxLinkChat;
import icu.wuhui.voxlink.ui.VoxLinkScreen;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(value=Side.CLIENT)
public class VoxLinkClient {
    private static final int OPEN_SCREEN_BUTTON_ID = 9731;
    private static final int KEY_V = 47;
    private static volatile boolean inited = false;
    private static KeyBinding openScreenKey;

    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.register(VoxLinkClient.class);
    }

    public static void initClient() {
        if (inited) {
            return;
        }
        inited = true;
        SignalingClient signalingClient = VoxLinkMod.getSignalingClient();
        if (signalingClient != null) {
            TopologyClient topologyClient = new TopologyClient(signalingClient);
            RoomManager roomManager = new RoomManager(signalingClient, topologyClient);
            VoxLinkMod.setTopologyClient(topologyClient);
            VoxLinkMod.setRoomManager(roomManager);
            try {
                StunProbe.probeAsync(StunDetector.getStunServerGroups());
            }
            catch (Exception exception) {
                VoxLinkMod.LOGGER.warn("STUN prefetch on init failed: {}", (Object)exception.getMessage());
            }
            roomManager.setRoomLostCallback(new Runnable(){

                @Override
                public void run() {
                    final Minecraft minecraft = Minecraft.getMinecraft();
                    if (minecraft == null || minecraft.player == null) {
                        return;
                    }
                    minecraft.addScheduledTask(new Runnable(){

                        @Override
                        public void run() {
                            if (minecraft.player == null) {
                                return;
                            }
                            RoomManager roomManager = VoxLinkMod.getRoomManager();
                            String string = roomManager != null ? roomManager.getRoomLostReason() : null;
                            String string2 = "voxlink.room_lost.default";
                            if (string != null) {
                                if ("HOST_CLOSED".equals(string)) {
                                    string2 = "voxlink.room_lost.host_closed";
                                } else if ("HOST_DISCONNECTED".equals(string)) {
                                    string2 = "voxlink.room_lost.host_disconnected";
                                } else if ("ROOM_NOT_FOUND".equals(string)) {
                                    string2 = "voxlink.room_lost.host_gone";
                                } else if ("TOKEN_INVALID".equals(string)) {
                                    string2 = "voxlink.room_closed";
                                } else if ("ROOM_EVICTED".equals(string)) {
                                    string2 = "voxlink.room_lost.host_closed";
                                }
                            }
                            VoxLinkChat.print((ITextComponent)new TextComponentString(TextFormatting.RED + I18n.format((String)"voxlink.chat.error_prefix", (Object[])new Object[0]) + I18n.format((String)string2, (Object[])new Object[0])));
                            VoxLinkChat.print((ITextComponent)new TextComponentString(TextFormatting.GRAY + I18n.format((String)"voxlink.room_lost.hint", (Object[])new Object[0])));
                        }
                    });
                }
            });
        }
        try {
            PeerServer.start();
        }
        catch (Exception exception) {
            VoxLinkMod.LOGGER.warn("PeerServer.start failed: {}", (Object)exception.getMessage());
        }
        openScreenKey = new KeyBinding("voxlink.gui.title", 47, "VoxLink");
        ClientRegistry.registerKeyBinding((KeyBinding)openScreenKey);
        VoxLinkMod.LOGGER.info("VoxLink client initialized (V key + menu buttons)");
    }

    private static void openScreen(GuiScreen guiScreen) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        final VoxLinkScreen voxLinkScreen = guiScreen != null ? new VoxLinkScreen(guiScreen) : new VoxLinkScreen();
        minecraft.addScheduledTask(new Runnable(){

            @Override
            public void run() {
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft != null) {
                    minecraft.displayGuiScreen(voxLinkScreen);
                }
            }
        });
    }

    @SubscribeEvent
    public static void onClientPlayerLoggingIn(PlayerEvent.PlayerLoggedInEvent playerLoggedInEvent) {
    }

    @SubscribeEvent
    public static void onClientPlayerLoggingOut(PlayerEvent.PlayerLoggedOutEvent playerLoggedOutEvent) {
    }

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent clientTickEvent) {
        Minecraft minecraft;
        if (clientTickEvent.phase != TickEvent.Phase.END) {
            return;
        }
        if (openScreenKey != null && openScreenKey.isPressed() && (minecraft = Minecraft.getMinecraft()) != null && minecraft.currentScreen == null) {
            VoxLinkClient.openScreen(null);
        }
    }

    @SubscribeEvent
    public static void onGuiInit(GuiScreenEvent.InitGuiEvent.Post post) {
        GuiScreen guiScreen = post.getGui();
        if (!(guiScreen instanceof GuiMainMenu) && !(guiScreen instanceof GuiIngameMenu)) {
            return;
        }
        List<GuiButton> list = post.getButtonList();
        for (GuiButton guiButton : list) {
            if (guiButton.id != 9731) continue;
            return;
        }
        int n = guiScreen instanceof GuiMainMenu ? 6 : 6;
        list.add(new GuiButton(9731, guiScreen.width / 2 + 104, guiScreen.height / 4 + 72, 76, 20, I18n.format((String)"voxlink.gui.title", (Object[])new Object[0])));
    }

    @SubscribeEvent
    public static void onGuiAction(GuiScreenEvent.ActionPerformedEvent.Post post) {
        GuiButton guiButton = post.getButton();
        if (guiButton != null && guiButton.id == 9731) {
            VoxLinkClient.openScreen(post.getGui());
        }
    }

    private VoxLinkClient() {
    }
}

