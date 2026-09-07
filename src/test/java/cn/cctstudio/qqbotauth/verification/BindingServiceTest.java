package cn.cctstudio.qqbotauth.verification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingServiceTest {
    @Test
    void invalidatesCodeOnlyAfterBindingWasPersisted() {
        UUID playerId = UUID.randomUUID();
        VerificationService verification = new VerificationService(6, Duration.ofMinutes(5));
        VerificationCode code = verification.create(playerId, "PlayerOne");
        StubRepository repository = new StubRepository();
        BindingService service = new BindingService(repository, verification, ignored -> { });

        repository.failSave = true;
        assertThrows(CompletionException.class,
                () -> service.bind(code.value(), "group", "member").join());
        assertTrue(verification.findValid(code.value()).isPresent());

        repository.failSave = false;
        BindResult result = service.bind(code.value(), "group", "member").join();
        assertEquals(BindResult.Status.SUCCESS, result.status());
        assertTrue(verification.findValid(code.value()).isEmpty());
    }

    @Test
    void returnsRemovedBindingAndNotifiesTheCentralRemovalListener() {
        UUID playerId = UUID.randomUUID();
        VerificationService verification = new VerificationService(6, Duration.ofMinutes(5));
        VerificationCode code = verification.create(playerId, "PlayerOne");
        StubRepository repository = new StubRepository();
        AtomicReference<BindingRecord> removed = new AtomicReference<>();
        BindingService service = new BindingService(
                repository, verification, ignored -> { }, removed::set);
        BindingRecord binding = service.bind(code.value(), "group", "member").join().binding();

        assertEquals(Optional.of(binding), service.unbindMinecraft("playerone").join());
        assertEquals(binding, removed.get());
        assertTrue(service.findByMinecraft(playerId).join().isEmpty());
    }

    @Test
    void anyBindingRemainsTrueUntilBothQqAndDiscordAreRemoved() {
        UUID playerId = UUID.randomUUID();
        VerificationService verification = new VerificationService(6, Duration.ofMinutes(5));
        StubRepository repository = new StubRepository();
        BindingService service = new BindingService(repository, verification, ignored -> { });
        repository.binding = new BindingRecord(
                playerId, "PlayerOne", "group", "member", Instant.now(), VerificationStatus.VERIFIED);
        repository.discordBinding = new DiscordBindingRecord(
                playerId, "123456", "player.discord", Instant.now());

        assertTrue(service.hasAnyBinding(playerId).join());
        service.unbindMinecraft(playerId.toString()).join();
        assertTrue(service.hasAnyBinding(playerId).join());
        service.unbindDiscord(playerId).join();
        assertEquals(false, service.hasAnyBinding(playerId).join());
    }

    private static final class StubRepository implements BindingRepository {
        private BindingRecord binding;
        private DiscordBindingRecord discordBinding;
        private VerificationCode verificationCode;
        private boolean failSave;

        @Override
        public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<BindingRecord>> findByMinecraftUuid(UUID minecraftUuid) {
            return CompletableFuture.completedFuture(
                    binding != null && binding.minecraftUuid().equals(minecraftUuid)
                            ? Optional.of(binding) : Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<BindingRecord>> findByMinecraftName(String minecraftName) {
            return CompletableFuture.completedFuture(
                    binding != null && binding.minecraftName().equalsIgnoreCase(minecraftName)
                            ? Optional.of(binding) : Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<BindingRecord>> findByQqIdentity(
                String groupOpenId,
                String memberOpenId
        ) {
            return CompletableFuture.completedFuture(
                    binding != null && binding.groupOpenId().equals(groupOpenId)
                            && binding.memberOpenId().equals(memberOpenId)
                            ? Optional.of(binding) : Optional.empty());
        }

        @Override
        public CompletableFuture<List<BindingRecord>> findByMemberOpenId(String memberOpenId) {
            return CompletableFuture.completedFuture(
                    binding != null && binding.memberOpenId().equals(memberOpenId)
                            ? List.of(binding) : List.of());
        }

        @Override
        public CompletableFuture<Void> save(BindingRecord binding) {
            if (failSave) {
                return CompletableFuture.failedFuture(new IllegalStateException("database unavailable"));
            }
            this.binding = binding;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<BindingRecord>> deleteByQqIdentity(
                String groupOpenId,
                String memberOpenId
        ) {
            if (binding != null && binding.groupOpenId().equals(groupOpenId)
                    && binding.memberOpenId().equals(memberOpenId)) {
                BindingRecord deleted = binding;
                binding = null;
                return CompletableFuture.completedFuture(Optional.of(deleted));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<BindingRecord>> deleteByMinecraftUuid(UUID minecraftUuid) {
            if (binding != null && binding.minecraftUuid().equals(minecraftUuid)) {
                BindingRecord deleted = binding;
                binding = null;
                return CompletableFuture.completedFuture(Optional.of(deleted));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Long> count() {
            return CompletableFuture.completedFuture(binding == null ? 0L : 1L);
        }

        @Override
        public CompletableFuture<List<BindingRecord>> findAll() {
            return CompletableFuture.completedFuture(binding == null ? List.of() : List.of(binding));
        }

        @Override
        public CompletableFuture<Optional<DiscordBindingRecord>> findDiscordByMinecraftUuid(UUID minecraftUuid) {
            return CompletableFuture.completedFuture(discordBinding != null
                    && discordBinding.minecraftUuid().equals(minecraftUuid)
                    ? Optional.of(discordBinding) : Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<DiscordBindingRecord>> findByDiscordUserId(String discordUserId) {
            return CompletableFuture.completedFuture(discordBinding != null
                    && discordBinding.discordUserId().equals(discordUserId)
                    ? Optional.of(discordBinding) : Optional.empty());
        }

        @Override
        public CompletableFuture<Void> saveDiscord(DiscordBindingRecord binding) {
            discordBinding = binding;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<DiscordBindingRecord>> deleteDiscordByMinecraftUuid(UUID minecraftUuid) {
            if (discordBinding != null && discordBinding.minecraftUuid().equals(minecraftUuid)) {
                DiscordBindingRecord deleted = discordBinding;
                discordBinding = null;
                return CompletableFuture.completedFuture(Optional.of(deleted));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Void> saveVerificationCode(VerificationCode code) {
            verificationCode = code;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<VerificationCode>> findVerificationCode(String code, Instant now) {
            return CompletableFuture.completedFuture(verificationCode != null
                    && verificationCode.value().equalsIgnoreCase(code)
                    && verificationCode.expiresAt().isAfter(now)
                    ? Optional.of(verificationCode) : Optional.empty());
        }

        @Override
        public CompletableFuture<Void> deleteVerificationCode(UUID minecraftUuid) {
            if (verificationCode != null && verificationCode.minecraftUuid().equals(minecraftUuid)) {
                verificationCode = null;
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
