package cn.cctstudio.qqbotauth.util;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class HumanDurationFormatter {
    private HumanDurationFormatter() {
    }

    public static String remainingUntil(Instant expiresAt) {
        long millis = Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis());
        long seconds = Math.max(1, (millis + 999) / 1_000);
        return seconds(seconds);
    }

    public static String seconds(long totalSeconds) {
        long remaining = Math.max(0, totalSeconds);
        long hours = remaining / 3_600;
        long minutes = remaining % 3_600 / 60;
        long seconds = remaining % 60;
        List<String> parts = new ArrayList<>(3);
        if (hours > 0) {
            parts.add(hours + " 小时");
        }
        if (minutes > 0) {
            parts.add(minutes + " 分钟");
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(seconds + " 秒");
        }
        return String.join(" ", parts);
    }
}
