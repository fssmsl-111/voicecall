package com.lmj.voicecall.server;

import com.lmj.voicecall.VoiceCallMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立 UDP 语音中继（不占用 Minecraft 连接）。
 * <p>
 * 客户端需要先通过 MC 连接拿到端口和令牌，然后往这里发 UDP 语音包；
 * 中继按"频道/近距"规则转发给其他玩家。
 *
 * <pre>
 * 客户端 -> 服务端: [magic VCL1][token 8][flags 1][len 2][audio]
 * 服务端 -> 客户端: [magic VCL2][len 2][audio]
 * 服务端 -> 客户端: [magic VCL3][token 8]          (注册确认/keep-alive 回执)
 * </pre>
 */
public final class VoiceTunnelServer {

    private static int TUNNEL_STAT_FRAMES = 0;
    private static int TUNNEL_STAT_SENT = 0;

    private static final int MAGIC_C2S = 0x56434C31; // "VCL1"
    private static final int MAGIC_S2C = 0x56434C32; // "VCL2"
    private static final int MAGIC_ACK = 0x56434C33; // "VCL3"

    /** 超过这个时间没心跳就认为客户端离线 */
    private static final long TIMEOUT_MS = 10_000L;

    private static final Map<String, Endpoint> ENDPOINTS = new ConcurrentHashMap<>();
    private static volatile DatagramSocket socket;
    private static volatile Thread thread;
    private static volatile boolean running = false;
    private static volatile int port = 0;
    private static volatile MinecraftServer server;

    private static final class Endpoint {
        final UUID player;
        final InetSocketAddress address;
        final long lastSeen;

        Endpoint(UUID player, InetSocketAddress address, long lastSeen) {
            this.player = player;
            this.address = address;
            this.lastSeen = lastSeen;
        }
    }

    private VoiceTunnelServer() {
    }

    public static int getPort() {
        return port;
    }

    public static boolean isRunning() {
        return running;
    }

    /** 启动 UDP 中继；失败返回 false（客户端会退回 MC 连接传输） */
    public static synchronized boolean start(MinecraftServer srv, int wantedPort) {
        if (running) {
            return true;
        }
        server = srv;
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(wantedPort));
            s.setSoTimeout(1000);
            socket = s;
            port = s.getLocalPort();
            running = true;
            thread = new Thread(VoiceTunnelServer::loop, "VoiceCall-UdpServer");
            thread.setDaemon(true);
            thread.start();
            VoiceCallMod.LOGGER.info("[VoiceCall] 独立语音隧道已启动: udp://0.0.0.0:{}", port);
            return true;
        } catch (Throwable t) {
            VoiceCallMod.LOGGER.warn("[VoiceCall] 独立语音隧道启动失败(UDP {}): {}（将退回 MC 连接传输）",
                    wantedPort, t.getMessage());
            running = false;
            port = 0;
            return false;
        }
    }

    public static synchronized void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        socket = null;
        thread = null;
        port = 0;
        ENDPOINTS.clear();
        server = null;
    }

    private static void loop() {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running && socket != null) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                handle(packet);
            } catch (java.net.SocketTimeoutException ignored) {
                cleanup();
            } catch (Throwable t) {
                if (running) {
                    VoiceCallMod.LOGGER.warn("[VoiceCall] UDP 收包异常: {}", t.getMessage());
                }
            }
        }
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        ENDPOINTS.entrySet().removeIf(e -> now - e.getValue().lastSeen > TIMEOUT_MS);
    }

    private static void handle(DatagramPacket packet) {
        byte[] data = packet.getData();
        int len = packet.getLength();
        com.lmj.voicecall.net.TunnelProtocol.C2S msg =
                com.lmj.voicecall.net.TunnelProtocol.decodeC2S(data, len);
        if (msg == null) {
            return;
        }
        byte[] tokenBytes = msg.token();
        String token = hex(tokenBytes);
        int flags = msg.flags();
        byte[] audio = msg.audio();
        if (audio.length == 0) {
            // 保活包：只登记端点
            UUID owner0 = ServerChannelManager.tokenOwner(token);
            if (owner0 != null) {
                ENDPOINTS.put(token, new Endpoint(owner0,
                        new InetSocketAddress(packet.getAddress(), packet.getPort()),
                        System.currentTimeMillis()));
                sendAck(new InetSocketAddress(packet.getAddress(), packet.getPort()), tokenBytes);
            }
            return;
        }

        UUID owner = ServerChannelManager.tokenOwner(token);
        if (owner == null) {
            return;
        }
        InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());
        ENDPOINTS.put(token, new Endpoint(owner, addr, System.currentTimeMillis()));
        sendAck(addr, tokenBytes);

        relay(owner, addr, flags, msg.frameSeq(), audio);
    }

    private static void sendAck(InetSocketAddress addr, byte[] tokenBytes) {
        try {
            byte[] ack = com.lmj.voicecall.net.TunnelProtocol.encodeAck(tokenBytes);
            socket.send(new DatagramPacket(ack, ack.length, addr));
        } catch (Throwable ignored) {
        }
    }

    /** 按频道/近距规则转发 */
    private static void relay(UUID fromId, InetSocketAddress fromAddr, int flags, int frameSeq, byte[] audio) {
        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        ServerPlayer from = srv.getPlayerList().getPlayer(fromId);
        if (from == null) {
            return;
        }
        Integer channelBox = ServerChannelManager.channelOf(fromId);
        int channel = channelBox == null ? -1 : channelBox;
        boolean toChannel = (flags & 1) != 0 && channel >= 0;
        boolean toProximity = (flags & 2) != 0;
        // 对讲机模式：频道同一时刻只能一个人说话
        if (toChannel && !ServerChannelManager.takeFloor(channel, fromId)) {
            toChannel = false;
        }
        if (!toChannel && !toProximity) {
            return;
        }
        double range = toProximity ? ServerChannelManager.getProximityRange() : 0;
        double rangeSq = range * range;
        int seq = frameSeq;
        double px = from.getX(), py = from.getY(), pz = from.getZ();

        // 频道包（不衰减）
        byte[] channelPayload = com.lmj.voicecall.net.TunnelProtocol.encodeS2C(
                fromId, seq, com.lmj.voicecall.net.TunnelProtocol.MODE_CHANNEL, 0f, 0f, 0f, audio);
        // 近距包（带声源坐标，客户端做近大远小）
        byte[] proximityPayload = com.lmj.voicecall.net.TunnelProtocol.encodeS2C(
                fromId, seq, com.lmj.voicecall.net.TunnelProtocol.MODE_PROXIMITY,
                (float) px, (float) py, (float) pz, audio);

        java.util.List<String> listenerNames = new java.util.ArrayList<>();
        // 已通过隧道送达的玩家（MC 兜底时跳过）
        java.util.Set<UUID> udpDelivered = new java.util.HashSet<>();
        // 同一玩家可能注册了多个 token（每次进频道都会发新 token），
        // 只保留最新端点，否则同一帧会重复发给同一个人 → 客户端时序错乱、断续
        java.util.Map<UUID, Endpoint> latest = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Endpoint> e : ENDPOINTS.entrySet()) {
            Endpoint ep = e.getValue();
            if (now - ep.lastSeen > TIMEOUT_MS) {
                continue;
            }
            Endpoint prev = latest.get(ep.player);
            if (prev == null || ep.lastSeen > prev.lastSeen) {
                latest.put(ep.player, ep);
            }
        }
        for (Map.Entry<UUID, Endpoint> e : latest.entrySet()) {
            UUID playerId = e.getKey();
            Endpoint ep = e.getValue();
            if (playerId.equals(fromId)) {
                continue;
            }
            if (toChannel) {
                Integer tc = ServerChannelManager.channelOf(playerId);
                if (tc != null && tc == channel) {
                    try {
                        socket.send(new DatagramPacket(channelPayload, channelPayload.length, ep.address));
                        udpDelivered.add(playerId);
                        ServerPlayer sp = srv.getPlayerList().getPlayer(playerId);
                        if (sp != null) {
                            listenerNames.add(sp.getGameProfile().getName());
                        }
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
            }
            if (toProximity) {
                ServerPlayer target = srv.getPlayerList().getPlayer(playerId);
                if (target != null && target.level() == from.level()
                        && target.distanceToSqr(from.position()) <= rangeSq) {
                    try {
                        socket.send(new DatagramPacket(proximityPayload, proximityPayload.length, ep.address));
                        udpDelivered.add(playerId);
                        listenerNames.add(target.getGameProfile().getName());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    
        // 隧道没送到的玩家：走 MC 连接兜底（UDP 被防火墙拦 / 初始化失败时仍能听到）
        ServerChannelManager.relayAudioMcExcept(from, audio, flags, frameSeq, udpDelivered);

}


    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
