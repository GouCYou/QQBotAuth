package cn.cctstudio.qqbotauth.verification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public interface BindingRepository extends AutoCloseable {
    CompletableFuture<Void> initialize();

    CompletableFuture<Optional<BindingRecord>> findByMinecraftUuid(UUID minecraftUuid);

    CompletableFuture<Optional<BindingRecord>> findByMinecraftName(String minecraftName);

    CompletableFuture<Optional<BindingRecord>> findByQqIdentity(String groupOpenId, String memberOpenId);

    CompletableFuture<List<BindingRecord>> findByMemberOpenId(String memberOpenId);

    CompletableFuture<Void> save(BindingRecord binding);

    CompletableFuture<Optional<BindingRecord>> deleteByQqIdentity(String groupOpenId, String memberOpenId);

    CompletableFuture<Optional<BindingRecord>> deleteByMinecraftUuid(UUID minecraftUuid);

    CompletableFuture<Long> count();

    CompletableFuture<List<BindingRecord>> findAll();

    CompletableFuture<Optional<DiscordBindingRecord>> findDiscordByMinecraftUuid(UUID minecraftUuid);

    CompletableFuture<Optional<DiscordBindingRecord>> findByDiscordUserId(String discordUserId);

    CompletableFuture<Void> saveDiscord(DiscordBindingRecord binding);

    CompletableFuture<Optional<DiscordBindingRecord>> deleteDiscordByMinecraftUuid(UUID minecraftUuid);

    CompletableFuture<Void> saveVerificationCode(VerificationCode code);

    CompletableFuture<Optional<VerificationCode>> findVerificationCode(String code, Instant now);

    CompletableFuture<Void> deleteVerificationCode(UUID minecraftUuid);

    @Override
    default void close() {
    }
}
