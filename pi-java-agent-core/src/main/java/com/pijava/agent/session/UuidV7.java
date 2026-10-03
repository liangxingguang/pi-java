package com.pijava.agent.session;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7 (time-ordered) generator.
 *
 * <p>pi uses {@code uuidv7} for session/entry/record ids; JDK 25 does not yet
 * expose a type-7 factory, so this implements the standard layout: 48-bit Unix
 * timestamp with millisecond precision, version/variant bits, then 74 bits of
 * randomness.</p>
 *
 * <p>⚠️ <b>2026-10-03 修过一处真 bug</b>：旧实现把随机字节按
 * {@code (long) rand[i] << k} 拼进去，而 Java 的 **byte → long 会符号扩展**
 * （{@code 0xDD} → {@code 0xFFFF_FFFF_FFFF_FFDD}）⇒ 左移后把**高位的 48 位时间戳整段
 * 涂成 1**、变体位也涂成了 3，生成出来的 id 一律长成 {@code ffffffff-ffff-…}
 * （用户报障「会话名为什么是 fffffff」）。**别再回退成字节拼接**：位移前一律
 * {@code & 0xFF}，或者直接用 {@code long} 随机数 + 掩码（现在这样）。
 * 回归见 {@code UuidV7Test}。</p>
 */
public final class UuidV7 implements IdGenerator {

    /** Shared instance. */
    public static final UuidV7 INSTANCE = new UuidV7();

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    /** Generate a new UUID v7. */
    public static UUID uuid() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextLong();
        long randB = RANDOM.nextLong();
        // 48 位毫秒时间戳 | version 7 | 12 位 rand_a
        long msb = ((millis & 0xFFFF_FFFF_FFFFL) << 16) | 0x7000L | (randA & 0x0FFFL);
        // variant 10 | 62 位 rand_b
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    @Override
    public String next() {
        return uuid().toString();
    }
}
