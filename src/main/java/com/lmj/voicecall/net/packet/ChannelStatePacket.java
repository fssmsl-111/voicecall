package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.client.ClientVoiceManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：当前所在频道（-1 表示未加入）+ 成员列表（含谁在说话）
 */
public record ChannelStatePacket(int channel, List<ChannelStatePacket.Member> members) {

    /** 频道成员：名字 + 是否正在说话 */
    public record Member(String name, boolean talking) {
    }

    public static void encode(ChannelStatePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.channel);
        List<Member> list = msg.members == null ? List.of() : msg.members;
        buf.writeVarInt(list.size());
        for (Member m : list) {
            buf.writeUtf(m.name() == null ? "" : m.name(), 64);
            buf.writeBoolean(m.talking());
        }
    }

    public static ChannelStatePacket decode(FriendlyByteBuf buf) {
        int ch = buf.readInt();
        int n = buf.readVarInt();
        List<Member> list = new ArrayList<>(Math.max(0, Math.min(n, 256)));
        for (int i = 0; i < n && i < 256; i++) {
            list.add(new Member(buf.readUtf(64), buf.readBoolean()));
        }
        return new ChannelStatePacket(ch, list);
    }

    public static void handle(ChannelStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ClientVoiceManager.setChannel(msg.channel, msg.members);
    }
}
