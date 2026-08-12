package cn.cctstudio.qqbotauth.qq;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks opaque group_openid values seen in official Gateway events. */
public final class QQGroupRegistry {
    private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();
    private final Clock clock;

    public QQGroupRegistry() {
        this(Clock.systemUTC());
    }

    QQGroupRegistry(Clock clock) {
        this.clock = clock;
    }

    public boolean observe(String groupOpenId) {
        Instant now = clock.instant();
        boolean[] first = {false};
        observations.compute(groupOpenId, (ignored, existing) -> {
            if (existing == null) {
                first[0] = true;
                return new Observation(groupOpenId, now, now);
            }
            return new Observation(groupOpenId, existing.firstSeen(), now);
        });
        return first[0];
    }

    public List<Observation> observations() {
        return observations.values().stream()
                .sorted(Comparator.comparing(Observation::lastSeen).reversed())
                .toList();
    }

    public record Observation(String groupOpenId, Instant firstSeen, Instant lastSeen) {
    }
}
