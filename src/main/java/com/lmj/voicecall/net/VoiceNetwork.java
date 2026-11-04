package com.lmj.voicecall.net;

import com.lmj.voicecall.VoiceCallMod;
import com.lmj.voicecall.net.packet.ChannelStatePacket;
import com.lmj.voicecall.net.packet.JoinChannelPacket;
import com.lmj.voicecall.net.packet.LeaveChannelPacket;
import com.lmj.voicecall.net.packet.TalkBroadcastPacket;
import com.lmj.voicecall.net.packet.TalkStatePacket;
import com.lmj.voicecall.net.packet.VoiceDataPacket;
import com.lmj.voicecall.net.packet.VoiceReceivePacket;
import com.lmj.voicecall.net.packet.VoiceTunnelInfoPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class VoiceNetwork {

    private static final String PROTOCOL = "1";
    private static int nextId = 0;
    public static SimpleChannel CHANNEL;

    private VoiceNetwork() {
    }

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                VoiceCallMod.id("main"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals);

        // 客户端 -> 服务端
        CHANNEL.messageBuilder(VoiceDataPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VoiceDataPacket::encode)
                .decoder(VoiceDataPacket::decode)
                .consumerNetworkThread(VoiceDataPacket::handle)
                .add();

        CHANNEL.messageBuilder(TalkStatePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TalkStatePacket::encode)
                .decoder(TalkStatePacket::decode)
                .consumerMainThread(TalkStatePacket::handle)
                .add();

        CHANNEL.messageBuilder(JoinChannelPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(JoinChannelPacket::encode)
                .decoder(JoinChannelPacket::decode)
                .consumerMainThread(JoinChannelPacket::handle)
                .add();

        CHANNEL.messageBuilder(LeaveChannelPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(LeaveChannelPacket::encode)
                .decoder(LeaveChannelPacket::decode)
                .consumerMainThread(LeaveChannelPacket::handle)
                .add();

        // 服务端 -> 客户端
        CHANNEL.messageBuilder(VoiceReceivePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VoiceReceivePacket::encode)
                .decoder(VoiceReceivePacket::decode)
                .consumerNetworkThread(VoiceReceivePacket::handle)
                .add();

        CHANNEL.messageBuilder(ChannelStatePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ChannelStatePacket::encode)
                .decoder(ChannelStatePacket::decode)
                .consumerMainThread(ChannelStatePacket::handle)
                .add();

        CHANNEL.messageBuilder(TalkBroadcastPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TalkBroadcastPacket::encode)
                .decoder(TalkBroadcastPacket::decode)
                .consumerMainThread(TalkBroadcastPacket::handle)
                .add();
        CHANNEL.messageBuilder(VoiceTunnelInfoPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VoiceTunnelInfoPacket::encode)
                .decoder(VoiceTunnelInfoPacket::decode)
                .consumerMainThread(VoiceTunnelInfoPacket::handle)
                .add();

        VoiceCallMod.LOGGER.info("Voice protocol registered ({} packets)", nextId);
    }

    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void sendToAll(Object msg) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), msg);
    }
}
