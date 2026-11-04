package com.lmj.voicecall.server;

import com.lmj.voicecall.VoiceCallMod;
import com.lmj.voicecall.net.VoiceNetwork;
import com.lmj.voicecall.net.packet.ChannelStatePacket;
import com.lmj.voicecall.net.packet.TalkBroadcastPacket;
import com.lmj.voicecall.net.packet.VoiceReceivePacket;
import com.lmj.voicecall.net.packet.VoiceTunnelInfoPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端：频道成员表 + 音频转发 + 说话状态广播
 */
public final class ServerChannelManager {

    /** 玩家 -> 频道号 */
    private static final Map<UUID, Integer> CHANNELS = new ConcurrentHashMap<>();
    /** 正在说话的玩家 */
    private static final Map<UUID, Boolean> TALKING = new ConcurrentHashMap<>();
    /** 频道占线锁（纯 Java 实现，便于测试） */
    private static final TalkFloor FLOOR = new TalkFloor(400L);



    /** 占线超时（毫秒）：说话停顿超过这个时间自动释放 */

    /** 尝试占用频道说话权，返回 true 表示可以发 */
    /** 隧道层调用：尝试占用频道说话权（对讲机模式） */
    public static boolean takeFloor(int channel, UUID speaker) {
        return tryTakeFloor(channel, speaker);
    }

    private static boolean tryTakeFloor(int channel, UUID speaker) {
        return FLOOR.tryTake(channel, speaker);
    }

    /** 释放占线（该玩家说完/离开频道） */
    private static void releaseFloor(int channel, UUID speaker) {
        FLOOR.release(channel, speaker);
    }



    /** 虚拟成员（假人）：UUID -> (名字, 频道) */
    private static final Map<UUID, VirtualMember> VIRTUALS = new ConcurrentHashMap<>();

    /** 虚拟成员记录 */
    public record VirtualMember(String name, int channel) {
    }

    /** 虚拟成员说话状态：UUID -> 是否在说话 */
    private static final Map<UUID, Boolean> VIRTUAL_TALKING = new ConcurrentHashMap<>();

    /** 设置假人说话状态并刷新频道成员列表 */
    public static void setVirtualTalking(UUID id, boolean talking) {
        if (talking) {
            VIRTUAL_TALKING.put(id, Boolean.TRUE);
        } else {
            VIRTUAL_TALKING.remove(id);
        }
        VirtualMember v = VIRTUALS.get(id);
        if (v != null) {
            refreshMembersVirtual(v.channel());
        }
    }


    /** 每个说话者的音频序号（用于客户端去重/排序） */
    private static final Map<UUID, Integer> SEND_SEQ = new ConcurrentHashMap<>();

    /** 语音隧道令牌（hex）-> 玩家 */
    private static final Map<String, UUID> TOKENS = new ConcurrentHashMap<>();
    /** 独立语音隧道开关与端口 */
    private static volatile boolean tunnelEnabled = true;
    private static volatile int tunnelPort = 24455;

    /** 对外（穿透后）的 UDP 端口：0 表示与本地端口相同 */
    private static volatile int tunnelPublicPort = 0;

    private ServerChannelManager() {
    }

    // ---------------- 独立语音隧道 ----------------

    public static UUID tokenOwner(String token) {
        return TOKENS.get(token);
    }

    public static Integer channelOf(UUID player) {
        return CHANNELS.get(player);
    }

    /** 给玩家分配令牌并下发隧道信息（端口 0 表示隧道不可用） */
    private static void sendTunnelInfo(ServerPlayer player) {
        int port = VoiceTunnelServer.isRunning() ? VoiceTunnelServer.getPort() : 0;
        if (port > 0 && tunnelPublicPort > 0) {
            port = tunnelPublicPort;   // 客户端要连的是穿透后的对外端口
        }
        if (port <= 0) {
            // 隧道没开就不发任何包，保证和老版本客户端完全兼容
            return;
        }
        byte[] token = new byte[8];
        new Random().nextBytes(token);
        String hex = toHex(token);
        TOKENS.put(hex, player.getUUID());
        VoiceNetwork.sendToPlayer(player, new VoiceTunnelInfoPacket(advertiseHost(), port, token));
    }

    /** 广告给客户端的隧道地址：优先本机局域网 IPv4 */
    private static String advertiseHost() {
        try {
            java.util.List<java.net.Inet4Address> candidates = new java.util.ArrayList<>();
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (java.net.InetAddress addr : java.util.Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        candidates.add((java.net.Inet4Address) addr);
                    }
                }
            }
            for (java.net.Inet4Address addr : candidates) {
                String ip = addr.getHostAddress();
                if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                    return ip;
                }
            }
            if (!candidates.isEmpty()) {
                return candidates.get(0).getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------------- 频道管理 ----------------

    public static void join(ServerPlayer player, int channel) {
        CHANNELS.put(player.getUUID(), channel);
        syncChannel(player);
        refreshMembers(player.server, channel);
        VoiceCallMod.LOGGER.info("[VoiceCall] {} joined channel {}",
                player.getGameProfile().getName(), channel);
        player.displayClientMessage(
                Component.literal("§a[通话] 已加入频道 " + channel)
                        .withStyle(ChatFormatting.GREEN), true);
    }

    public static void leave(ServerPlayer player) {
        Integer oldCh = CHANNELS.get(player.getUUID());
        if (oldCh != null && oldCh >= 0) {
            releaseFloor(oldCh, player.getUUID());
        }
        Integer old = CHANNELS.remove(player.getUUID());
        if (old != null) {
            refreshMembers(player.server, old);
            VoiceCallMod.LOGGER.info("[VoiceCall] {} left channel {}",
                    player.getGameProfile().getName(), old);
        }
        setTalking(player, false);
        VoiceNetwork.sendToPlayer(player, new ChannelStatePacket(-1, List.of()));
        player.displayClientMessage(Component.literal("§7[通话] 已退出频道"), true);
    }

    public static int getChannel(ServerPlayer player) {
        return CHANNELS.getOrDefault(player.getUUID(), -1);
    }

    public static int countMembers(MinecraftServer server, int channel) {
        int base = countRealMembers(server, channel);
        for (VirtualMember v : VIRTUALS.values()) {
            if (v.channel() == channel) {
                base++;
            }
        }
        return base;
    }

    private static int countRealMembers(MinecraftServer server, int channel) {
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (getChannel(p) == channel) {
                n++;
            }
        }
        return n;
    }

    /** 频道内所有玩家名字 */
    /** 频道成员列表（真人 + 假人），带说话状态 */
    public static List<com.lmj.voicecall.net.packet.ChannelStatePacket.Member> memberInfos(MinecraftServer server, int channel) {
        List<com.lmj.voicecall.net.packet.ChannelStatePacket.Member> list = new ArrayList<>();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (getChannel(p) == channel) {
                    boolean talking = TALKING.getOrDefault(p.getUUID(), false);
                    list.add(new com.lmj.voicecall.net.packet.ChannelStatePacket.Member(
                            p.getGameProfile().getName(), talking));
                }
            }
        }
        for (Map.Entry<UUID, VirtualMember> e : VIRTUALS.entrySet()) {
            VirtualMember v = e.getValue();
            if (v.channel() == channel) {
                boolean talking = VIRTUAL_TALKING.getOrDefault(e.getKey(), Boolean.FALSE);
                list.add(new com.lmj.voicecall.net.packet.ChannelStatePacket.Member(v.name(), talking));
            }
        }
        return list;
    }

    public static List<String> memberNames(MinecraftServer server, int channel) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (getChannel(p) == channel) {
                names.add(p.getGameProfile().getName() + (TALKING.containsKey(p.getUUID()) ? "（说话中）" : ""));
            }
        }
        return names;
    }

    public static void syncChannel(ServerPlayer player) {
        int ch = getChannel(player);
        int n = ch < 0 ? 0 : countMembers(player.server, ch);
        VoiceNetwork.sendToPlayer(player, new ChannelStatePacket(ch,
                ch < 0 ? List.of() : memberInfos(player.server, ch)));
    }

    private static void refreshMembers(MinecraftServer server, int channel) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (getChannel(p) == channel) {
                syncChannel(p);
            }
        }
    }

    /** 注册虚拟成员（假人）到某个频道 */
    public static void addVirtual(UUID id, String name, int channel) {
        VIRTUALS.put(id, new VirtualMember(name, channel));
        refreshMembersVirtual(channel);
    }

    /** 把某个虚拟成员移动到另一个频道 */
    public static void moveVirtual(UUID id, String keepName, int channel) {
        VirtualMember v = VIRTUALS.get(id);
        if (v == null) {
            return;
        }
        VIRTUALS.put(id, new VirtualMember(v.name(), channel));
        refreshMembersVirtual(v.channel());
        if (channel >= 0) {
            refreshMembersVirtual(channel);
        }
    }

    /** 清除所有虚拟成员 */
    public static void clearVirtuals() {
        List<Integer> channels = new ArrayList<>();
        for (VirtualMember v : VIRTUALS.values()) {
            channels.add(v.channel());
        }
        VIRTUALS.clear();
        VIRTUAL_TALKING.clear();
        for (int ch : channels) {
            refreshMembersVirtual(ch);
        }
    }

    private static void refreshMembersVirtual(int channel) {
        for (ServerPlayer p : getOnline()) {
            if (getChannel(p) == channel) {
                syncChannel(p);
            }
        }
    }

    private static List<ServerPlayer> getOnline() {
        List<ServerPlayer> list = new ArrayList<>();
        if (lastServer != null) {
            list.addAll(lastServer.getPlayerList().getPlayers());
        }
        return list;
    }

    // ---------------- 音频转发 ----------------

    /** 自由麦（近距）默认半径，单位：格 */
    private static volatile double proximityRange = 16.0D;
    private static volatile long configLoadedAt = -1L;

    public static void reloadConfig() {
        try {
            java.nio.file.Path file = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("voicecall-server.txt");
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file,
                        "# 服务端设置（改完重启世界/服务器生效）\n"
                                + "# 自由麦（近距语音）有效半径，单位格\n"
                                + "proximityRange=16\n"
                                + "# 独立语音隧道（不占用 Minecraft 连接）：需要额外开放这个 UDP 端口\n"
                                + "voiceTunnel=true\n"
                                + "voicePort=24455\n"
                                + "# 穿透后对外暴露的 UDP 端口（0 = 和 voicePort 相同）\n"
                                + "# 例：樱花把 远程 25565 映射到 本地 24455，这里就填 25565\n"
                                + "voicePublicPort=0\n");
                return;
            }
            long mtime = java.nio.file.Files.getLastModifiedTime(file).toMillis();
            if (mtime == configLoadedAt) {
                return;
            }
            configLoadedAt = mtime;
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
                if ("proximityrange".equals(key)) {
                    try {
                        proximityRange = Math.max(4.0D, Math.min(256.0D, Double.parseDouble(value)));
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("voicetunnel".equals(key)) {
                    tunnelEnabled = Boolean.parseBoolean(value);
                } else if ("voiceport".equals(key)) {
                    try {
                        tunnelPort = Math.max(1024, Math.min(65535, Integer.parseInt(value)));
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("voicepublicport".equals(key)) {
                    try {
                        int v = Integer.parseInt(value);
                        tunnelPublicPort = v <= 0 ? 0 : Math.max(1024, Math.min(65535, v));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            VoiceCallMod.LOGGER.info("[VoiceCall] 配置: 自由麦半径={}格  语音隧道={} (本地 udp {} -> 对外 {})",
                    proximityRange, tunnelEnabled, tunnelPort,
                    tunnelPublicPort > 0 ? tunnelPublicPort : tunnelPort);
        } catch (Exception ignored) {
        }
    }

    public static double getProximityRange() {
        reloadConfig();
        return proximityRange;
    }

    /**
     * 按投递标记转发音频。
     *
     * @param flags bit0 = 频道（同频道不限距离），bit1 = 自由麦（附近玩家）
     */
    public static void relayAudio(ServerPlayer from, byte[] data, int flags) {
        relayAudio(from, data, flags, nextSeq(from.getUUID()));
    }

    /** @param frameSeq 客户端帧号（隧道/MC 两条路共用，便于接收端去重） */
    public static void relayAudio(ServerPlayer from, byte[] data, int flags, int frameSeq) {
        Integer channelBox = CHANNELS.get(from.getUUID());
        int channel = channelBox == null ? -1 : channelBox;
        boolean toChannel = (flags & 1) != 0 && channel >= 0;
        boolean toProximity = (flags & 2) != 0;

        if (!toChannel && !toProximity) {
            return;
        }

        int seq = frameSeq;
        var fromVec = from.position();
        VoiceReceivePacket channelPacket = new VoiceReceivePacket(from.getUUID(), seq,
                VoiceReceivePacket.MODE_CHANNEL, 0f, 0f, 0f, data);
        VoiceReceivePacket proximityPacket = new VoiceReceivePacket(from.getUUID(), seq,
                VoiceReceivePacket.MODE_PROXIMITY,
                (float) fromVec.x, (float) fromVec.y, (float) fromVec.z, data);
        double range = toProximity ? getProximityRange() : 0.0D;
        double rangeSq = range * range;
        var serverLevel = from.serverLevel();
        var fromPos = from.position();
        java.util.List<String> listenerNames = new java.util.ArrayList<>();
        for (ServerPlayer target : from.server.getPlayerList().getPlayers()) {
            if (target == from) {
                continue;
            }
            // 频道：同频道、不限距离（不衰减）
            if (toChannel && channel == getChannel(target)) {
                VoiceNetwork.sendToPlayer(target, channelPacket);
                listenerNames.add(target.getGameProfile().getName());
                continue;
            }
            // 自由麦：同一维度内、距离足够近（客户端按距离做近大远小）
            if (toProximity && target.level() == serverLevel
                    && target.distanceToSqr(fromPos) <= rangeSq) {
                VoiceNetwork.sendToPlayer(target, proximityPacket);
                listenerNames.add(target.getGameProfile().getName());
            }
        }
    }
    /**
     * 与 relayAudio 相同规则，但跳过"已经通过 UDP 隧道收到"的玩家。
     * 隧道不通时（客户端 UDP 初始化失败/防火墙拦截）自动退回 MC 转发。
     */
    public static void relayAudioMcExcept(ServerPlayer from, byte[] data, int flags, int frameSeq,
                                          java.util.Set<UUID> skip) {
        Integer channelBox = CHANNELS.get(from.getUUID());
        int channel = channelBox == null ? -1 : channelBox;
        boolean toChannel = (flags & 1) != 0 && channel >= 0;
        boolean toProximity = (flags & 2) != 0;
        if (!toChannel && !toProximity) {
            return;
        }
        var fromVec = from.position();
        VoiceReceivePacket channelPacket = new VoiceReceivePacket(from.getUUID(), frameSeq,
                VoiceReceivePacket.MODE_CHANNEL, 0f, 0f, 0f, data);
        VoiceReceivePacket proximityPacket = new VoiceReceivePacket(from.getUUID(), frameSeq,
                VoiceReceivePacket.MODE_PROXIMITY,
                (float) fromVec.x, (float) fromVec.y, (float) fromVec.z, data);
        double range = toProximity ? getProximityRange() : 0.0D;
        double rangeSq = range * range;
        var serverLevel = from.serverLevel();
        for (ServerPlayer target : from.server.getPlayerList().getPlayers()) {
            if (target == from || skip.contains(target.getUUID())) {
                continue;
            }
            if (toChannel && channel == getChannel(target)) {
                VoiceNetwork.sendToPlayer(target, channelPacket);
                continue;
            }
            if (toProximity && target.level() == serverLevel
                    && target.distanceToSqr(fromVec) <= rangeSq) {
                VoiceNetwork.sendToPlayer(target, proximityPacket);
            }
        }
    }

    /**
     * 假人音频转发：和真人走同一套规则（同频道不限距离 / 近距按半径），
     * 保证测试链路与真实情况一致。
     */
    public static void relayFromVirtual(UUID fromId, net.minecraft.server.level.ServerLevel level,
                                        double x, double y, double z,
                                        byte[] data, int flags) {
        VirtualMember self = VIRTUALS.get(fromId);
        int ch = self == null ? -1 : self.channel();
        boolean toChannel;
        boolean toProximity;
        if (ch >= 0) {
            // 在频道：只走频道（参与对讲机占线），不走近距
            // 否则被占线挡住后还会从近距漏出去，导致"两个人同时在说话"
            if (!tryTakeFloor(ch, fromId)) {
                return;
            }
            toChannel = true;
            toProximity = false;
        } else {
            // 不在频道：只走近距（带坐标做近大远小）
            toChannel = false;
            toProximity = true;
        }
        int seq = nextSeq(fromId);
        VoiceReceivePacket channelPacket = new VoiceReceivePacket(fromId, seq,
                VoiceReceivePacket.MODE_CHANNEL, 0f, 0f, 0f, data);
        VoiceReceivePacket proximityPacket = new VoiceReceivePacket(fromId, seq,
                VoiceReceivePacket.MODE_PROXIMITY, (float) x, (float) y, (float) z, data);
        double range = toProximity ? getProximityRange() : 0.0D;
        double rangeSq = range * range;
        MinecraftServer srv = lastServer;
        if (srv == null) {
            return;
        }
        for (ServerPlayer target : srv.getPlayerList().getPlayers()) {
            if (toChannel && getChannel(target) == ch) {
                VoiceNetwork.sendToPlayer(target, channelPacket);
                continue;
            }
            if (toProximity && target.level() == level) {
                double dx = target.getX() - x;
                double dy = target.getY() - y;
                double dz = target.getZ() - z;
                if (dx * dx + dy * dy + dz * dz <= rangeSq) {
                    VoiceNetwork.sendToPlayer(target, proximityPacket);
                }
            }
        }
    }


    /** 取该玩家下一帧的序号 */
    public static int nextSeq(UUID player) {
        return SEND_SEQ.merge(player, 1, Integer::sum);
    }

    // ---------------- 说话状态 ----------------

    public static void setTalking(ServerPlayer player, boolean talking, boolean channel) {
        boolean mode = talking && channel;
        Boolean old = talking ? TALKING.put(player.getUUID(), mode) : TALKING.remove(player.getUUID());
        if (old != null && old == mode && talking) {
            return;
        }
        if (old == null && !talking) {
            return;
        }
        TalkBroadcastPacket packet = new TalkBroadcastPacket(player.getUUID(), talking, mode);
        if (talking) {
            // 动画全服可见（包括自己第三人称、频道外的人）。
            // 右上角名字列表仍由 ChannelStatePacket 控制，只显示同频道成员，不会串名字。
            if (mode) {
                VoiceNetwork.sendToAll(packet);
            }
            refreshChannelState(player);
        } else {
            // 停止说话：全服发一遍，保证任何频道的人都能清掉状态
            VoiceNetwork.sendToAll(packet);
            refreshChannelState(player);
        }
    }

    /** 刷新同频道玩家的成员列表（说话高亮） */
    private static void refreshChannelState(ServerPlayer player) {
        int ch = getChannel(player);
        if (ch < 0 || player.server == null) {
            return;
        }
        for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
            if (getChannel(p) == ch) {
                syncChannel(p);
            }
        }
    }


    public static void setTalking(ServerPlayer player, boolean talking) {
        setTalking(player, talking, true);
    }

    // ---------------- 事件 ----------------

    /** 供虚拟成员刷新用 */
    private static volatile MinecraftServer lastServer = null;

    /** 服务器/世界启动：开 UDP 语音隧道 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reloadConfig();
        lastServer = event.getServer();
        if (!tunnelEnabled) {
            VoiceCallMod.LOGGER.info("[VoiceCall] 语音隧道已关闭（voiceTunnel=false），走 MC 连接传输");
            return;
        }
        VoiceTunnelServer.start(event.getServer(), tunnelPort);
    }

    /** 服务器/世界关闭：释放端口 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VoiceTunnelServer.stop();
        TOKENS.clear();
    }

    /** 登录时下发状态（重连/进服后 HUD 能显示） */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncChannel(player);
            sendTunnelInfo(player);
        }
    }

    /** 重生/换维度后重新同步 */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncChannel(player);
        }
    }

    /** 从末地回来 / 传送跨维度后同步 */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncChannel(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Integer old = CHANNELS.remove(player.getUUID());
            boolean wasTalking = TALKING.remove(player.getUUID()) != null;
            if (wasTalking) {
                VoiceNetwork.sendToAll(new TalkBroadcastPacket(player.getUUID(), false, false));
            }
            TOKENS.entrySet().removeIf(e -> e.getValue().equals(player.getUUID()));
            if (old != null) {
                refreshMembers(player.server, old);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!TALKING.isEmpty()) {
            for (UUID id : new ArrayList<>(TALKING.keySet())) {
                ServerPlayer p = event.getServer().getPlayerList().getPlayer(id);
                if (p == null) {
                    TALKING.remove(id);
                    VoiceNetwork.sendToAll(new TalkBroadcastPacket(id, false, false));
                }
            }
        }
    }
}
