package cn.cctstudio.qqbotauth.verification;

import java.time.Instant;
import java.util.UUID;

public record BindingRecord(
        UUID minecraftUuid,
        String minecraftName,
        String groupOpenId,
        String memberOpenId,
        Instant boundAt,
        VerificationStatus status
) {
}
