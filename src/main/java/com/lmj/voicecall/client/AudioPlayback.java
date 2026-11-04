package com.lmj.voicecall.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 音频播放：把远端各路声源混音后写入声卡。
 * <p>
 * 一帧 = 20ms（320 采样点 / 640 字节）。
 * 每个发送者有独立的缓冲队列 + 预缓冲，按 20ms 节拍严格出帧，
 * 近距声源按坐标做"近大远小"衰减，频道声源不衰减。
 */
public final class AudioPlayback {

    private static final AudioFormat FORMAT =
            new AudioFormat(MicCapture.SAMPLE_RATE, 16, 1, true, false);
    /** 一帧的时长（纳秒） */
    private static final long FRAME_NANOS = 20_000_000L;
    /** 声源多久没更新就丢弃（毫秒） */
    private static final long SOURCE_TIMEOUT_MS = 400L;
    /** 播放线缓冲帧数（越小延迟越低，太小会爆音） */
    private static volatile int lineBufferFrames = 2;
    /** 每路声源预缓冲帧数（吸收网络抖动；越小延迟越低，越容易断续） */
    private static volatile int prebufferFrames = 1;
    /** 近大远小：near 格内满音量，far 格外听不到 */
    private static volatile double PROX_NEAR = 3.0D;
    private static volatile double PROX_FAR = 16.0D;

    /** 复用缓冲（单播放线程使用） */
    private static final byte[] SILENCE = new byte[MicCapture.FRAME_BYTES];
    private static int[] MIX_ACC = new int[MicCapture.FRAME_BYTES / 2];
    private static byte[] MIX_OUT = new byte[MicCapture.FRAME_BYTES];

    private record Frame(byte[] pcm, long captureNanos) {
    }

    /** 每个声源的缓冲队列 + 增益 */
    private static final class Source {
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        volatile byte[] lastPcm;
        volatile long updateMs;
        /** 1=频道（不衰减） 2=近距（按坐标衰减） */
        volatile int mode = 1;
        volatile float targetGain = 1f;
        float gain = 1f;
        /** 是否已攒够预缓冲开始播放 */
        private boolean started = false;
        /** 补帧计数：连续补帧时逐帧淡出 */
        private int holdCount = 0;

        Source(byte[] pcm, long updateMs) {
            push(pcm, updateMs);
        }

        void push(byte[] pcm, long now) {
            synchronized (queue) {
                while (queue.size() >= 4) {
                    queue.pollFirst();
                    diagOverflow++;
                }
                queue.addLast(pcm);
            }
            lastPcm = pcm;
            updateMs = now;
        }

        byte[] poll(long now) {
            synchronized (queue) {
                if (!started) {
                    if (queue.size() < Math.max(1, prebufferFrames)) {
                        return null;
                    }
                    started = true;
                }
                byte[] f = queue.pollFirst();
                if (f != null) {
                    holdCount = 0;
                    return f;
                }
            }
            // 队列空了：沿用上一帧并淡出（最多 8 帧 = 160ms）
            byte[] l = lastPcm;
            if (l != null && now - updateMs < 300L && holdCount < 8) {
                holdCount++;
                diagHold++;
                float g = 1f - holdCount / 8f;
                byte[] faded = new byte[l.length];
                for (int i = 0; i + 1 < l.length; i += 2) {
                    short v = (short) ((l[i] & 0xFF) | (l[i + 1] << 8));
                    int s = (int) (v * g);
                    faded[i] = (byte) (s & 0xFF);
                    faded[i + 1] = (byte) ((s >> 8) & 0xFF);
                }
                return faded;
            }
            diagEmpty++;
            return null;
        }
    }

    private static SourceDataLine line;
    private static Thread thread;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** 远端声源 */
    private static final Map<UUID, Source> SOURCES = new ConcurrentHashMap<>();
    private static final Inflater INFLATER = new Inflater();
    private static volatile long lastReceiveTime = 0L;
    private static int MIX_STAT_PEAK = 0;
    private static int MIX_STAT_SOURCES = 0;
    /** 每个发送者最后收到的序号（去重） */
    private static final Map<UUID, Integer> LAST_SEQ = new ConcurrentHashMap<>();

    /** 诊断计数 */
    private static long diagEmpty = 0;
    private static long diagHold = 0;
    private static long diagOverflow = 0;
    private static long diagDup = 0;
    private static long diagRecv = 0;
    /** 自测路径诊断：入队 / 丢弃 / 空帧 / 迟到次数 */

    private AudioPlayback() {
    }

    // ---------------- 查询/配置 ----------------



    public static void setLineBufferFrames(int frames) {
        lineBufferFrames = Math.max(1, Math.min(8, frames));
    }

    public static void setPrebufferFrames(int frames) {
        prebufferFrames = Math.max(0, Math.min(4, frames));
    }

    /** 设置近大远小范围（near 内满音量，far 外静音） */
    public static void setProximityRange(float near, float far) {
        PROX_NEAR = Math.max(0.0D, near);
        PROX_FAR = Math.max(PROX_NEAR + 1.0D, far);
    }

    public static boolean isActive() {
        return RUNNING.get() && !SOURCES.isEmpty();
    }

    public static long lastReceiveTime() {
        return lastReceiveTime;
    }

    // ---------------- 启动/停止 ----------------

    public static synchronized void start() {
        if (RUNNING.get()) {
            return;
        }
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            line = (SourceDataLine) AudioSystem.getLine(info);
            int want = MicCapture.FRAME_BYTES * lineBufferFrames;
            line.open(FORMAT, want);
            line.start();
            com.lmj.voicecall.VoiceCallMod.LOGGER.info(
                    "[VoiceCall] 播放缓冲: 请求={}B 实际={}B ({} ms)",
                    want, line.getBufferSize(), line.getBufferSize() * 1000 / (MicCapture.FRAME_BYTES * 50));
        } catch (Throwable t) {
            com.lmj.voicecall.VoiceCallMod.LOGGER.warn("[VoiceCall] 打开播放设备失败: {}", t.getMessage());
            line = null;
            return;
        }
        RUNNING.set(true);
        thread = new Thread(AudioPlayback::loop, "VoiceCall-Playback");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        RUNNING.set(false);
        thread = null;
        SOURCES.clear();
        LAST_SEQ.clear();
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Throwable ignored) {
            }
            line = null;
        }
    }

    // ---------------- 输入 ----------------

    /** 兼容旧调用 */
    public static void submitRemote(UUID sender, int seq, int mode, byte[] compressed) {
        submitRemote(sender, seq, mode, 0f, 0f, 0f, compressed);
    }

    /** 远端音频：带声源坐标，用于近大远小 */
    public static void submitRemote(UUID sender, int seq, int mode,
                                    float sx, float sy, float sz, byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return;
        }
        UUID key = sender == null ? new UUID(0L, 0L) : sender;
        if (seq >= 0) {
            Integer last = LAST_SEQ.get(key);
            if (last != null) {
                int diff = seq - last;
                if (diff <= 0 && diff > -1000) {
                    diagDup++;
                    return;
                }
            }
            LAST_SEQ.put(key, seq);
        }
        byte[] pcm = decompress(compressed);
        if (pcm == null) {
            return;
        }
        lastReceiveTime = System.currentTimeMillis();
        diagRecv++;
        float gain = computeGain(mode, sx, sy, sz);
        long now = System.currentTimeMillis();
        Source src = SOURCES.get(key);
        if (src == null) {
            Source created = new Source(pcm, now);
            created.mode = mode;
            created.targetGain = gain;
            created.gain = gain;
            SOURCES.put(key, created);
        } else {
            src.mode = mode;
            src.targetGain = gain;
            src.push(pcm, now);
        }
        if (!RUNNING.get()) {
            start();
        }
    }

    /** 解压一帧（供自测录音保存用） */
    public static byte[] decode(byte[] compressed) {
        return decompress(compressed);
    }

    /** 近大远小：频道音频不衰减；近距音频按声源坐标线性+平方衰减 */
    private static float computeGain(int mode, float sx, float sy, float sz) {
        if (mode != 2) {
            return 1f;
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) {
                return 1f;
            }
            double dx = mc.player.getX() - sx;
            double dy = mc.player.getY() - sy;
            double dz = mc.player.getZ() - sz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // 坐标全 0 视为未知（老包/隧道），不衰减
            if (sx == 0f && sy == 0f && sz == 0f) {
                return 1f;
            }
            if (d <= PROX_NEAR) {
                return 1f;
            }
            if (d >= PROX_FAR) {
                return 0f;
            }
            double linear = 1.0 - (d - PROX_NEAR) / (PROX_FAR - PROX_NEAR);
            return (float) (linear * linear);
        } catch (Throwable t) {
            return 1f;
        }
    }

    // ---------------- 输出循环 ----------------

    private static void loop() {
        byte[] silence = SILENCE;
        int statFrames = 0;
        final long t0 = System.nanoTime();
        long frameIndex = 0;
        while (RUNNING.get()) {
            try {
                long due = t0 + frameIndex * FRAME_NANOS;
                long now = System.nanoTime();
                if (now < due - 300_000L) {
                    long remain = due - now;
                    long ms = (remain - 300_000L) / 1_000_000L;
                    if (ms > 0) {
                        Thread.sleep(ms);
                    } else {
                        Thread.yield();
                    }
                    continue;
                }
                // 落后太多（卡顿/GC）：对齐到当前时间
                if (now - due > 200_000_000L) {
                    frameIndex = (now - t0) / FRAME_NANOS;
                }

                byte[] out = mix();
                if (out == null) {
                    out = silence;
                }
                if (out.length != MicCapture.FRAME_BYTES) {
                    byte[] fixed = new byte[MicCapture.FRAME_BYTES];
                    System.arraycopy(out, 0, fixed, 0, Math.min(out.length, fixed.length));
                    out = fixed;
                }
                line.write(out, 0, out.length);
                frameIndex++;

            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** 所有声源缓冲总帧数（诊断用） */
    private static int bufferedFrames() {
        int n = 0;
        for (Source s : SOURCES.values()) {
            synchronized (s.queue) {
                n += s.queue.size();
            }
        }
        return n;
    }

    /** 把所有声源叠加混音；没有声源返回 null */
    private static byte[] mix() {
        if (SOURCES.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        int samples = MicCapture.FRAME_BYTES / 2;
        if (MIX_ACC.length < samples) {
            MIX_ACC = new int[samples];
        }
        int[] acc = MIX_ACC;
        java.util.Arrays.fill(acc, 0);
        boolean any = false;
        for (Map.Entry<UUID, Source> e : SOURCES.entrySet()) {
            Source src = e.getValue();
            if (now - src.updateMs > SOURCE_TIMEOUT_MS) {
                SOURCES.remove(e.getKey(), src);
                continue;
            }
            byte[] pcm = src.poll(now);
            if (pcm == null || pcm.length < MicCapture.FRAME_BYTES) {
                continue;
            }
            any = true;
            // 增益平滑过渡，避免走动时"啪嗒"跳变
            float g = src.gain;
            float tg = src.targetGain;
            float step = 0.25f;
            if (Math.abs(tg - g) <= step) {
                g = tg;
            } else {
                g += (tg > g) ? step : -step;
            }
            src.gain = g;
            if (g > 0.001f) {
                for (int i = 0; i < samples; i++) {
                    short v = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
                    acc[i] += (int) (v * g);
                }
            }
        }
        if (!any) {
            return null;
        }
        byte[] out = MIX_OUT;
        for (int i = 0; i < samples; i++) {
            int v = acc[i];
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    // ---------------- 解压 ----------------

    private static byte[] decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return null;
        }
        synchronized (INFLATER) {
            try {
                INFLATER.reset();
                INFLATER.setInput(compressed);
                byte[] out = new byte[MicCapture.FRAME_BYTES * 2];
                int n = INFLATER.inflate(out);
                if (n <= 0) {
                    return null;
                }
                if (n == out.length) {
                    return out;
                }
                byte[] exact = new byte[n];
                System.arraycopy(out, 0, exact, 0, n);
                return exact;
            } catch (DataFormatException e) {
                return null;
            }
        }
    }
}
