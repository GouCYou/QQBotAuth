package cn.cctstudio.qqbotauth.verification;

import java.time.Instant;
import java.util.UUID;

public record VerificationCode(
        String value,
        UUID minecraftUuid,
        String minecraftName,
        Instant createdAt,
        Instant expiresAt
) {
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
