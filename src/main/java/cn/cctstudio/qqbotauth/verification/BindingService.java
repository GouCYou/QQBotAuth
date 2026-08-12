package cn.cctstudio.qqbotauth.verification;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class BindingService {
    private final Object queueLock = new Object();
    private final BindingRepository repository;
    private final VerificationService verificationService;
    private final Clock clock;
    private final Consumer<BindingRecord> successListener;
    private CompletableFuture<Void> queue = CompletableFuture.completedFuture(null);

    public BindingService(
            BindingRepository repository,
            VerificationService verificationService,
            Consumer<BindingRecord> successListener
    ) {
        this(repository, verificationService, Clock.systemUTC(), successListener);
    }

    BindingService(
            BindingRepository repository,
            VerificationService verificationService,
            Clock clock,
            Consumer<BindingRecord> successListener
    ) {
        this.repository = repository;
        this.verificationService = verificationService;
        this.clock = clock;
        this.successListener = successListener;
    }

    public CompletableFuture<BindResult> bind(String rawCode, String groupOpenId, String memberOpenId) {
        return enqueue(() -> doBind(rawCode, groupOpenId, memberOpenId));
    }

    private CompletableFuture<BindResult> doBind(String rawCode, String groupOpenId, String memberOpenId) {
        Optional<VerificationCode> found = verificationService.findValid(rawCode);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(BindResult.of(BindResult.Status.INVALID_OR_EXPIRED_CODE));
        }
        VerificationCode code = found.get();
        return repository.findByQqIdentity(groupOpenId, memberOpenId).thenCompose(qqBinding -> {
            if (qqBinding.isPresent()) {
                BindResult.Status status = qqBinding.get().minecraftUuid().equals(code.minecraftUuid())
                        ? BindResult.Status.MINECRAFT_ALREADY_BOUND
                        : BindResult.Status.QQ_ALREADY_BOUND;
                return CompletableFuture.completedFuture(new BindResult(status, qqBinding.get()));
            }
            return repository.findByMinecraftUuid(code.minecraftUuid()).thenCompose(mcBinding -> {
                if (mcBinding.isPresent()) {
                    return CompletableFuture.completedFuture(
                            new BindResult(BindResult.Status.MINECRAFT_ALREADY_BOUND, mcBinding.get()));
                }
                BindingRecord binding = new BindingRecord(
                        code.minecraftUuid(),
                        code.minecraftName(),
                        groupOpenId,
                        memberOpenId,
                        clock.instant(),
                        VerificationStatus.VERIFIED
                );
                return repository.save(binding).thenApply(ignored -> {
                    verificationService.invalidate(code.minecraftUuid());
                    successListener.accept(binding);
                    return new BindResult(BindResult.Status.SUCCESS, binding);
                });
            });
        });
    }

    public CompletableFuture<Optional<BindingRecord>> findByMinecraft(UUID minecraftUuid) {
        return repository.findByMinecraftUuid(minecraftUuid);
    }

    public CompletableFuture<Optional<BindingRecord>> findByQq(String groupOpenId, String memberOpenId) {
        return repository.findByQqIdentity(groupOpenId, memberOpenId);
    }

    public CompletableFuture<Boolean> unbind(String groupOpenId, String memberOpenId) {
        return enqueue(() -> repository.deleteByQqIdentity(groupOpenId, memberOpenId));
    }

    public CompletableFuture<Long> count() {
        return repository.count();
    }

    private <T> CompletableFuture<T> enqueue(Operation<T> operation) {
        synchronized (queueLock) {
            CompletableFuture<T> result = queue.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> operation.run());
            queue = result.handle((ignored, failure) -> null);
            return result;
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        CompletableFuture<T> run();
    }
}
