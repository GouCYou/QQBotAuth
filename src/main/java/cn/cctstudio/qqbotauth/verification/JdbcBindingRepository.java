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
    public CompletableFuture<Boolean> deleteByQqIdentity(String groupOpenId, String memberOpenId) {
        return run(() -> {
            try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM qqbotauth_bindings WHERE group_openid = ? AND member_openid = ?")) {
                statement.setString(1, groupOpenId);
                statement.setString(2, memberOpenId);
                return statement.executeUpdate() > 0;
            }
        });
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
}
