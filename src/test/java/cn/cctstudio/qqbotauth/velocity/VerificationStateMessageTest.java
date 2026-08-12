package cn.cctstudio.qqbotauth.velocity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationStateMessageTest {
    @Test
    void roundTripsAndRejectsMalformedPayload() {
        VerificationStateMessage message = new VerificationStateMessage(UUID.randomUUID(), true);
        assertEquals(message, VerificationStateMessage.decode(message.encode()).orElseThrow());
        assertTrue(VerificationStateMessage.decode(new byte[]{1, 2, 3}).isEmpty());
    }
}
