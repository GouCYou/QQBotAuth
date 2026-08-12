package cn.cctstudio.qqbotauth.verification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationServiceTest {
    @Test
    void onlyOneCodePerPlayerAndSingleUse() {
        UUID playerId = UUID.randomUUID();
        VerificationService service = new VerificationService(
                6,
                Duration.ofMinutes(5),
                Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC),
                new Random(12345L)
        );

        VerificationCode first = service.create(playerId, "PlayerOne");
        VerificationCode second = service.create(playerId, "PlayerOne");

        assertNotEquals(first.value(), second.value());
        assertFalse(service.findValid(first.value()).isPresent());
        assertEquals(second, service.current(playerId).orElseThrow());
        assertTrue(service.consume(second.value().toLowerCase(), playerId));
        assertFalse(service.consume(second.value(), playerId));
    }

    @Test
    void alphabetExcludesAmbiguousCharacters() {
        VerificationService service = new VerificationService(12, Duration.ofMinutes(5));
        for (int i = 0; i < 100; i++) {
            String code = service.create(UUID.randomUUID(), "Player").value();
            assertEquals(12, code.length());
            assertFalse(code.matches(".*[O0I1].*"));
            assertTrue(code.matches("[2-9A-HJ-NP-Z]+"));
        }
    }
}
