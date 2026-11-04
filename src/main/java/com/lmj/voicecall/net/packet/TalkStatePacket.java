package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.server.ServerChannelManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：开始/停止说话。
 *
 * @param talking 是否在说话（任意模式）
 * @param channel 是否频道麦（true=频道麦，抬手动画；false=自由麦，只显示图标）
 */
public record TalkStatePacket(boolean talking, boolean channel) {

    public static void encode(TalkStatePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.talking);
        buf.writeBoolean(msg.channel);
    }

    public static TalkStatePacket decode(FriendlyByteBuf buf) {
        return new TalkStatePacket(buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(TalkStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            ServerChannelManager.setTalking(sender, msg.talking, msg.channel);
        }
    }
}
