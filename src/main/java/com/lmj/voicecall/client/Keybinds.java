package com.lmj.voicecall.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 键位定义（默认值，全部可在「选项 → 控制 → 按键绑定 → 频道语音通话」里改）：
 * <pre>
 *   按住 ~  频道麦说话
 *   V       频道面板
 *   M       自由麦开关
 * </pre>
 */
public final class Keybinds {

    public static final String CATEGORY = "key.categories.voicecall";

    /** 按住说话（频道麦，不限距离）：默认波浪键 ~，可自行改绑 */
    public static final KeyMapping PUSH_TO_TALK = new KeyMapping(
            "key.voicecall.push_to_talk",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            CATEGORY);

    /** 选择麦克风 */
    public static final KeyMapping MIC_SELECT = new KeyMapping(
            "key.voicecall.mic_select",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            CATEGORY);

    /** 打开频道面板 */
    public static final KeyMapping CHANNEL_MENU = new KeyMapping(
            "key.voicecall.channel_menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    /** 自由麦开关（语音激活） */
    public static final KeyMapping FREE_MIC = new KeyMapping(
            "key.voicecall.free_mic",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY);
    /** 频道麦是否按住 */
    public static boolean talkingKeyDown() {
        return PUSH_TO_TALK.isDown();
    }

}