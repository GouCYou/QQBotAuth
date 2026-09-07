package cn.cctstudio.qqbotauth.velocity;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

/** Small authenticated loopback protocol shared by the Paper and Velocity sides of the plugin. */
record VelocityControlMessage(UUID playerUuid, Action action) {
    private static final int MAGIC = 0x51424131; // QBA1
    private static final int VERSION = 2;

    enum Action {
        BOUND(1),
        UNBOUND(2);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        static Optional<Action> fromId(int id) {
            for (Action action : values()) {
                if (action.id == id) return Optional.of(action);
            }
            return Optional.empty();
        }
    }

    void writeTo(DataOutput output, String secret) throws IOException {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        output.writeByte(action.id);
        output.writeUTF(secret);
        output.writeLong(playerUuid.getMostSignificantBits());
        output.writeLong(playerUuid.getLeastSignificantBits());
    }

    static Optional<VelocityControlMessage> readFrom(DataInput input, String expectedSecret) throws IOException {
        if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
            return Optional.empty();
        }
        Optional<Action> action = Action.fromId(input.readUnsignedByte());
        if (action.isEmpty()) return Optional.empty();
        String receivedSecret = input.readUTF();
        if (!MessageDigest.isEqual(
                receivedSecret.getBytes(StandardCharsets.UTF_8),
                expectedSecret.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(new VelocityControlMessage(
                new UUID(input.readLong(), input.readLong()), action.orElseThrow()));
    }
}
