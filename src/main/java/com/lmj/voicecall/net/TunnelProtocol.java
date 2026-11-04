package com.lmj.voicecall.net;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 独立 UDP 语音隧道协议（纯 Java，不依赖游戏类，方便单独测试）。
 *
 * <pre>
 * C2S 音频/保活： [magic 4][token 8][flags 1][len 2][audio]
 * S2C 音频：     [magic 4][uuid 16][seq 4][mode 1][x,y,z 12(仅近距)][len 2][audio]
 * ACK：          [magic 4][token 8]
 * </pre>
 */
public final class TunnelProtocol {

    public static final int MAGIC_C2S = 0x56434C31; // "VCL1"
    public static final int MAGIC_S2C = 0x56434C32; // "VCL2"
    public static final int MAGIC_ACK = 0x56434C33; // "VCL3"

    /** 频道音频（不限距离、不衰减） */
    public static final int MODE_CHANNEL = 1;
    /** 近距音频（客户端按坐标衰减） */
    public static final int MODE_PROXIMITY = 2;

    /** 解析出的 C2S 内容 */
    public record C2S(byte[] token, int flags, int frameSeq, byte[] audio) {
    }

    /** 解析出的 S2C 内容 */
    public record S2C(UUID sender, int seq, int mode, float x, float y, float z, byte[] audio) {
    }

    private TunnelProtocol() {
    }

    // ---------------- 编码 ----------------

    public static byte[] encodeC2S(byte[] token8, int flags, byte[] audio) {
        return encodeC2S(token8, flags, 0, audio);
    }

    public static byte[] encodeC2S(byte[] token8, int flags, int frameSeq, byte[] audio) {
        int len = audio == null ? 0 : audio.length;
        ByteBuffer out = ByteBuffer.allocate(4 + 8 + 1 + 4 + 2 + len);
        out.putInt(MAGIC_C2S);
        out.put(token8, 0, 8);
        out.put((byte) (flags & 0xFF));
        out.putInt(frameSeq);
        out.putShort((short) len);
        if (len > 0) {
            out.put(audio);
        }
        return out.array();
    }

    public static byte[] encodeAck(byte[] token8) {
        ByteBuffer out = ByteBuffer.allocate(4 + 8);
        out.putInt(MAGIC_ACK);
        out.put(token8, 0, 8);
        return out.array();
    }

    public static byte[] encodeS2C(UUID sender, int seq, int mode,
                                   float x, float y, float z, byte[] audio) {
        boolean prox = mode == MODE_PROXIMITY;
        int len = audio == null ? 0 : audio.length;
        ByteBuffer out = ByteBuffer.allocate(4 + 16 + 4 + 1 + (prox ? 12 : 0) + 2 + len);
        out.putInt(MAGIC_S2C);
        out.putLong(sender.getMostSignificantBits());
        out.putLong(sender.getLeastSignificantBits());
        out.putInt(seq);
        out.put((byte) mode);
        if (prox) {
            out.putFloat(x);
            out.putFloat(y);
            out.putFloat(z);
        }
        out.putShort((short) len);
        if (len > 0) {
            out.put(audio);
        }
        return out.array();
    }

    // ---------------- 解码 ----------------

    /** 返回 null 表示不是 C2S 包 */
    public static C2S decodeC2S(byte[] data, int len) {
        if (data == null || len < 4 + 8 + 7) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(data, 0, len);
        if (in.getInt() != MAGIC_C2S) {
            return null;
        }
        byte[] token = new byte[8];
        in.get(token);
        int flags = in.get() & 0xFF;
        int frameSeq = in.remaining() >= 4 ? in.getInt() : 0;
        int audioLen = in.getShort() & 0xFFFF;
        if (audioLen < 0 || audioLen > in.remaining()) {
            return null;
        }
        byte[] audio = new byte[audioLen];
        if (audioLen > 0) {
            in.get(audio);
        }
        return new C2S(token, flags, frameSeq, audio);
    }

    /** 读 magic（用于分发），不改动 pos 之外的判断 */
    public static int peekMagic(byte[] data, int len) {
        if (data == null || len < 4) {
            return 0;
        }
        return ByteBuffer.wrap(data, 0, 4).getInt();
    }

    /** 返回 null 表示不是 S2C 包 */
    public static S2C decodeS2C(byte[] data, int len) {
        if (data == null || len < 4 + 16 + 4 + 1 + 2) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(data, 0, len);
        if (in.getInt() != MAGIC_S2C) {
            return null;
        }
        long hi = in.getLong();
        long lo = in.getLong();
        UUID sender = new UUID(hi, lo);
        int seq = in.getInt();
        int mode = in.get() & 0xFF;
        float x = 0f, y = 0f, z = 0f;
        if (mode == MODE_PROXIMITY) {
            if (in.remaining() < 12 + 2) {
                return null;
            }
            x = in.getFloat();
            y = in.getFloat();
            z = in.getFloat();
        }
        int audioLen = in.getShort() & 0xFFFF;
        if (audioLen < 0 || audioLen > in.remaining()) {
            return null;
        }
        byte[] audio = new byte[audioLen];
        if (audioLen > 0) {
            in.get(audio);
        }
        return new S2C(sender, seq, mode, x, y, z, audio);
    }

    /** 距离衰减：near 内 1.0，far 外 0.0，中间线性平方 */
    public static float attenuation(double distance, double near, double far) {
        if (distance <= near) {
            return 1f;
        }
        if (distance >= far) {
            return 0f;
        }
        double linear = 1.0 - (distance - near) / (far - near);
        return (float) (linear * linear);
    }
}
