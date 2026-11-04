package com.lmj.voicecall.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD：屏幕左下角显示
 * <ul>
 *   <li>频道号 / 成员数 / 麦克风模式</li>
 *   <li><b>正在说话的玩家名字</b></li>
 *   <li>电平条</li>
 * </ul>
 */
public final class VoiceHud {

    private static final int BAR_WIDTH = 70;

    private VoiceHud() {
    }

    public static void render(GuiGraphics graphics, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        int channel = ClientVoiceManager.getChannel();
        boolean freeMic = ClientVoiceManager.isFreeMic();
        boolean ptt = ClientVoiceManager.isChannelTalking();
        boolean prox = ClientVoiceManager.isProximityTalking();
        boolean micOn = ClientVoiceManager.isMicOn();
        String error = ClientVoiceManager.getError();

        if (channel < 0 && !freeMic && error == null) {
            return;
        }

        int x = 6;
        int y = graphics.guiHeight() - 44;

        String line;
        int color;
        if (error != null) {
            line = "§c麦克风错误";
            color = 0xFFFF8080;
        } else if (ptt) {
            line = "§a● 频道 " + channel + " 说话中" + (freeMic ? "  §7[自由麦] " : "");
            color = 0xFFA0FFA0;
        } else if (prox) {
            line = "§e● 自由麦（近距）";
            color = 0xFFFFE080;
        } else if (freeMic) {
            line = "§7自由麦已开（M 关闭）";
            color = 0xFFAAAAAA;
        } else if (channel >= 0) {
            line = "§7频道 " + channel + "（按住 ~ 说话）";
            color = 0xFFAAAAAA;
        } else {
            line = "§7按 V 加入频道";
            color = 0xFFAAAAAA;
        }

        List<String> talkingNames = collectTalkingNames(mc);

        int textWidth = mc.font.width(line.replaceAll("§.", ""));
        int width = textWidth + 10;
        int height = 13;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height, 0x88000000);
        graphics.drawString(mc.font, line, x + 5, y + 3, color, true);

        // 电平条
        if (micOn) {
            int barY = y + height + 2;
            float level = ClientVoiceManager.micLevel();
            int filled = (int) (level * BAR_WIDTH);
            graphics.fill(x, barY, x + BAR_WIDTH, barY + 4, 0x88000000);
            int barColor = filled > BAR_WIDTH * 0.85f ? 0xFFFF5050
                    : filled > BAR_WIDTH * 0.5f ? 0xFFFFD040
                    : 0xFF60FF60;
            if (filled > 0) {
                graphics.fill(x, barY, x + filled, barY + 4, barColor);
            }
        }

        if (error != null) {
            String line2 = "§c麦克风错误: " + error;
            int w2 = mc.font.width(line2.replaceAll("§.", "")) + 12;
            int errY = y + height + 6;
            graphics.fill(x - 1, errY, x + w2 + 1, errY + 13, 0x88000000);
            graphics.drawString(mc.font, line2, x + 5, errY + 2, 0xFFFF8080, true);
        }

        // ---------------- 右上角：频道成员名单 ----------------
        renderMemberList(graphics, mc, channel, talkingNames);
    }

    /** 左上角显示当前频道成员（说话中的高亮） */
    private static void renderMemberList(GuiGraphics graphics, Minecraft mc,
                                         int channel, List<String> talkingNames) {
        if (channel < 0) {
            return;
        }
        List<com.lmj.voicecall.net.packet.ChannelStatePacket.Member> members = ClientVoiceManager.getMemberInfos();
        String selfName = mc.player != null ? mc.player.getName().getString() : "";
        String title = "§b频道 " + channel + "  §7(" + members.size() + "人)";

        int lineH = 11;
        int lines = members.size();
        int height = 14 + Math.max(1, lines) * lineH + 2;

        // 计算宽度
        int width = mc.font.width(title.replaceAll("§.", "")) + 12;
        for (com.lmj.voicecall.net.packet.ChannelStatePacket.Member m : members) {
            String display = m.name() + (m.name().equals(selfName) ? "（你）" : "");
            width = Math.max(width, mc.font.width(display) + 22);
        }
        // 右上角
        int px = graphics.guiWidth() - width - 6;
        int py = 6;
        graphics.fill(px - 1, py - 1, px + width, py + height, 0x88000000);
        graphics.drawString(mc.font, title, px + 5, py + 3, 0xFFB0E0FF, true);

        if (lines == 0) {
            graphics.drawString(mc.font, "§7(空)", px + 5, py + 16, 0xFF888888, true);
            return;
        }
        for (int i = 0; i < lines; i++) {
            com.lmj.voicecall.net.packet.ChannelStatePacket.Member m = members.get(i);
            String name = m.name();
            boolean self = name.equals(selfName);
            boolean talking = m.talking();
            String text = (talking ? "§a▶ " : "§8· ") + (talking ? "§f" : "§7")
                    + name + (self ? "§8（你）" : "");
            graphics.drawString(mc.font, text, px + 5, py + 16 + i * lineH, 0xFFFFFFFF, true);
        }
    }

    /** 收集正在说话的玩家名字（只统计频道麦），按"谁先开口"排序（自己标"你"） */
    private static List<String> collectTalkingNames(Minecraft mc) {
        List<String> names = new ArrayList<>();
        if (mc.level == null) {
            return names;
        }
        // 记录 (名字, 开始说话时间)
        List<String[]> entries = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            // 只有频道麦说话才显示名字；自由麦（近距）不显示
            if (!TalkAnimations.isChannelTalking(p.getUUID())) {
                continue;
            }
            boolean self = p == mc.player;
            String name = p.getName().getString() + (self ? "（你）" : "");
            entries.add(new String[]{name});
            starts.add(TalkAnimations.talkStart(p.getUUID()));
        }
        // 本地自己（频道麦）说话但没收到广播时兜底
        if (ClientVoiceManager.isChannelTalking()) {
            boolean already = false;
            for (String[] e : entries) {
                if (e[0].startsWith(mc.player.getName().getString())) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                entries.add(new String[]{mc.player.getName().getString() + "（你）"});
                starts.add(Long.MIN_VALUE + 1);   // 兜底放最前
            }
        }
        // 按开始说话时间升序（先说话的在前）
        Integer[] idx = new Integer[entries.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Long.compare(starts.get(a), starts.get(b)));
        for (int i : idx) {
            names.add(entries.get(i)[0]);
        }
        return names;
    }
}
