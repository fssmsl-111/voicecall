package com.lmj.voicecall.client;

import com.lmj.voicecall.VoiceCallMod;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用 PlayerAnimator 给"正在说话"的玩家播放抬手扶耳麦的动作。
 * <p>
 * 姿势参数已固定（按调试调好的值硬编码）：
 * <pre>
 *   pitch=-3.130  roll=0.410  yaw=-0.120  bend=1.200  bendAxis=0.0
 * </pre>
 */
public final class TalkAnimations {

    private static final int LAYER_ID = 0x5643; // "VC"

    // ---- 硬编码的姿势参数（弧度）----
    /** 抬多高：-1.57=水平前伸, -3.14=竖直向上 */
    private static final float POSE_PITCH = -3.130f;
    /** 内收/外张：正数=向头部内侧收 */
    private static final float POSE_ROLL = 0.410f;
    /** 前后微调 */
    private static final float POSE_YAW = -0.120f;
    /** 肘部弯曲量：0=伸直, 1.0~1.5=明显弯肘 */
    private static final float POSE_BEND = 1.200f;
    /** 弯曲方向：0=向前, 1.57=向上, 3.14=向后 */
    private static final float POSE_BEND_AXIS = 0.0f;

    /** 任意模式在说话（用于 HUD 名字列表） */
    private static final Map<UUID, Boolean> TALKING = new HashMap<>();
    /** 仅频道麦在说话（用于抬手动画；自由麦不做手势） */
    private static final Map<UUID, Boolean> CHANNEL_TALKING = new HashMap<>();
    private static final Map<UUID, ModifierLayer<TalkPose>> LAYERS = new HashMap<>();
    /** 玩家开始说话的时间（用于 HUD 按先后排序） */
    private static final Map<UUID, Long> TALK_START = new HashMap<>();

    private TalkAnimations() {
    }

    // ---------------- 状态 ----------------

    /**
     * @param talking 是否在说话（任意模式，驱动 HUD 名字列表）
     * @param channel 是否频道麦（驱动抬手动画；自由麦传 false）
     */
    public static void setTalking(UUID player, boolean talking, boolean channel) {
        VoiceCallMod.LOGGER.info("[VoiceCall] 收到说话状态: {} talking={} channel={}", player, talking, channel);
        Boolean was = TALKING.get(player);
        TALKING.put(player, talking);
        CHANNEL_TALKING.put(player, talking && channel);
        if (talking) {
            if (was == null || !was) {
                TALK_START.put(player, System.currentTimeMillis());
            }
        } else {
            TALK_START.remove(player);
        }
    }

    public static void setTalking(UUID player, boolean talking) {
        setTalking(player, talking, true);
    }

    /** HUD 名字列表用：任意模式说话都算 */
    public static boolean isTalking(UUID player) {
        return TALKING.getOrDefault(player, false);
    }

    /** 开始说话的时间戳（毫秒），0 表示没在说话 */
    public static long talkStart(UUID player) {
        return TALK_START.getOrDefault(player, 0L);
    }

    /** 抬手动画用：只有频道麦 */
    public static boolean isChannelTalking(UUID player) {
        return CHANNEL_TALKING.getOrDefault(player, false);
    }

    // ---------------- tick ----------------

    /** 每客户端 tick 调用 */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!LAYERS.isEmpty()) {
                clear();
            }
            return;
        }
        for (AbstractClientPlayer player : mc.level.players()) {
            ModifierLayer<TalkPose> layer = LAYERS.get(player.getUUID());
            if (layer == null) {
                layer = attach(player);
                if (layer == null) {
                    continue;
                }
                LAYERS.put(player.getUUID(), layer);
            }
            TalkPose pose = layer.getAnimation();
            if (pose != null) {
                boolean want = CHANNEL_TALKING.getOrDefault(player.getUUID(), false);
                if (want != pose.active) {
                    pose.setActive(want);
                    VoiceCallMod.LOGGER.info("[VoiceCall] 动画 {} -> {}", player.getName().getString(), want);
                }
            }
        }
    }

    private static ModifierLayer<TalkPose> attach(AbstractClientPlayer player) {
        try {
            var stack = PlayerAnimationAccess.getPlayerAnimLayer(player);
            if (stack == null) {
                return null;
            }
            ModifierLayer<TalkPose> layer = new ModifierLayer<>();
            TalkPose pose = new TalkPose();
            layer.setAnimation(pose);
            stack.addAnimLayer(LAYER_ID, layer);
            VoiceCallMod.LOGGER.info("[VoiceCall] 动画层已附加: {}", player.getName().getString());
            return layer;
        } catch (Throwable t) {
            VoiceCallMod.LOGGER.warn("[VoiceCall] 附加动画层失败({}): {}",
                    player.getName().getString(), t.toString());
            return null;
        }
    }

    public static void clear() {
        LAYERS.clear();
        TALKING.clear();
        CHANNEL_TALKING.clear();
        TALK_START.clear();
    }

    // ---------------- 动画实现 ----------------

    /** 抬手扶耳麦的姿势（绝对角度，覆盖走路摆动） */
    static final class TalkPose implements IAnimation {

        private boolean active;
        private float progress;

        void setActive(boolean value) {
            this.active = value;
        }

        @Override
        public boolean isActive() {
            return active || progress > 0.01f;
        }

        @Override
        public void tick() {
            if (active) {
                progress = Math.min(1f, progress + 0.22f);
            } else {
                progress = Math.max(0f, progress - 0.18f);
            }
        }

        @Override
        public void setupAnim(float partialTick) {
            // 无需每帧处理
        }

        @Override
        public FirstPersonMode getFirstPersonMode(float tickDelta) {
            // 第一人称不播放，避免手臂糊在镜头上
            return FirstPersonMode.NONE;
        }

        @Override
        public Vec3f get3DTransform(String partName, TransformType type, float partialTick, Vec3f current) {
            if (progress <= 0.001f) {
                return current;
            }
            if (!isRightArm(partName)) {
                return current;
            }
            float t = ease(progress);

            // 弯曲（PlayerAnimator 的 BendHelper 会把模型分段做出"肘关节"效果）
            if (type == TransformType.BEND) {
                return new Vec3f(POSE_BEND * t, POSE_BEND_AXIS * t, 0f);
            }
            if (type != TransformType.ROTATION) {
                return current;
            }
            // 目标为绝对角度：完全举起后手臂静止，不再随走路摆动
            float pitch = current.getX() * (1f - t) + POSE_PITCH * t;
            float yaw = current.getY() * (1f - t) + POSE_YAW * t;
            float roll = current.getZ() * (1f - t) + POSE_ROLL * t;
            return new Vec3f(pitch, yaw, roll);
        }

        private static boolean isRightArm(String name) {
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("rightarm") || lower.contains("right_arm");
        }

        private static float ease(float x) {
            return x * x * (3f - 2f * x);
        }
    }
}
