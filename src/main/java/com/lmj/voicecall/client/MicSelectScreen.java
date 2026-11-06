package com.lmj.voicecall.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 麦克风选择界面：列出所有可录制的设备，点一下切换。
 */
public class MicSelectScreen extends Screen {

    private final Screen parent;
    private List<String> devices;

    public MicSelectScreen(Screen parent) {
        super(Component.literal("选择麦克风"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.devices = MicCapture.listDevices();
        int y = 44;
        for (String dev : devices) {
            boolean current = dev.equals(MicCapture.getDeviceName());
            String label = (current ? "§a▶ " : "§7") + shortName(dev);
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                MicCapture.setDeviceName(dev);
                MicCapture.saveDeviceToConfig();
                Minecraft.getInstance().setScreen(null);
            }).bounds(this.width / 2 - 160, y, 320, 20).build());
            y += 24;
        }

        this.addRenderableWidget(Button.builder(Component.literal("§7使用系统默认"), b -> {
            MicCapture.setDeviceName("");
            MicCapture.saveDeviceToConfig();
            Minecraft.getInstance().setScreen(null);
        }).bounds(this.width / 2 - 160, Math.min(this.height - 56, y + 8), 320, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b ->
                Minecraft.getInstance().setScreen(parent))
                .bounds(this.width / 2 - 50, this.height - 32, 100, 20).build());
    }

    private static String shortName(String dev) {
        if (dev.length() > 40) {
            return dev.substring(0, 38) + "…";
        }
        return dev;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal("§7当前: " + shortName(MicCapture.getDeviceName().isEmpty() ? "系统默认" : MicCapture.getDeviceName())),
                this.width / 2, 30, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
