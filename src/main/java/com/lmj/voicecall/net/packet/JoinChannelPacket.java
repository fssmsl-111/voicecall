package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.server.ServerChannelManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：加入频道（由频道面板 GUI 触发）
 */
public record JoinChannelPacket(int channel) {

    public static void encode(JoinChannelPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.channel);
    }

    public static JoinChannelPacket decode(FriendlyByteBuf buf) {
        return new JoinChannelPacket(buf.readVarInt());
    }

    public static void handle(JoinChannelPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            ServerChannelManager.join(sender, Math.max(0, Math.min(999, msg.channel)));
        }
    }
}
