package cn.cctstudio.qqbotauth.verification;

import java.time.Clock;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class BindingService {
    private final Object queueLock = new Object();
    private final BindingRepository repository;
    private final VerificationService verificationService;
    private final Clock clock;
    private final Consumer<BindingRecord> successListener;
    private final Consumer<BindingRecord> removalListener;
    private CompletableFuture<Void> queue = CompletableFuture.completedFuture(null);

    public BindingService(
            BindingRepository repository,
            VerificationService verificationService,
            Consumer<BindingRecord> successListener
    ) {
        this(repository, verificationService, successListener, ignored -> { });
    }

    public BindingService(
            BindingRepository repository,
            VerificationService verificationService,
            Consumer<BindingRecord> successListener,
            Consumer<BindingRecord> removalListener
    ) {
        this(repository, verificationService, Clock.systemUTC(), successListener, removalListener);
    }

    BindingService(
            BindingRepository repository,
            VerificationService verificationService,
            Clock clock,
            Consumer<BindingRecord> successListener,
            Consumer<BindingRecord> removalListener
    ) {
        this.repository = repository;
        this.verificationService = verificationService;
        this.clock = clock;
        this.successListener = successListener;
        this.removalListener = removalListener;
    }

    public CompletableFuture<BindResult> bind(String rawCode, String groupOpenId, String memberOpenId) {
        return enqueue(() -> doBind(rawCode, groupOpenId, memberOpenId));
    }

    private CompletableFuture<BindResult> doBind(String rawCode, String groupOpenId, String memberOpenId) {
        Optional<VerificationCode> local = verificationService.findValid(rawCode);
        CompletableFuture<Optional<VerificationCode>> lookup = local.isPresent()
                ? CompletableFuture.completedFuture(local)
                : repository.findVerificationCode(rawCode, clock.instant());
        return lookup.thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(BindResult.of(BindResult.Status.INVALID_OR_EXPIRED_CODE));
            }
            VerificationCode code = found.orElseThrow();
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
                return repository.save(binding).thenCompose(ignored -> {
                    verificationService.invalidate(code.minecraftUuid());
                    successListener.accept(binding);
                    return repository.deleteVerificationCode(code.minecraftUuid())
                            .thenApply(nothing -> new BindResult(BindResult.Status.SUCCESS, binding));
                });
            });
        });
        });
    }

    public CompletableFuture<VerificationCode> createVerificationCode(UUID minecraftUuid, String minecraftName) {
        VerificationCode code = verificationService.create(minecraftUuid, minecraftName);
        return repository.saveVerificationCode(code).thenApply(ignored -> code);
    }

    public Optional<VerificationCode> currentVerificationCode(UUID minecraftUuid) {
        return verificationService.current(minecraftUuid);
    }

    public CompletableFuture<Void> invalidateVerificationCode(UUID minecraftUuid) {
        verificationService.invalidate(minecraftUuid);
        return repository.deleteVerificationCode(minecraftUuid);
    }

    public CompletableFuture<Optional<BindingRecord>> findByMinecraft(UUID minecraftUuid) {
        return repository.findByMinecraftUuid(minecraftUuid);
    }

    public CompletableFuture<Boolean> hasAnyBinding(UUID minecraftUuid) {
        return repository.findByMinecraftUuid(minecraftUuid).thenCompose(qq -> qq.isPresent()
                ? CompletableFuture.completedFuture(true)
                : repository.findDiscordByMinecraftUuid(minecraftUuid).thenApply(Optional::isPresent));
    }

    public CompletableFuture<Optional<DiscordBindingRecord>> findDiscordByMinecraft(UUID minecraftUuid) {
        return repository.findDiscordByMinecraftUuid(minecraftUuid);
    }

    public CompletableFuture<DiscordBindResult> bindDiscord(
            UUID minecraftUuid,
            String discordUserId,
            String discordUsername
    ) {
        return enqueue(() -> repository.findByDiscordUserId(discordUserId).thenCompose(byDiscord -> {
            if (byDiscord.isPresent() && !byDiscord.orElseThrow().minecraftUuid().equals(minecraftUuid)) {
                return CompletableFuture.completedFuture(new DiscordBindResult(
                        DiscordBindResult.Status.DISCORD_ALREADY_BOUND, byDiscord.orElseThrow()));
            }
            return repository.findDiscordByMinecraftUuid(minecraftUuid).thenCompose(existing -> {
                if (existing.isPresent()
                        && existing.orElseThrow().discordUserId().equals(discordUserId)) {
                    return CompletableFuture.completedFuture(new DiscordBindResult(
                            DiscordBindResult.Status.ALREADY_BOUND, existing.orElseThrow()));
                }
                DiscordBindingRecord binding = new DiscordBindingRecord(
                        minecraftUuid,
                        discordUserId,
                        discordUsername,
                        clock.instant()
                );
                return repository.saveDiscord(binding).thenApply(ignored ->
                        new DiscordBindResult(DiscordBindResult.Status.SUCCESS, binding));
            });
        }));
    }

    public CompletableFuture<Boolean> unbindDiscord(UUID minecraftUuid) {
        return enqueue(() -> repository.deleteDiscordByMinecraftUuid(minecraftUuid)
                .thenApply(Optional::isPresent));
    }

    public CompletableFuture<Optional<BindingRecord>> findByMinecraftIdentity(String nameOrUuid) {
        Optional<UUID> parsedUuid = parseUuid(nameOrUuid);
        return parsedUuid.isPresent()
                ? repository.findByMinecraftUuid(parsedUuid.get())
                : repository.findByMinecraftName(nameOrUuid.trim());
    }

    public CompletableFuture<Optional<BindingRecord>> findByQq(String groupOpenId, String memberOpenId) {
        return repository.findByQqIdentity(groupOpenId, memberOpenId);
    }

    public CompletableFuture<List<BindingRecord>> findByQqMember(String memberOpenId) {
        return repository.findByMemberOpenId(memberOpenId.trim());
    }

    public CompletableFuture<Boolean> unbind(String groupOpenId, String memberOpenId) {
        return unbindRecord(groupOpenId, memberOpenId).thenApply(Optional::isPresent);
    }

    public CompletableFuture<Optional<BindingRecord>> unbindRecord(String groupOpenId, String memberOpenId) {
        return enqueue(() -> repository.deleteByQqIdentity(groupOpenId, memberOpenId)
                .thenApply(binding -> {
                    binding.ifPresent(removalListener);
                    return binding;
                }));
    }

    public CompletableFuture<Optional<BindingRecord>> unbindMinecraft(String nameOrUuid) {
        return enqueue(() -> {
            Optional<UUID> parsedUuid = parseUuid(nameOrUuid);
            CompletableFuture<Optional<BindingRecord>> lookup = parsedUuid.isPresent()
                    ? repository.findByMinecraftUuid(parsedUuid.get())
                    : repository.findByMinecraftName(nameOrUuid.trim());
            return lookup.thenCompose(binding -> {
                if (binding.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return repository.deleteByMinecraftUuid(binding.get().minecraftUuid())
                        .thenApply(deleted -> {
                            deleted.ifPresent(removalListener);
                            return deleted;
                        });
            });
        });
    }

    public CompletableFuture<Long> count() {
        return repository.count();
    }

    public CompletableFuture<List<BindingRecord>> findAll() {
        return repository.findAll();
    }

    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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
