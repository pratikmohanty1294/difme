package dev.difme.core.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Uuid7Test {
    @Test
    void versionVariantAndTimestamp() {
        Instant t = Instant.parse("2026-11-17T10:00:00.123Z");
        UUID id = Uuid7.of(t, new SplittableRandom(42));
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertEquals(t, Uuid7.timestampOf(id));
        assertTrue(Uuid7.isValid(id.toString()));
    }

    @Test
    void timeOrdered() {
        UUID a = Uuid7.of(Instant.ofEpochMilli(1_000), new SplittableRandom(1));
        UUID b = Uuid7.of(Instant.ofEpochMilli(1_001), new SplittableRandom(1));
        assertTrue(a.toString().compareTo(b.toString()) < 0);
    }

    @Test
    void derivedIsDeterministic() {
        Instant t = Instant.ofEpochMilli(1_700_000_000_000L);
        assertEquals(Uuid7.derived(t, 99), Uuid7.derived(t, 99));
        assertNotEquals(Uuid7.derived(t, 99), Uuid7.derived(t, 100));
    }

    @Test
    void rejectsV4() {
        assertFalse(Uuid7.isValid(UUID.nameUUIDFromBytes(new byte[] {1}).toString()));
    }
}
