package com.lmj.voicecall;

/**
 * 音频格式常量（客户端采集/播放、服务端假人都用这一份）。
 */
public final class VoiceConstants {

    /** 采样率 */
    public static final float SAMPLE_RATE = 16000f;
    /** 每帧时长（毫秒） */
    public static final int FRAME_MS = 20;
    /** 每帧采样数 */
    public static final int FRAME_SAMPLES = (int) (SAMPLE_RATE * FRAME_MS / 1000f);
    /** 每帧字节数（16bit 单声道） */
    public static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    private VoiceConstants() {
    }
}
