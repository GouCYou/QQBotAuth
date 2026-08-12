package cn.cctstudio.qqbotauth.verification;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class VerificationService {
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final Object lock = new Object();
    private final Map<UUID, VerificationCode> byPlayer = new HashMap<>();
    private final Map<String, VerificationCode> byValue = new HashMap<>();
    private final Clock clock;
    private final RandomGenerator random;
    private volatile int codeLength;
    private volatile Duration lifetime;

    public VerificationService(int codeLength, Duration lifetime) {
        this(codeLength, lifetime, Clock.systemUTC(), new SecureRandom());
    }

    VerificationService(int codeLength, Duration lifetime, Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
        reconfigure(codeLength, lifetime);
    }

    public void reconfigure(int codeLength, Duration lifetime) {
        if (codeLength < 4 || codeLength > 12) {
            throw new IllegalArgumentException("codeLength must be between 4 and 12");
        }
        if (lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
        this.codeLength = codeLength;
        this.lifetime = lifetime;
    }

    public VerificationCode create(UUID minecraftUuid, String minecraftName) {
        Instant now = clock.instant();
        synchronized (lock) {
            purgeExpired(now);
            VerificationCode old = byPlayer.remove(minecraftUuid);
            if (old != null) {
                byValue.remove(old.value());
            }
            String value;
            do {
                value = randomCode();
            } while (byValue.containsKey(value));
            VerificationCode code = new VerificationCode(
                    value, minecraftUuid, minecraftName, now, now.plus(lifetime));
            byPlayer.put(minecraftUuid, code);
            byValue.put(value, code);
            return code;
        }
    }

    public Optional<VerificationCode> current(UUID minecraftUuid) {
        Instant now = clock.instant();
        synchronized (lock) {
            VerificationCode code = byPlayer.get(minecraftUuid);
            if (code == null) {
                return Optional.empty();
            }
            if (code.isExpired(now)) {
                remove(code);
                return Optional.empty();
            }
            return Optional.of(code);
        }
    }

    public Optional<VerificationCode> findValid(String rawCode) {
        Instant now = clock.instant();
        synchronized (lock) {
            VerificationCode code = byValue.get(normalize(rawCode));
            if (code == null) {
                return Optional.empty();
            }
            if (code.isExpired(now)) {
                remove(code);
                return Optional.empty();
            }
            return Optional.of(code);
        }
    }

    public boolean consume(String rawCode, UUID expectedPlayer) {
        synchronized (lock) {
            VerificationCode code = byValue.get(normalize(rawCode));
            if (code == null || code.isExpired(clock.instant()) || !code.minecraftUuid().equals(expectedPlayer)) {
                if (code != null && code.isExpired(clock.instant())) {
                    remove(code);
                }
                return false;
            }
            remove(code);
            return true;
        }
    }

    public void invalidate(UUID minecraftUuid) {
        synchronized (lock) {
            VerificationCode code = byPlayer.get(minecraftUuid);
            if (code != null) {
                remove(code);
            }
        }
    }

    public int activeCount() {
        synchronized (lock) {
            purgeExpired(clock.instant());
            return byValue.size();
        }
    }

    private String randomCode() {
        StringBuilder value = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }

    private void purgeExpired(Instant now) {
        byValue.values().removeIf(code -> {
            if (!code.isExpired(now)) {
                return false;
            }
            byPlayer.remove(code.minecraftUuid(), code);
            return true;
        });
    }

    private void remove(VerificationCode code) {
        byValue.remove(code.value(), code);
        byPlayer.remove(code.minecraftUuid(), code);
    }

    private String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
