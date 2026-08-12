package cn.cctstudio.qqbotauth.verification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BindingRepository extends AutoCloseable {
    CompletableFuture<Void> initialize();

    CompletableFuture<Optional<BindingRecord>> findByMinecraftUuid(UUID minecraftUuid);

    CompletableFuture<Optional<BindingRecord>> findByQqIdentity(String groupOpenId, String memberOpenId);

    CompletableFuture<Void> save(BindingRecord binding);

    CompletableFuture<Boolean> deleteByQqIdentity(String groupOpenId, String memberOpenId);

    CompletableFuture<Long> count();

    CompletableFuture<List<BindingRecord>> findAll();

    @Override
    default void close() {
    }
}
