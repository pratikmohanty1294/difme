package dev.difme.core.common;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * RFC 9562 UUIDv7 (PDF §9): 48-bit Unix-ms timestamp, version 7, 74 random bits.
 * Time-ordered, so primary-key inserts don't hot-spot the index.
 * Pure: time and randomness are inputs, so command handlers stay deterministic and replayable.
 */
public final class Uuid7 {
    private static final Pattern FORMAT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private Uuid7() {}

    public static UUID of(Instant time, RandomGenerator random) {
        long ms = time.toEpochMilli();
        if (ms < 0 || ms > 0xFFFF_FFFF_FFFFL) throw new IllegalArgumentException("timestamp out of UUIDv7 range");
        long randA = random.nextLong() & 0x0FFFL;
        long randB = random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long msb = (ms << 16) | (0x7L << 12) | randA;
        long lsb = (0x2L << 62) | randB;
        return new UUID(msb, lsb);
    }

    /** Deterministic variant for replay: derives the random bits from a seed (e.g. hash of the source event id). */
    public static UUID derived(Instant time, long seed) {
        return of(time, new java.util.SplittableRandom(seed));
    }

    public static boolean isValid(String s) {
        return s != null && FORMAT.matcher(s).matches();
    }

    public static Instant timestampOf(UUID uuid) {
        if (uuid.version() != 7) throw new IllegalArgumentException("not a UUIDv7");
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }
}
