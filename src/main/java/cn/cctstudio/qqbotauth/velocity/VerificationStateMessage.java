package cn.cctstudio.qqbotauth.velocity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public record VerificationStateMessage(UUID playerUuid, boolean verified) {
    private static final int MAGIC = 0x51424131;
    private static final int MAX_BYTES = 64;

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeLong(playerUuid.getMostSignificantBits());
                output.writeLong(playerUuid.getLeastSignificantBits());
                output.writeBoolean(verified);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode verification state", impossible);
        }
    }

    public static Optional<VerificationStateMessage> decode(byte[] bytes) {
        if (bytes == null || bytes.length != 21 || bytes.length > MAX_BYTES) {
            return Optional.empty();
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                return Optional.empty();
            }
            UUID uuid = new UUID(input.readLong(), input.readLong());
            boolean verified = input.readBoolean();
            return Optional.of(new VerificationStateMessage(uuid, verified));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
