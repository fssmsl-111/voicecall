package com.lmj.voicecall;

import com.lmj.voicecall.net.VoiceNetwork;
import com.lmj.voicecall.server.ServerChannelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 频道式远程语音通话。
 * <p>
 * 按住 R 键说话，同频道玩家无视距离互相听见，其余玩家能看到"抬手扶耳麦"的动画。
 */
@Mod(VoiceCallMod.MOD_ID)
public class VoiceCallMod {

    public static final String MOD_ID = "voicecall";
    public static final Logger LOGGER = LoggerFactory.getLogger("VoiceCall");

    public VoiceCallMod() {
        FMLJavaModLoadingContext.get().getModEventBus();
        VoiceNetwork.register();
        MinecraftForge.EVENT_BUS.register(ServerChannelManager.class);
        MinecraftForge.EVENT_BUS.register(ServerCommands.class);
        LOGGER.info("VoiceCall loaded");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
