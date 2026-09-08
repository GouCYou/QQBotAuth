package cn.cctstudio.qqbotauth.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Thread-safe per-player rate limiter shared by the Paper and Velocity gates. */
public final class UnboundChatRateLimiter {
    private final ConcurrentMap<UUID, Long> nextAllowedAt = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public UnboundChatRateLimiter() {
        this(System::currentTimeMillis);
    }

    UnboundChatRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public Decision acquire(UUID playerId, int cooldownSeconds) {
        long now = clock.getAsLong();
        long cooldownMillis = Math.max(1, cooldownSeconds) * 1_000L;
        AtomicLong blockedUntil = new AtomicLong(-1L);
        nextAllowedAt.compute(playerId, (ignored, current) -> {
            if (current != null && current > now) {
                blockedUntil.set(current);
                return current;
            }
            return now + cooldownMillis;
        });
        if (blockedUntil.get() < 0L) return new Decision(true, 0L);
        long remainingMillis = blockedUntil.get() - now;
        return new Decision(false, Math.max(1L, (remainingMillis + 999L) / 1_000L));
    }

    public void clear(UUID playerId) {
        nextAllowedAt.remove(playerId);
    }

    /**
     * QuickShop accepts a signed 32-bit integer as a purchase quantity. Velocity
     * cannot see QuickShop's backend interaction state, so callers additionally
     * scope this narrow bypass to servers on which QuickShop is installed.
     */
    public static boolean isPurchaseQuantity(String message) {
        String value = message == null ? "" : message.strip();
        if (value.isEmpty() || value.length() > 10 || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public record Decision(boolean allowed, long remainingSeconds) {
    }
}
