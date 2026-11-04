package com.lmj.voicecall.client;

import com.lmj.voicecall.VoiceCallMod;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * RNNoise 神经网络降噪引擎（JNA 调用原生库）。
 * <p>
 * 要求：48kHz、480 样本一帧（10ms）。输入输出都是"int16 量级"的 float（±32768）。
 * 原生库首次使用时从 jar 释放到临时目录；加载失败会自动降级（不影响游戏）。
 */
public final class RnNoiseEngine {

    public static final int FRAME_SIZE = 480;
    public static final int SAMPLE_RATE = 48000;

    private static boolean tried = false;
    private static boolean available = false;
    private static RnNoiseLib lib;
    private static Pointer state;
    private static String lastError;

    /** JNA 接口 */
    interface RnNoiseLib extends Library {
        Pointer rnnoise_create(Pointer model);

        void rnnoise_destroy(Pointer state);

        float rnnoise_process_frame(Pointer state, float[] out, float[] in);

        int rnnoise_get_frame_size();
    }

    private RnNoiseEngine() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String lastError() {
        return lastError;
    }

    /** 初始化（幂等，失败后不再重试） */
    public static synchronized boolean init() {
        if (tried) {
            return available;
        }
        tried = true;
        try {
            Path dll = extractFromJar("/native/win-x64/rnnoise.dll", "voicecall-rnnoise");
            if (dll == null) {
                dll = extractFromJar("/native/rnnoise.dll", "voicecall-rnnoise");
            }
            if (dll == null) {
                lastError = "jar 里找不到 rnnoise.dll";
                VoiceCallMod.LOGGER.warn("[VoiceCall] RNNoise 不可用: {}", lastError);
                return false;
            }
            lib = Native.load(dll.toAbsolutePath().toString(), RnNoiseLib.class);
            int size = lib.rnnoise_get_frame_size();
            if (size != FRAME_SIZE) {
                lastError = "frame_size=" + size + "（预期 480）";
                VoiceCallMod.LOGGER.warn("[VoiceCall] RNNoise 帧长不符: {}", lastError);
                return false;
            }
            state = lib.rnnoise_create(null);
            if (state == null) {
                lastError = "rnnoise_create 返回 NULL";
                VoiceCallMod.LOGGER.warn("[VoiceCall] RNNoise 初始化失败: {}", lastError);
                return false;
            }
            available = true;
            VoiceCallMod.LOGGER.info("[VoiceCall] RNNoise 已启用（48kHz / 480样本帧） dll={}", dll);
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            VoiceCallMod.LOGGER.warn("[VoiceCall] RNNoise 加载失败（将使用传统降噪）: {}", lastError);
            return false;
        }
    }

    private static Path extractFromJar(String resource, String prefix) throws IOException {
        try (InputStream in = RnNoiseEngine.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Path dir = Files.createTempDirectory(prefix);
            Path target = dir.resolve("rnnoise.dll");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return target;
        }
    }

    /**
     * 处理一帧（480 样本）。
     *
     * @return 语音概率 0~1（RNNoise 的 VAD 输出）
     */
    public static float process(float[] out, float[] in) {
        if (!available || state == null) {
            System.arraycopy(in, 0, out, 0, Math.min(in.length, out.length));
            return 0f;
        }
        try {
            return lib.rnnoise_process_frame(state, out, in);
        } catch (Throwable t) {
            available = false;
            return 0f;
        }
    }

    public static synchronized void destroy() {
        if (lib != null && state != null) {
            try {
                lib.rnnoise_destroy(state);
            } catch (Throwable ignored) {
            }
        }
        state = null;
    }

    /** 重置状态（重新开始采集时调用） */
    public static synchronized void reset() {
        destroy();
        if (available && lib != null) {
            state = lib.rnnoise_create(null);
        }
    }
}
