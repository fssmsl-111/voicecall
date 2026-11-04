package com.lmj.voicecall.client;

import com.lmj.voicecall.net.VoiceNetwork;
import com.lmj.voicecall.net.packet.JoinChannelPacket;
import com.lmj.voicecall.net.packet.LeaveChannelPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 频道面板：按 C 打开，输入频道号加入
 */
public class ChannelScreen extends Screen {

    private EditBox input;

    public ChannelScreen() {
        super(Component.literal("通话频道"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        String initial = ClientVoiceManager.getChannel() >= 0
                ? String.valueOf(ClientVoiceManager.getChannel())
                : "1";

        this.input = new EditBox(this.font, cx - 60, cy - 45, 120, 20, Component.literal("频道号"));
        this.input.setMaxLength(3);
        this.input.setValue(initial);
        this.input.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
        this.input.setFocused(true);
        this.addRenderableWidget(this.input);

        this.addRenderableWidget(Button.builder(
                        Component.literal("加入频道"),
                        b -> join())
                .bounds(cx - 120, cy - 10, 115, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("退出频道"),
                        b -> {
                            VoiceNetwork.sendToServer(new LeaveChannelPacket());
                            this.onClose();
                        })
                .bounds(cx + 5, cy - 10, 115, 20).build());

        this.addRenderableWidget(Button.builder(
                        Component.literal("关闭"),
                        b -> this.onClose())
                .bounds(cx - 60, cy + 30, 120, 20).build());
    }

    private void join() {
        String text = this.input == null ? "" : this.input.getValue().trim();
        int channel;
        try {
            channel = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return;
        }
        if (channel < 0 || channel > 999) {
            return;
        }
        VoiceNetwork.sendToServer(new JoinChannelPacket(channel));
        this.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            join();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int cx = this.width / 2;
        int cy = this.height / 2;

        graphics.drawCenteredString(this.font, "§l通话频道", cx, cy - 80, 0xFFFFFF);
        int current = ClientVoiceManager.getChannel();
        String state = current < 0
                ? "§7当前：未加入频道"
                : "§a当前：频道 " + current + "  §7(" + ClientVoiceManager.getMembers() + "人)";
        graphics.drawCenteredString(this.font, state, cx, cy - 62, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "§8输入频道号后按 Enter 或点「加入频道」", cx, cy - 20, 0x888888);

        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
