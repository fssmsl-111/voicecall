package com.lmj.voicecall.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * 麦克风采集：16kHz / 16bit / 单声道，20ms 一帧。
 * 采集线程直接回调（不经过 tick 批量），把延迟压到最低。
 */
public final class MicCapture {

    public static final float SAMPLE_RATE = com.lmj.voicecall.VoiceConstants.SAMPLE_RATE;
    /** 20ms 一帧（低延迟） */
    public static final int FRAME_MS = com.lmj.voicecall.VoiceConstants.FRAME_MS;
    public static final int FRAME_BYTES = com.lmj.voicecall.VoiceConstants.FRAME_BYTES;

    /** 采集用 48kHz（RNNoise 原生采样率），收发仍是 16kHz */
    public static final int CAPTURE_RATE = 48000;
    public static final int CAPTURE_FRAME_BYTES = CAPTURE_RATE / 50 * 2;   // 20ms = 1920 字节
    private static final AudioFormat FORMAT = new AudioFormat(CAPTURE_RATE, 16, 1, true, false);

    /** 采集线程回调：压缩后的音频帧 + 采集时刻(nanos) */
    public interface FrameSink {
        void accept(byte[] compressed, long captureNanos);
    }

    private static TargetDataLine line;
    private static Thread thread;
    private static volatile boolean running = false;
    private static volatile FrameSink sink;
    private static final Deflater DEFLATER = new Deflater(Deflater.BEST_SPEED);
    private static volatile String lastError = null;
    private static volatile float level = 0f;
    /** 采集帧队列（采集线程 -> 处理线程） */
    private static final java.util.concurrent.ArrayBlockingQueue<byte[]> QUEUE =
            new java.util.concurrent.ArrayBlockingQueue<>(100);
    private static volatile long droppedFrames = 0;

    private MicCapture() {
    }

    public static void setSink(FrameSink s) {
        sink = s;
    }

    public static String lastError() {
        return lastError;
    }

    /** 采集线程因队列满丢掉的帧数 */
    public static long droppedCount() {
        return droppedFrames;
    }

    /** 待处理队列长度 */
    public static int queueSize() {
        return QUEUE.size();
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean available() {
        try {
            return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
        } catch (Throwable t) {
            return false;
        }
    }


    /** 当前选择的麦克风（空 = 系统默认） */
    private static volatile String deviceName = "";
    private static boolean deviceLoaded = false;

    /** 列出所有支持当前格式的录音设备 */
    public static java.util.List<String> listDevices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("系统默认");
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(mi);
                if (mixer.isLineSupported(info)) {
                    out.add(mi.getName());
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static String getDeviceName() {
        loadDeviceFromConfig();
        return deviceName;
    }

    public static void setDeviceName(String name) {
        deviceName = name == null ? "" : name;
        deviceLoaded = true;
        com.lmj.voicecall.VoiceCallMod.LOGGER.info("[VoiceCall] 麦克风设备切换为: {}",
                deviceName.isEmpty() ? "系统默认" : deviceName);
    }

    private static java.nio.file.Path configPath() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("voicecall-client.txt");
    }

    private static void loadDeviceFromConfig() {
        if (deviceLoaded) {
            return;
        }
        deviceLoaded = true;
        try {
            java.nio.file.Path p = configPath();
            if (!java.nio.file.Files.exists(p)) {
                return;
            }
            for (String line : java.nio.file.Files.readAllLines(p)) {
                String t = line.trim();
                if (t.startsWith("micDevice=")) {
                    deviceName = t.substring("micDevice=".length()).trim();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把当前设备写回配置 */
    public static void saveDeviceToConfig() {
        try {
            java.nio.file.Path p = configPath();
            java.util.List<String> lines = java.nio.file.Files.exists(p)
                    ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(p))
                    : new java.util.ArrayList<>();
            lines.removeIf(l -> l.trim().startsWith("micDevice="));
            lines.add("micDevice=" + deviceName);
            java.nio.file.Files.write(p, lines);
        } catch (Throwable t) {
            com.lmj.voicecall.VoiceCallMod.LOGGER.warn("[VoiceCall] 保存麦克风配置失败: {}", t.getMessage());
        }
    }

    /** 按当前选择打开采集线路 */
    private static TargetDataLine openLine(DataLine.Info info) throws javax.sound.sampled.LineUnavailableException {
        loadDeviceFromConfig();
        if (!deviceName.isEmpty()) {
            for (javax.sound.sampled.Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().equals(deviceName)) {
                    continue;
                }
                try {
                    javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(info)) {
                        return (TargetDataLine) mixer.getLine(info);
                    }
                } catch (Throwable ignored) {
                }
            }
            com.lmj.voicecall.VoiceCallMod.LOGGER.warn("[VoiceCall] 找不到麦克风 {}，改用系统默认", deviceName);
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    public static synchronized boolean start() {
        if (running) {
            return true;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                lastError = "找不到可用的麦克风（格式不支持）";
                return false;
            }
            line = openLine(info);
            // 采集缓冲缩到 2 帧（40ms容量）
            line.open(FORMAT, CAPTURE_FRAME_BYTES * 8);
            line.start();
            int actualBuf = line.getBufferSize();
            double bufMs = actualBuf * 1000.0 / (CAPTURE_RATE * 2);
            com.lmj.voicecall.VoiceCallMod.LOGGER.info(
                    "[VoiceCall] 麦克风缓冲: 请求={}B 实际={}B ({} ms) 帧={}B",
                    CAPTURE_FRAME_BYTES * 8, actualBuf, String.format(java.util.Locale.ROOT, "%.1f", bufMs), CAPTURE_FRAME_BYTES);
            running = true;
            lastError = null;
            AudioFilter.reset();
            droppedFrames = 0;
            QUEUE.clear();
            droppedFrames = 0;
            thread = new Thread(MicCapture::captureThread, "VoiceCall-MicCapture");
            thread.setDaemon(true);
            thread.start();
            Thread worker = new Thread(MicCapture::processThread, "VoiceCall-MicProcess");
            worker.setDaemon(true);
            worker.start();
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            shutdown();
            return false;
        }
    }

    /** 采集线程只负责读声卡并投递，绝不阻塞（防止处理慢导致声卡溢出丢帧） */
    private static void captureThread() {
        byte[] acc = new byte[CAPTURE_FRAME_BYTES * 4];
        int accLen = 0;
        byte[] frame = new byte[CAPTURE_FRAME_BYTES];
        while (running) {
            try {
                int read = line.read(acc, accLen, acc.length - accLen);
                if (read <= 0) {
                    continue;
                }
                accLen += read;
                while (accLen >= CAPTURE_FRAME_BYTES) {
                    System.arraycopy(acc, 0, frame, 0, CAPTURE_FRAME_BYTES);
                    System.arraycopy(acc, CAPTURE_FRAME_BYTES, acc, 0, accLen - CAPTURE_FRAME_BYTES);
                    accLen -= CAPTURE_FRAME_BYTES;
                    // 队列满了就丢最旧的（宁可丢一帧也别阻塞采集）
                    if (!QUEUE.offer(frame)) {
                        QUEUE.poll();
                        QUEUE.offer(frame);
                        droppedFrames++;
                    }
                    frame = new byte[CAPTURE_FRAME_BYTES];
                }
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                break;
            }
        }
    }

    /** 处理线程：降噪 + 压缩 + 发送（允许慢，不会影响声卡读数据） */
    private static void processThread() {
        int statFrames = 0;
        long statStart = System.nanoTime();
        while (running) {
            try {
                byte[] frame48 = QUEUE.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (frame48 == null) {
                    continue;
                }
                long captureNanos = System.nanoTime();
                byte[] frame = AudioFilter.process(frame48);   // 48k → 16k（含 RNNoise）
                if (frame == null) {
                    continue;
                }
                updateLevel(frame);
                byte[] compressed = compress(frame);

                FrameSink s = sink;
                if (s != null) {
                    s.accept(compressed, captureNanos);
                }

                statFrames++;
                long elapsedMs = (captureNanos - statStart) / 1_000_000L;
                if (elapsedMs >= 60000L) {
                    double fps = statFrames * 1000.0 / elapsedMs;
                    com.lmj.voicecall.VoiceCallMod.LOGGER.info(
                            "[VoiceCall] capture rate: {} frames in {} ms = {} fps (expect 50.00) 丢帧={}",
                            statFrames, elapsedMs, String.format(java.util.Locale.ROOT, "%.2f", fps), droppedFrames);
                    statStart = captureNanos;
                    statFrames = 0;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                break;
            }
        }
    }
    private static void updateLevel(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
        }
        float target = Math.min(1f, peak / 32768f);
        float cur = level;
        level = target > cur ? target : cur * 0.75f;
    }

    private static byte[] compress(byte[] raw) {
        synchronized (DEFLATER) {
            DEFLATER.reset();
            DEFLATER.setInput(raw);
            DEFLATER.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 16);
            byte[] tmp = new byte[raw.length + 32];
            while (!DEFLATER.finished()) {
                int n = DEFLATER.deflate(tmp);
                if (n <= 0) {
                    break;
                }
                out.write(tmp, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static float level() {
        return level;
    }

    public static synchronized void stop() {
        running = false;
        sink = null;
        shutdown();
    }

    private static void shutdown() {
        try {
            if (line != null) {
                line.stop();
                line.close();
            }
        } catch (Throwable ignored) {
        }
        line = null;
        thread = null;
    }
}
