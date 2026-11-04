package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.server.ServerChannelManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：退出频道
 */
public record LeaveChannelPacket() {

    public static void encode(LeaveChannelPacket msg, FriendlyByteBuf buf) {
    }

    public static LeaveChannelPacket decode(FriendlyByteBuf buf) {
        return new LeaveChannelPacket();
    }

    public static void handle(LeaveChannelPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null && ServerChannelManager.getChannel(sender) >= 0) {
            ServerChannelManager.leave(sender);
        }
    }
}
