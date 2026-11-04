package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.client.VoiceTunnelClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：独立语音隧道信息（UDP 端口 + 身份令牌）。
 * <p>
 * 客户端收到后用这个端口和令牌去连服务端的 UDP 语音中继。
 *
 * @param port  UDP 语音端口（0 = 服务端未开启隧道）
 * @param token 8 字节身份令牌，服务端用来识别是哪个玩家
 */
public record VoiceTunnelInfoPacket(String host, int port, byte[] token) {

    public static void encode(VoiceTunnelInfoPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.host == null ? "" : msg.host);
        buf.writeInt(msg.port);
        byte[] t = msg.token == null ? new byte[8] : msg.token;
        buf.writeByteArray(t);
    }

    public static VoiceTunnelInfoPacket decode(FriendlyByteBuf buf) {
        return new VoiceTunnelInfoPacket(buf.readUtf(), buf.readInt(), buf.readByteArray());
    }

    public static void handle(VoiceTunnelInfoPacket msg, Supplier<NetworkEvent.Context> ctx) {
        VoiceTunnelClient.configure(msg.host(), msg.port(), msg.token());
    }
}
