package cn.cctstudio.qqbotauth.velocity;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityControlMessageTest {
    @Test
    void roundTripsWithMatchingSecretAndRejectsWrongSecret() throws Exception {
        VelocityControlMessage message = new VelocityControlMessage(
                UUID.randomUUID(), VelocityControlMessage.Action.BOUND);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            message.writeTo(output, "a-secure-control-secret-123");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(message, VelocityControlMessage.readFrom(
                    input, "a-secure-control-secret-123").orElseThrow());
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertTrue(VelocityControlMessage.readFrom(input, "wrong-secret-but-long-enough").isEmpty());
        }
    }
}
