package cn.cctstudio.qqbotauth.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnboundChatRateLimiterTest {
    @Test
    void allowsOneMessageEveryThirtySeconds() {
        AtomicLong now = new AtomicLong(1_000L);
        UnboundChatRateLimiter limiter = new UnboundChatRateLimiter(now::get);
        UUID player = UUID.randomUUID();

        assertTrue(limiter.acquire(player, 30).allowed());
        now.set(1_001L);
        UnboundChatRateLimiter.Decision blocked = limiter.acquire(player, 30);
        assertFalse(blocked.allowed());
        assertEquals(30L, blocked.remainingSeconds());
        now.set(31_000L);
        assertTrue(limiter.acquire(player, 30).allowed());
    }

    @Test
    void clearsPlayerStateAfterDisconnectOrBinding() {
        UnboundChatRateLimiter limiter = new UnboundChatRateLimiter(() -> 10_000L);
        UUID player = UUID.randomUUID();
        assertTrue(limiter.acquire(player, 30).allowed());
        assertFalse(limiter.acquire(player, 30).allowed());
        limiter.clear(player);
        assertTrue(limiter.acquire(player, 30).allowed());
    }

}
