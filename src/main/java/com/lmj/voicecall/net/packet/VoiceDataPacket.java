package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.server.ServerChannelManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：一帧压缩后的音频 + 投递方式标记
 * <p>
 * flags:
 * <ul>
 *   <li>bit0 = 频道麦（同频道，不限距离）</li>
 *   <li>bit1 = 自由麦（附近玩家，有距离限制）</li>
 * </ul>
 */
public record VoiceDataPacket(byte[] data, int flags, int frameSeq) {

    public static final int FLAG_CHANNEL = 1;
    public static final int FLAG_PROXIMITY = 2;
    public static void encode(VoiceDataPacket msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.data);
        buf.writeByte(msg.flags & 0xFF);
        buf.writeVarInt(msg.frameSeq);
    }

    public static VoiceDataPacket decode(FriendlyByteBuf buf) {
        return new VoiceDataPacket(buf.readByteArray(), buf.readUnsignedByte(), buf.readVarInt());
    }

    public static void handle(VoiceDataPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null && msg.data.length > 0 && msg.flags != 0) {
            ServerChannelManager.relayAudio(sender, msg.data, msg.flags, msg.frameSeq);
        }
    }
}
