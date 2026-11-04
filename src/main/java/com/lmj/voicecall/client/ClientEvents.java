package com.lmj.voicecall.client;

import com.lmj.voicecall.VoiceCallMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端事件：按键注册 / tick / HUD
 */
public final class ClientEvents {

    private ClientEvents() {
    }

    /** Mod 总线：注册按键 */
    @Mod.EventBusSubscriber(modid = VoiceCallMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {

        private ModBus() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(Keybinds.PUSH_TO_TALK);
                event.register(Keybinds.CHANNEL_MENU);
            event.register(Keybinds.FREE_MIC);
        }
    }

    /** Forge 总线：tick / HUD / 退出清理 */
    @Mod.EventBusSubscriber(modid = VoiceCallMod.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeBus {

        private ForgeBus() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            // 按 C 打开频道面板
            while (Keybinds.CHANNEL_MENU.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    mc.setScreen(new ChannelScreen());
                }
            }
            ClientVoiceManager.tick(mc);
            TalkAnimations.clientTick();
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            VoiceHud.render(event.getGuiGraphics(), event.getPartialTick());
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientVoiceManager.reset();
        }
    }
}
