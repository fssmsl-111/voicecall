package com.lmj.voicecall.client;

import com.lmj.voicecall.net.VoiceNetwork;
import com.lmj.voicecall.net.packet.TalkStatePacket;
import com.lmj.voicecall.net.packet.VoiceDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 客户端语音状态机。两套并行：
 * <ul>
 *   <li><b>频道麦</b>：按住 ~，发给同频道玩家，<b>不限距离</b></li>
 *   <li><b>自由麦</b>：按 M 开启，语音激活，发给<b>附近玩家</b>（有距离限制）</li>
 * </ul>
 */
public final class ClientVoiceManager {

    private static volatile float vadThreshold = 0.020f;
    private static volatile long vadHangoverMs = 350L;

    private static int channel = -1;
    private static int members = 0;
    /** 当前频道成员名单 */
    /** 当前频道成员（名字 + 说话状态） */
    private static volatile java.util.List<com.lmj.voicecall.net.packet.ChannelStatePacket.Member> memberInfos = java.util.List.of();

    private static volatile boolean micOn = false;
    private static volatile boolean freeMic = false;
    /** 按住 ~（频道麦） */
    private static volatile boolean channelPtt = false;
    /** 频道里正在说话的其他人（对讲机模式：他说话时我按了也不发） */
    private static volatile java.util.UUID channelBusyBy = null;
    /** 占用者名字（来自成员列表，最可靠） */
    private static volatile String busyName = null;
    /** 自由麦人声门 */
    private static volatile boolean proxOpen = false;
    private static long lastVoiceAt = 0L;

    private static volatile boolean speaking = false;
    /** 上次广播的模式：true=频道麦（做手势），false=自由麦（只显示图标） */
    private static volatile boolean speakingChannel = false;
    private static String error = null;
    private static boolean micWarned = false;
    /** 诊断用：发送帧计数 */
    private static int sentFrames = 0;

    /** 发送帧号（隧道/MC 两条路共用，接收端据此去重） */
    private static volatile int frameSeq = 1;

    private static final MicCapture.FrameSink SINK = (data, ts) -> {
        int flags = 0;
        if (channelPtt) {
            flags |= VoiceDataPacket.FLAG_CHANNEL;
        }
        if (proxOpen) {
            flags |= VoiceDataPacket.FLAG_PROXIMITY;
        }
        if (flags == 0) {
            return;
        }
        try {
            int seq = frameSeq++;
            boolean viaTunnel = false;
            if (VoiceTunnelClient.isUseTunnelEnabled() && VoiceTunnelClient.isReady()) {
                viaTunnel = VoiceTunnelClient.send(flags, seq, data);
            }
            if (!viaTunnel) {
                // 隧道不可用时退回 Minecraft 连接传输（同一帧号，接收端可以正确去重）
                VoiceNetwork.sendToServer(new VoiceDataPacket(data, flags, seq));
            }
            sentFrames++;
            if (sentFrames >= 3000) {
                com.lmj.voicecall.VoiceCallMod.LOGGER.info(
                        "[VoiceCall] 已发送音频: {} 帧（频道={} 近距={} 通道={}）",
                        sentFrames, (flags & 1) != 0, (flags & 2) != 0, viaTunnel ? "UDP隧道" : "MC连接");
                sentFrames = 0;
            }
        } catch (Throwable ignored) {
        }
    };
    private ClientVoiceManager() {
    }

    // ---------------- 查询 ----------------

    public static int getChannel() {
        return channel;
    }

    public static int getMembers() {
        return members;
    }

    public static boolean isTalking() {
        return speaking;
    }
    public static boolean isChannelTalking() {
        return channelPtt;
    }

    public static boolean isFreeMic() {
        return freeMic;
    }

    public static boolean isProximityTalking() {
        return proxOpen;
    }

    public static boolean isMicOn() {
        return micOn;
    }

    public static String getError() {
        return error;
    }

    public static float micLevel() {
        return MicCapture.level();
    }

    // ---------------- 状态 ----------------

    public static void setChannel(int ch, int mem) {
        channel = ch;
        members = mem;
        if (ch < 0) {
            memberInfos = java.util.List.of();
        }
    }

    /** 服务端下发频道状态 + 成员名单 */
    public static void setChannel(int ch, java.util.List<com.lmj.voicecall.net.packet.ChannelStatePacket.Member> list) {
        channel = ch;
        memberInfos = list == null ? java.util.List.of() : java.util.List.copyOf(list);
        members = memberInfos.size();
        // 成员列表里只要"别人"在说话，就算频道被占用（对讲机模式）
        String self = "";
        net.minecraft.client.Minecraft mcm = net.minecraft.client.Minecraft.getInstance();
        if (mcm != null && mcm.player != null) {
            self = mcm.player.getName().getString();
        }
        String found = null;
        for (com.lmj.voicecall.net.packet.ChannelStatePacket.Member m : memberInfos) {
            if (m.talking() && !m.name().equals(self)) {
                found = m.name();
                break;
            }
        }
        busyName = found;
        if (found != null) {
            channelBusyBy = null;
        }
    }

    /** 频道占用者名字（空 = 没被占用） */
    public static String busyName() {
        return busyName;
    }

    public static java.util.List<com.lmj.voicecall.net.packet.ChannelStatePacket.Member> getMemberInfos() {
        return memberInfos;
    }

    public static void reset() {
        micOn = false;
        channelPtt = false;
        proxOpen = false;
        freeMic = false;
        announce(false, false);
        channel = -1;
        members = 0;
        error = null;
        MicCapture.stop();
        AudioPlayback.stop();
        TalkAnimations.clear();
        VoiceTunnelClient.shutdown();
    }

    private static void startMic() {
        com.lmj.voicecall.VoiceCallMod.LOGGER.info("[VoiceCall] 音频处理: 无（原始语音）");
        if (!MicCapture.start()) {
            error = MicCapture.lastError();
            if (!micWarned) {
                micWarned = true;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("§c[通话] 麦克风打开失败：" + error), false);
                }
            }
            return;
        }
        AudioPlayback.start();
        MicCapture.setSink(SINK);
        micOn = true;
    }

    private static void stopMic() {
        micOn = false;
        proxOpen = false;
        MicCapture.stop();
    }

    private static void announce(boolean talking, boolean channel) {
        boolean ch = talking && channel;
        if (speaking == talking && speakingChannel == ch) {
            return;
        }
        speaking = talking;
        speakingChannel = ch;
        com.lmj.voicecall.VoiceCallMod.LOGGER.info("[VoiceCall] 说话状态变更: talking={} channel={}", talking, ch);
        VoiceNetwork.sendToServer(new TalkStatePacket(talking, ch));
        // 本地立即应用动画：自己第三人称也能马上看到抬手
        net.minecraft.client.Minecraft mcm = net.minecraft.client.Minecraft.getInstance();
        if (mcm.player != null) {
            TalkAnimations.setTalking(mcm.player.getUUID(), ch);
        }
    }


    // ---------------- tick ----------------


    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (mc.player == null) {
            return;
        }
        boolean uiFree = mc.screen == null;

        // 自由麦开关（M）
        while (Keybinds.FREE_MIC.consumeClick()) {
            freeMic = !freeMic;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(freeMic
                        ? "§a[通话] 自由麦 开启（附近玩家能听到，按住 ~ 可与频道通话）"
                        : "§7[通话] 自由麦 关闭"), true);
            }
        }


        // 频道麦：按住 ~ 且已在频道
        boolean busyByOther = busyName != null || (channelBusyBy != null
                && (mc.player == null || !channelBusyBy.equals(mc.player.getUUID())));
        channelPtt = uiFree && channel >= 0 && Keybinds.talkingKeyDown() && !busyByOther;

        // 自由麦人声门
        if (freeMic) {
            updateVad();
        } else {
            proxOpen = false;
        }

        boolean wantMic = channelPtt || freeMic;
        if (wantMic && !micOn) {
            startMic();
        } else if (!wantMic && micOn) {
            stopMic();
        }

        announce(channelPtt || proxOpen, channelPtt);

    }

    private static void updateVad() {
        long now = System.currentTimeMillis();
        boolean voiced;
        if (AudioFilter.isRnNoiseActive()) {
            float prob = AudioFilter.lastSpeechProbability();
            voiced = prob > 0.15f || MicCapture.level() > vadThreshold;
        } else {
            voiced = MicCapture.level() > vadThreshold;
        }
        if (voiced) {
            lastVoiceAt = now;
        }
        if (AudioFilter.isVadGate()) {
            proxOpen = (now - lastVoiceAt) < vadHangoverMs;
        } else {
            // 不掐帧：自由麦开着就一直发送（降噪交给 RNNoise）
            proxOpen = freeMic;
        }
    }

    // ---------------- 网络回调 ----------------

    /** MC 包路径 */
    public static void onRemoteAudioMc(UUID sender, int seq, int mode, float x, float y, float z, byte[] data) {
        onRemoteAudio(sender, seq, mode, x, y, z, data);
    }

    /** 隧道路径 */
    public static void onRemoteAudioTunnel(UUID sender, int seq, int mode, float x, float y, float z, byte[] data) {
        onRemoteAudio(sender, seq, mode, x, y, z, data);
    }

    public static void onRemoteAudio(UUID sender, int seq, int mode, float x, float y, float z, byte[] data) {
        AudioPlayback.start();
        AudioPlayback.submitRemote(sender, seq, mode, x, y, z, data);
    }

    /** 频道是否被别人占用（对讲机模式） */
    public static boolean isChannelBusy() {
        return busyName != null || channelBusyBy != null;
    }

    /** 占用频道的玩家名字（查不到返回"队友"） */
    public static String channelBusyName() {
        if (busyName != null) {
            return busyName;
        }
        java.util.UUID id = channelBusyBy;
        if (id == null) {
            return "";
        }
        try {
            net.minecraft.client.Minecraft m = net.minecraft.client.Minecraft.getInstance();
            if (m.level != null) {
                net.minecraft.world.entity.player.Player p = m.level.getPlayerByUUID(id);
                if (p != null) {
                    return p.getName().getString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "队友";
    }

    public static void onTalkBroadcast(UUID player, boolean value) {
        if (value) {
            channelBusyBy = player;
        } else if (player.equals(channelBusyBy)) {
            channelBusyBy = null;
        }
        TalkAnimations.setTalking(player, value);
    }

    public static void applyVadConfig(float threshold, long hangoverMs) {
        vadThreshold = threshold;
        vadHangoverMs = hangoverMs;
    }
}
