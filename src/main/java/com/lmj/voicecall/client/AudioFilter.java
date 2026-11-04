package com.lmj.voicecall.client;

import com.lmj.voicecall.VoiceCallMod;

/**
 * 音频处理：48kHz 原生采集 → 高通 → RNNoise(48k 原生) → 降采样到 16kHz 发送。
 * <p>
 * 关键点：RNNoise 原生工作在 48kHz，所以采集也直接 48k，
 * 避免"16k 升采样到 48k"产生的镜像频率被 RNNoise 误判成噪音（会掐掉语音，听感一顿一顿）。
 */
public final class AudioFilter {

    public static final int CAPTURE_RATE = 48000;
    public static final int OUTPUT_RATE = 16000;
    public static final int OUTPUT_FRAME_BYTES = OUTPUT_RATE / 50 * 2;

    private static final float HP_HZ = 110f;

    private static float hpPrevIn = 0f;
    private static float hpPrevOut = 0f;
    private static float hpAlpha = 0.99f;

    private static float[] in48 = new float[960];
    private static float[] block = new float[480];
    private static float[] blockOut = new float[480];
    private static byte[] out16 = new byte[OUTPUT_FRAME_BYTES];

    private static volatile boolean rnnoiseActive = false;

    private AudioFilter() {
    }

    public static void reset() {
        hpPrevIn = 0f;
        hpPrevOut = 0f;
        float rc = 1f / (2f * (float) Math.PI * HP_HZ);
        float dt = 1f / CAPTURE_RATE;
        hpAlpha = rc / (rc + dt);
        rnnoiseActive = RnNoiseEngine.init();
        if (rnnoiseActive) {
            VoiceCallMod.LOGGER.info("[VoiceCall] 音频处理: 48k采集 + RNNoise(48k) + 降采样16k");
        } else {
            VoiceCallMod.LOGGER.info("[VoiceCall] 音频处理: 48k采集（RNNoise 不可用: {}）", RnNoiseEngine.lastError());
        }
    }

    /**
     * 处理一帧 48k PCM（960 样本 = 1920 字节）。
     *
     * @return 16k PCM（320 样本 = 640 字节）
     */
    public static byte[] process(byte[] pcm48) {
        int samples = pcm48.length / 2;
        if (samples < 480) {
            return null;
        }
        if (in48.length < samples) {
            in48 = new float[samples];
        }
        for (int i = 0; i < samples; i++) {
            int idx = i * 2;
            short s = (short) ((pcm48[idx] & 0xFF) | (pcm48[idx + 1] << 8));
            float x = s;
            float y = hpAlpha * (hpPrevOut + x - hpPrevIn);
            hpPrevIn = x;
            hpPrevOut = y;
            in48[i] = y;
        }

        int blocks = samples / 480;
        if (rnnoiseActive) {
            try {
                for (int b = 0; b < blocks; b++) {
                    System.arraycopy(in48, b * 480, block, 0, 480);
                    RnNoiseEngine.process(blockOut, block);
                    System.arraycopy(blockOut, 0, in48, b * 480, 480);
                }
            } catch (Throwable t) {
                rnnoiseActive = false;
                VoiceCallMod.LOGGER.warn("[VoiceCall] RNNoise 处理异常，改为直通: {}", t.getMessage());
            }
        }

        int outLen = samples / 3;
        int need = outLen * 2;
        if (out16.length != need) {
            out16 = new byte[need];
        }
        for (int i = 0; i < outLen; i++) {
            float v = (in48[i * 3] + in48[i * 3 + 1] + in48[i * 3 + 2]) / 3f;
            int s = Math.round(v);
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            out16[i * 2] = (byte) (s & 0xFF);
            out16[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out16;
    }

    public static String state() {
        return "48k采集 RNNoise=" + rnnoiseActive;
    }

    // ---- 兼容接口 ----
    public static boolean isRnNoiseActive() {
        return rnnoiseActive;
    }

    public static float lastSpeechProbability() {
        return 0f;
    }

    public static boolean isVadGate() {
        return false;
    }
}
