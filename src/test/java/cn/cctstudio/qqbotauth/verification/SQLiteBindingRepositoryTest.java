package cn.cctstudio.qqbotauth.verification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
        assertEquals(binding, repository.findByMinecraftName("steve").join().orElseThrow());
        assertEquals(binding, repository.findByQqIdentity("group-openid", "member-openid").join().orElseThrow());
        assertEquals(List.of(binding), repository.findByMemberOpenId("member-openid").join());
        assertEquals(1, repository.count().join());
        assertTrue(repository.deleteByQqIdentity("group-openid", "member-openid").join().isPresent());
        assertEquals(0, repository.count().join());

        DiscordBindingRecord discord = new DiscordBindingRecord(
                binding.minecraftUuid(), "123456789", "steve.discord",
                Instant.parse("2026-08-12T12:05:00Z"));
        repository.saveDiscord(discord).join();
        assertEquals(discord, repository.findDiscordByMinecraftUuid(binding.minecraftUuid()).join().orElseThrow());
        assertEquals(discord, repository.findByDiscordUserId("123456789").join().orElseThrow());
        assertEquals(discord, repository.deleteDiscordByMinecraftUuid(binding.minecraftUuid()).join().orElseThrow());

        VerificationCode code = new VerificationCode(
                "ABC234", binding.minecraftUuid(), binding.minecraftName(),
                Instant.parse("2026-08-12T12:10:00Z"), Instant.parse("2026-08-12T12:20:00Z"));
        repository.saveVerificationCode(code).join();
        assertEquals(code, repository.findVerificationCode(
                "abc234", Instant.parse("2026-08-12T12:15:00Z")).join().orElseThrow());
        assertTrue(repository.findVerificationCode(
                "ABC234", Instant.parse("2026-08-12T12:21:00Z")).join().isEmpty());
        repository.deleteVerificationCode(binding.minecraftUuid()).join();
        assertTrue(repository.findVerificationCode(
                "ABC234", Instant.parse("2026-08-12T12:15:00Z")).join().isEmpty());
    }
}
