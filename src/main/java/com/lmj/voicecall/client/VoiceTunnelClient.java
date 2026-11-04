package com.lmj.voicecall.client;

import com.lmj.voicecall.VoiceCallMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * 独立 UDP 语音隧道（客户端）。
 * <p>
 * 服务端通过 MC 连接下发端口 + 令牌，这里开一个独立 UDP socket，
 * 音频不再占用 Minecraft 的 TCP 连接。
 * <p>
 * 如果隧道没建立起来（防火墙/端口不通），自动退回 MC 连接传输。
 */
public final class VoiceTunnelClient {

    private static final int MAGIC_C2S = 0x56434C31;
    private static final int MAGIC_S2C = 0x56434C32;
    private static final int MAGIC_ACK = 0x56434C33;

    private static volatile boolean ready = false;
    /** 配置开关：是否允许走 UDP 隧道（false = 强制走 MC 连接） */
    private static volatile boolean useTunnelEnabled = true;

    public static boolean isUseTunnelEnabled() {
        return useTunnelEnabled;
    }
    private static volatile int targetPort = 0;
    private static volatile byte[] token;
    private static volatile DatagramSocket socket;
    private static volatile InetSocketAddress target;
    private static volatile long lastAckMs = 0L;
    private static volatile Thread receiver;
    private static volatile Thread keeper;

    private VoiceTunnelClient() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static int getTargetPort() {
        return targetPort;
    }

    /** 收到服务端下发的隧道信息 */
    public static synchronized void configure(String serverHost, int port, byte[] tokenBytes) {
        shutdown();
        if (tokenBytes == null || tokenBytes.length < 8) {
            VoiceCallMod.LOGGER.info("[VoiceCall] 服务端未开启语音隧道（将走 MC 连接）");
            return;
        }
        String host = (serverHost != null && !serverHost.isEmpty()) ? serverHost : resolveServerHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalStateException("找不到服务器地址");
            }
        if (host == null) {
            VoiceCallMod.LOGGER.warn("[VoiceCall] 无法确定服务器地址，语音隧道不使用");
            return;
        }

        // ---- 客户端手动覆盖（用于 FRP 穿透：外网 UDP 端口和本地端口不一致）----
        String[] override = readOverride();
        String overrideHost = override[0];
        int overridePort = Integer.parseInt(override[1]);
        if (overrideHost != null && !overrideHost.isEmpty()) {
            host = overrideHost;
        }
        if (overridePort > 0) {
            port = overridePort;
        }
        if (port <= 0) {
            VoiceCallMod.LOGGER.info("[VoiceCall] 语音隧道端口为 0（服务端未开），走 MC 连接");
            return;
        }
        try {
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(500);
            socket = s;
            token = tokenBytes;
            targetPort = port;
            target = new InetSocketAddress(InetAddress.getByName(host), port);
            ready = false;
            lastAckMs = 0;

            receiver = new Thread(VoiceTunnelClient::receiveLoop, "VoiceCall-UdpClient");
            receiver.setDaemon(true);
            receiver.start();
            keeper = new Thread(VoiceTunnelClient::keepAliveLoop, "VoiceCall-UdpKeepAlive");
            keeper.setDaemon(true);
            keeper.start();

            VoiceCallMod.LOGGER.info("[VoiceCall] 独立语音隧道: 连接 {}:{}", host, port);
        } catch (Throwable t) {
            VoiceCallMod.LOGGER.warn("[VoiceCall] 语音隧道初始化失败: {}", t.getMessage());
            shutdown();
        }
    }

    /**
     * 读取客户端覆盖配置 config/voicecall-client.txt
     * <pre>
     *   tunnelHost=frp-pen.com
     *   tunnelPort=38999
     * </pre>
     *
     * @return {host(可为 null), port(0=不覆盖)}
     */
    private static String[] readOverride() {
        String host = null;
        int port = 0;
        try {
            java.nio.file.Path file = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("voicecall-client.txt");
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file,
                        "# 客户端设置（改完重新进服务器生效）\n"
                                + "# 独立语音隧道地址覆盖：留空 = 自动使用服务器地址\n"
                                + "# 内网穿透场景下，如果外网 UDP 端口和服务器本地端口不一致，填这两个\n"
                                + "tunnelHost=\n"
                                + "tunnelPort=0\n");
                return new String[]{null, "0"};
            }
            for (String raw : java.nio.file.Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                if ("usetunnel".equals(key)) {
                useTunnelEnabled = Boolean.parseBoolean(value);
            } else if ("tunnelhost".equals(key)) {
                    host = value;
                } else if ("tunnelport".equals(key)) {
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{host, String.valueOf(port)};
    }

    private static String resolveServerHost() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.hasSingleplayerServer()) {
                return "127.0.0.1";
            }
            ServerData data = mc.getCurrentServer();
            if (data == null || data.ip == null || data.ip.isEmpty()) {
                return null;
            }
            String ip = data.ip.trim();
            // 形如 [fe80::1]:25565 的 IPv6 字面量
            if (ip.startsWith("[")) {
                int close = ip.indexOf(']');
                if (close > 0) {
                    ip = ip.substring(1, close);
                }
            } else {
                // IPv4/域名 后面可能带 :端口（只去掉最后一个冒号且只剩一个冒号时）
                int first = ip.indexOf(':');
                int last = ip.lastIndexOf(':');
                if (first > 0 && first == last) {
                    ip = ip.substring(0, last);
                }
            }
            // IPv6 链路本地地址需要 zone id，直接交给 Java 可能失败；尝试只用 % 前的部分
            int pct = ip.indexOf('%');
            if (pct > 0) {
                ip = ip.substring(0, pct);
            }
            return ip;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 发送一帧音频；返回 false 表示隧道不可用（调用方退回 MC 连接） */
    public static boolean send(int flags, byte[] audio) {
        return send(flags, 0, audio);
    }

    public static boolean send(int flags, int frameSeq, byte[] audio) {
        DatagramSocket s = socket;
        InetSocketAddress t = target;
        byte[] tk = token;
        if (!ready || s == null || t == null || tk == null || s.isClosed()) {
            return false;
        }
        try {
            // 必须和服务端 TunnelProtocol.decodeC2S 格式一致：
            // [magic 4][token 8][flags 1][frameSeq 4][len 2][audio]
            byte[] packet = com.lmj.voicecall.net.TunnelProtocol.encodeC2S(tk, flags, frameSeq, audio);
            s.send(new DatagramPacket(packet, packet.length, t));
            return true;
        } catch (Throwable ex) {
            ready = false;
            return false;
        }
    }

    private static void keepAliveLoop() {
        while (socket != null && !socket.isClosed()) {
            try {
                if (!send(0, new byte[0])) {
                    // 未就绪也持续尝试注册
                    sendRawKeepAlive();
                }
                // 超过 5 秒没收到回执，认为隧道断了
                if (lastAckMs > 0 && System.currentTimeMillis() - lastAckMs > 5000L) {
                    ready = false;
                }
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void sendRawKeepAlive() {
        try {
            DatagramSocket s = socket;
            InetSocketAddress t = target;
            byte[] tk = token;
            if (s == null || t == null || tk == null) {
                return;
            }
            byte[] payload = com.lmj.voicecall.net.TunnelProtocol.encodeC2S(tk, 0, new byte[0]);
            s.send(new DatagramPacket(payload, payload.length, t));
        } catch (Throwable ignored) {
        }
    }

    private static void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (socket != null && !socket.isClosed()) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                int len = packet.getLength();
                if (len < 6) {
                    continue;
                }
                ByteBuffer in = ByteBuffer.wrap(buf, 0, len);
                int magic = in.getInt();
                if (magic == MAGIC_ACK) {
                    lastAckMs = System.currentTimeMillis();
                    if (!ready) {
                        ready = true;
                        VoiceCallMod.LOGGER.info("[VoiceCall] 独立语音隧道已连通 (udp {}:{})",
                                packet.getAddress().getHostAddress(), packet.getPort());
                    }
                } else if (magic == MAGIC_S2C) {
                    com.lmj.voicecall.net.TunnelProtocol.S2C msg =
                            com.lmj.voicecall.net.TunnelProtocol.decodeS2C(buf, len);
                    if (msg != null && msg.audio().length > 0) {
                        ClientVoiceManager.onRemoteAudioTunnel(msg.sender(), msg.seq(), msg.mode(),
                                msg.x(), msg.y(), msg.z(), msg.audio());
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // 正常
            } catch (Throwable t) {
                if (socket != null && !socket.isClosed()) {
                    VoiceCallMod.LOGGER.debug("[VoiceCall] 隧道收包异常: {}", t.getMessage());
                }
            }
        }
    }

    public static synchronized void shutdown() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
        }
        socket = null;
        ready = false;
        token = null;
        target = null;
        lastAckMs = 0;
        receiver = null;
        keeper = null;
    }
}
