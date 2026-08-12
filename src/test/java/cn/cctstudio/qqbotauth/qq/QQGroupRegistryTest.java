package cn.cctstudio.qqbotauth.qq;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQGroupRegistryTest {
    @Test
    void recordsOpaqueGroupOnlyOnce() {
        QQGroupRegistry registry = new QQGroupRegistry(
                Clock.fixed(Instant.parse("2026-08-12T13:00:00Z"), ZoneOffset.UTC));

        assertTrue(registry.observe("group-openid-a"));
        assertFalse(registry.observe("group-openid-a"));
        assertTrue(registry.observe("group-openid-b"));
        assertEquals(2, registry.observations().size());
    }
}
