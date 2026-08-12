package cn.cctstudio.qqbotauth.verification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

    private static final class StubRepository implements BindingRepository {
        private BindingRecord binding;
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
        public CompletableFuture<Void> save(BindingRecord binding) {
            if (failSave) {
                return CompletableFuture.failedFuture(new IllegalStateException("database unavailable"));
            }
            this.binding = binding;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> deleteByQqIdentity(String groupOpenId, String memberOpenId) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Long> count() {
            return CompletableFuture.completedFuture(binding == null ? 0L : 1L);
        }

        @Override
        public CompletableFuture<List<BindingRecord>> findAll() {
            return CompletableFuture.completedFuture(binding == null ? List.of() : List.of(binding));
        }
    }
}
