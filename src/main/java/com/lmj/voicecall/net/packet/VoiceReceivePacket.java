package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.client.ClientVoiceManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：某个说话者的音频
 * <p>
 * mode: 1 = 频道（不限距离，不衰减）；2 = 近距（按声源坐标衰减）
 * x/y/z 仅在近距模式下有效
 */
public record VoiceReceivePacket(UUID sender, int seq, int mode,
                                 float x, float y, float z, byte[] data) {

    public static final int MODE_CHANNEL = 1;
    public static final int MODE_PROXIMITY = 2;

    public static void encode(VoiceReceivePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sender);
        buf.writeVarInt(msg.seq);
        buf.writeByte(msg.mode);
        if (msg.mode == MODE_PROXIMITY) {
            buf.writeFloat(msg.x);
            buf.writeFloat(msg.y);
            buf.writeFloat(msg.z);
        }
        buf.writeByteArray(msg.data);
    }

    public static VoiceReceivePacket decode(FriendlyByteBuf buf) {
        UUID sender = buf.readUUID();
        int seq = buf.readVarInt();
        int mode = buf.readUnsignedByte();
        float x = 0f, y = 0f, z = 0f;
        if (mode == MODE_PROXIMITY) {
            x = buf.readFloat();
            y = buf.readFloat();
            z = buf.readFloat();
        }
        return new VoiceReceivePacket(sender, seq, mode, x, y, z, buf.readByteArray());
    }

    public static void handle(VoiceReceivePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ClientVoiceManager.onRemoteAudioMc(msg.sender, msg.seq, msg.mode, msg.x, msg.y, msg.z, msg.data);
    }
}