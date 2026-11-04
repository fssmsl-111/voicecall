package com.lmj.voicecall.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 频道"对讲机"占线锁（纯 Java，可单独测试）。
 * <p>
 * 同一频道同一时刻只允许一个人说话；说话停顿超过 timeoutMs 自动释放。
 */
public final class TalkFloor {

    private static final class Holder {
        final UUID speaker;
        volatile long lastActiveMs;

        Holder(UUID speaker, long now) {
            this.speaker = speaker;
            this.lastActiveMs = now;
        }
    }

    private final Map<Integer, Holder> floors = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public TalkFloor(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** 尝试占用；返回 true 表示可以发 */
    public boolean tryTake(int channel, UUID speaker) {
        long now = System.currentTimeMillis();
        Holder cur = floors.get(channel);
        if (cur != null) {
            if (cur.speaker.equals(speaker)) {
                cur.lastActiveMs = now;
                return true;
            }
            if (now - cur.lastActiveMs <= timeoutMs) {
                return false;   // 别人正在说话
            }
        }
        Holder fresh = new Holder(speaker, now);
        Holder prev = floors.putIfAbsent(channel, fresh);
        if (prev == null) {
            return true;
        }
        if (prev.speaker.equals(speaker)) {
            prev.lastActiveMs = now;
            return true;
        }
        if (now - prev.lastActiveMs > timeoutMs) {
            floors.replace(channel, prev, fresh);
            return true;
        }
        return false;
    }

    /** 释放占线（只有持有者能释放） */
    public void release(int channel, UUID speaker) {
        Holder cur = floors.get(channel);
        if (cur != null && cur.speaker.equals(speaker)) {
            floors.remove(channel, cur);
        }
    }

    /** 当前占用者，没有返回 null */
    public UUID holder(int channel) {
        Holder cur = floors.get(channel);
        if (cur == null) {
            return null;
        }
        if (System.currentTimeMillis() - cur.lastActiveMs > timeoutMs) {
            return null;
        }
        return cur.speaker;
    }

    public void clear() {
        floors.clear();
    }
}
