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
 */
public final class UuidV7 implements IdGenerator {

    /** Shared instance. */
    public static final UuidV7 INSTANCE = new UuidV7();

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    /** Generate a new UUID v7. */
    public static UUID uuid() {
        long millis = System.currentTimeMillis();
        long hi = (millis & 0x0000_FFFF_FFFF_FFFFL) << 16;
        byte[] rand = new byte[8];
        RANDOM.nextBytes(rand);
        long midRand = ((long) rand[0] << 40) | ((long) rand[1] << 32)
            | ((long) rand[2] << 24) | ((long) rand[3] << 16)
            | ((long) rand[4] << 8) | ((long) rand[5] & 0xFF);
        long lo = ((long) rand[6] << 56) | ((long) rand[7] << 48);
        RANDOM.nextBytes(rand);
        lo |= ((long) rand[0] & 0xFF) << 40 | ((long) rand[1] & 0xFF) << 32
            | ((long) rand[2] & 0xFF) << 24 | ((long) rand[3] & 0xFF) << 16
            | ((long) rand[4] & 0xFF) << 8 | ((long) rand[5] & 0xFF);
        long msb = hi | midRand | 0x0000_0000_0000_7000L; // version 7
        long lsb = lo | 0x8000_0000_0000_0000L;           // variant 10xx
        return new UUID(msb, lsb);
    }

    @Override
    public String next() {
        return uuid().toString();
    }
}
