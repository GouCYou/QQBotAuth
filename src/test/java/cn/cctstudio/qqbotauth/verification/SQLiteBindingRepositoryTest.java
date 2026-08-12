package cn.cctstudio.qqbotauth.verification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteBindingRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void persistsAndQueriesBinding() {
        SQLiteBindingRepository repository = new SQLiteBindingRepository(
                temporaryDirectory.resolve("bindings.db"), executor);
        BindingRecord binding = new BindingRecord(
                UUID.randomUUID(), "Steve", "group-openid", "member-openid",
                Instant.parse("2026-08-12T12:00:00Z"), VerificationStatus.VERIFIED);

        repository.initialize().join();
        repository.save(binding).join();

        assertEquals(binding, repository.findByMinecraftUuid(binding.minecraftUuid()).join().orElseThrow());
        assertEquals(binding, repository.findByQqIdentity("group-openid", "member-openid").join().orElseThrow());
        assertEquals(1, repository.count().join());
        assertTrue(repository.deleteByQqIdentity("group-openid", "member-openid").join());
        assertEquals(0, repository.count().join());
    }
}
