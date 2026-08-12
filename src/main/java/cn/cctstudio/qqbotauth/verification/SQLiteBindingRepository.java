package cn.cctstudio.qqbotauth.verification;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Executor;

public final class SQLiteBindingRepository extends JdbcBindingRepository {
    private final String jdbcUrl;

    public SQLiteBindingRepository(Path file, Executor executor) {
        super(executor);
        this.jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("SQLite JDBC driver is unavailable", exception);
        }
    }

    @Override
    protected Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }
}
