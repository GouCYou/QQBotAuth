package cn.cctstudio.qqbotauth.verification;

import java.time.Instant;
import java.util.UUID;

public record DiscordBindingRecord(
        UUID minecraftUuid,
        String discordUserId,
        String discordUsername,
        Instant boundAt
) {
}
