package cn.cctstudio.qqbotauth.verification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

abstract class JdbcBindingRepository implements BindingRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS qqbotauth_bindings (
                minecraft_uuid VARCHAR(36) PRIMARY KEY,
                minecraft_name VARCHAR(16) NOT NULL,
                group_openid VARCHAR(128) NOT NULL,
                member_openid VARCHAR(128) NOT NULL,
                bound_at BIGINT NOT NULL,
                verification_status VARCHAR(32) NOT NULL,
                UNIQUE (group_openid, member_openid)
            )
            """;
    private static final String CREATE_DISCORD_TABLE = """
            CREATE TABLE IF NOT EXISTS qqbotauth_discord_bindings (
                minecraft_uuid VARCHAR(36) PRIMARY KEY,
                discord_user_id VARCHAR(32) NOT NULL UNIQUE,
                discord_username VARCHAR(80) NOT NULL,
                bound_at BIGINT NOT NULL
            )
            """;
    private static final String CREATE_VERIFICATION_CODE_TABLE = """
            CREATE TABLE IF NOT EXISTS qqbotauth_verification_codes (
                code VARCHAR(12) PRIMARY KEY,
                minecraft_uuid VARCHAR(36) NOT NULL UNIQUE,
                minecraft_name VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL
            )
            """;

    private final Executor executor;

    protected JdbcBindingRepository(Executor executor) {
        this.executor = executor;
    }

    protected abstract Connection openConnection() throws SQLException;

    @Override
    public CompletableFuture<Void> initialize() {
        return run(() -> {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE_TABLE);
                statement.executeUpdate(CREATE_DISCORD_TABLE);
                statement.executeUpdate(CREATE_VERIFICATION_CODE_TABLE);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<BindingRecord>> findByMinecraftUuid(UUID minecraftUuid) {
        return run(() -> {
            String sql = "SELECT * FROM qqbotauth_bindings WHERE minecraft_uuid = ?";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, minecraftUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<BindingRecord>> findByMinecraftName(String minecraftName) {
        return run(() -> {
            String sql = "SELECT * FROM qqbotauth_bindings WHERE LOWER(minecraft_name) = LOWER(?) "
                    + "ORDER BY bound_at DESC LIMIT 1";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, minecraftName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<BindingRecord>> findByQqIdentity(String groupOpenId, String memberOpenId) {
        return run(() -> {
            String sql = "SELECT * FROM qqbotauth_bindings WHERE group_openid = ? AND member_openid = ?";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, groupOpenId);
                statement.setString(2, memberOpenId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<BindingRecord>> findByMemberOpenId(String memberOpenId) {
        return run(() -> {
            List<BindingRecord> bindings = new ArrayList<>();
            String sql = "SELECT * FROM qqbotauth_bindings WHERE member_openid = ? ORDER BY bound_at DESC";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, memberOpenId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        bindings.add(read(result));
                    }
                }
            }
            return List.copyOf(bindings);
        });
    }

    @Override
    public CompletableFuture<Void> save(BindingRecord binding) {
        return run(() -> {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement deleteUuid = connection.prepareStatement(
                            "DELETE FROM qqbotauth_bindings WHERE minecraft_uuid = ?")) {
                        deleteUuid.setString(1, binding.minecraftUuid().toString());
                        deleteUuid.executeUpdate();
                    }
                    try (PreparedStatement deleteQq = connection.prepareStatement(
                            "DELETE FROM qqbotauth_bindings WHERE group_openid = ? AND member_openid = ?")) {
                        deleteQq.setString(1, binding.groupOpenId());
                        deleteQq.setString(2, binding.memberOpenId());
                        deleteQq.executeUpdate();
                    }
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO qqbotauth_bindings
                            (minecraft_uuid, minecraft_name, group_openid, member_openid, bound_at, verification_status)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, binding.minecraftUuid().toString());
                        insert.setString(2, binding.minecraftName());
                        insert.setString(3, binding.groupOpenId());
                        insert.setString(4, binding.memberOpenId());
                        insert.setLong(5, binding.boundAt().toEpochMilli());
                        insert.setString(6, binding.status().name());
                        insert.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<BindingRecord>> deleteByQqIdentity(String groupOpenId, String memberOpenId) {
        return deleteAndReturn(
                "SELECT * FROM qqbotauth_bindings WHERE group_openid = ? AND member_openid = ?",
                "DELETE FROM qqbotauth_bindings WHERE group_openid = ? AND member_openid = ?",
                statement -> {
                    statement.setString(1, groupOpenId);
                    statement.setString(2, memberOpenId);
                });
    }

    @Override
    public CompletableFuture<Optional<BindingRecord>> deleteByMinecraftUuid(UUID minecraftUuid) {
        return deleteAndReturn(
                "SELECT * FROM qqbotauth_bindings WHERE minecraft_uuid = ?",
                "DELETE FROM qqbotauth_bindings WHERE minecraft_uuid = ?",
                statement -> statement.setString(1, minecraftUuid.toString()));
    }

    @Override
    public CompletableFuture<Long> count() {
        return run(() -> {
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM qqbotauth_bindings")) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    @Override
    public CompletableFuture<List<BindingRecord>> findAll() {
        return run(() -> {
            List<BindingRecord> bindings = new ArrayList<>();
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM qqbotauth_bindings ORDER BY bound_at")) {
                while (result.next()) {
                    bindings.add(read(result));
                }
            }
            return List.copyOf(bindings);
        });
    }

    @Override
    public CompletableFuture<Optional<DiscordBindingRecord>> findDiscordByMinecraftUuid(UUID minecraftUuid) {
        return run(() -> {
            String sql = "SELECT * FROM qqbotauth_discord_bindings WHERE minecraft_uuid = ?";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, minecraftUuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readDiscord(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<DiscordBindingRecord>> findByDiscordUserId(String discordUserId) {
        return run(() -> {
            String sql = "SELECT * FROM qqbotauth_discord_bindings WHERE discord_user_id = ?";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, discordUserId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readDiscord(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveDiscord(DiscordBindingRecord binding) {
        return run(() -> {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM qqbotauth_discord_bindings WHERE minecraft_uuid = ?")) {
                        delete.setString(1, binding.minecraftUuid().toString());
                        delete.executeUpdate();
                    }
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO qqbotauth_discord_bindings
                            (minecraft_uuid, discord_user_id, discord_username, bound_at)
                            VALUES (?, ?, ?, ?)
                            """)) {
                        insert.setString(1, binding.minecraftUuid().toString());
                        insert.setString(2, binding.discordUserId());
                        insert.setString(3, binding.discordUsername());
                        insert.setLong(4, binding.boundAt().toEpochMilli());
                        insert.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<DiscordBindingRecord>> deleteDiscordByMinecraftUuid(UUID minecraftUuid) {
        return run(() -> {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    DiscordBindingRecord binding;
                    try (PreparedStatement select = connection.prepareStatement(
                            "SELECT * FROM qqbotauth_discord_bindings WHERE minecraft_uuid = ?")) {
                        select.setString(1, minecraftUuid.toString());
                        try (ResultSet result = select.executeQuery()) {
                            if (!result.next()) {
                                connection.rollback();
                                return Optional.empty();
                            }
                            binding = readDiscord(result);
                        }
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM qqbotauth_discord_bindings WHERE minecraft_uuid = ?")) {
                        delete.setString(1, minecraftUuid.toString());
                        if (delete.executeUpdate() != 1) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                    connection.commit();
                    return Optional.of(binding);
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveVerificationCode(VerificationCode code) {
        return run(() -> {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM qqbotauth_verification_codes WHERE minecraft_uuid = ?")) {
                        delete.setString(1, code.minecraftUuid().toString());
                        delete.executeUpdate();
                    }
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO qqbotauth_verification_codes
                            (code, minecraft_uuid, minecraft_name, created_at, expires_at)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, code.value());
                        insert.setString(2, code.minecraftUuid().toString());
                        insert.setString(3, code.minecraftName());
                        insert.setLong(4, code.createdAt().toEpochMilli());
                        insert.setLong(5, code.expiresAt().toEpochMilli());
                        insert.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<VerificationCode>> findVerificationCode(String code, Instant now) {
        return run(() -> {
            String sql = "SELECT * FROM qqbotauth_verification_codes WHERE code = ? AND expires_at > ?";
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, code.trim().toUpperCase(java.util.Locale.ROOT));
                statement.setLong(2, now.toEpochMilli());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    return Optional.of(new VerificationCode(
                            result.getString("code"),
                            UUID.fromString(result.getString("minecraft_uuid")),
                            result.getString("minecraft_name"),
                            Instant.ofEpochMilli(result.getLong("created_at")),
                            Instant.ofEpochMilli(result.getLong("expires_at"))
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteVerificationCode(UUID minecraftUuid) {
        return run(() -> {
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM qqbotauth_verification_codes WHERE minecraft_uuid = ?")) {
                statement.setString(1, minecraftUuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private BindingRecord read(ResultSet result) throws SQLException {
        return new BindingRecord(
                UUID.fromString(result.getString("minecraft_uuid")),
                result.getString("minecraft_name"),
                result.getString("group_openid"),
                result.getString("member_openid"),
                Instant.ofEpochMilli(result.getLong("bound_at")),
                VerificationStatus.valueOf(result.getString("verification_status"))
        );
    }

    private DiscordBindingRecord readDiscord(ResultSet result) throws SQLException {
        return new DiscordBindingRecord(
                UUID.fromString(result.getString("minecraft_uuid")),
                result.getString("discord_user_id"),
                result.getString("discord_username"),
                Instant.ofEpochMilli(result.getLong("bound_at"))
        );
    }

    private CompletableFuture<Optional<BindingRecord>> deleteAndReturn(
            String selectSql,
            String deleteSql,
            StatementBinder binder
    ) {
        return run(() -> {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    BindingRecord binding;
                    try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                        binder.bind(select);
                        try (ResultSet result = select.executeQuery()) {
                            if (!result.next()) {
                                connection.rollback();
                                return Optional.empty();
                            }
                            binding = read(result);
                        }
                    }
                    try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                        binder.bind(delete);
                        if (delete.executeUpdate() != 1) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                    connection.commit();
                    return Optional.of(binding);
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        });
    }

    private <T> CompletableFuture<T> run(SqlSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (SQLException exception) {
                throw new CompletionException("Database operation failed", exception);
            }
        }, executor);
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
