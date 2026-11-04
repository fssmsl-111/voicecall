package com.lmj.voicecall.net.packet;

import com.lmj.voicecall.client.TalkAnimations;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：某个玩家的说话状态。
 *
 * @param talking 是否在说话（驱动头顶麦克风图标）
 * @param channel 是否频道麦（驱动抬手动画；自由麦不做手势）
 */
public record TalkBroadcastPacket(UUID player, boolean talking, boolean channel) {

    public static void encode(TalkBroadcastPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.player);
        buf.writeBoolean(msg.talking);
        buf.writeBoolean(msg.channel);
    }

    public static TalkBroadcastPacket decode(FriendlyByteBuf buf) {
        return new TalkBroadcastPacket(buf.readUUID(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(TalkBroadcastPacket msg, Supplier<NetworkEvent.Context> ctx) {
        TalkAnimations.setTalking(msg.player, msg.talking, msg.channel);
    }
}
