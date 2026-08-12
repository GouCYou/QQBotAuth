package cn.cctstudio.qqbotauth.verification;

import cn.cctstudio.qqbotauth.config.PluginConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executor;

public final class MySqlBindingRepository extends JdbcBindingRepository {
    private final String jdbcUrl;
    private final Properties properties;

    public MySqlBindingRepository(PluginConfig.MySql config, Executor executor) {
        super(executor);
        if (!config.isConfigured()) {
            throw new IllegalArgumentException("MySQL configuration is incomplete");
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("MySQL JDBC driver is unavailable", exception);
        }
        String database = URLEncoder.encode(config.database(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        jdbcUrl = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + database;
        properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("useSSL", Boolean.toString(config.useSsl()));
        properties.setProperty("allowPublicKeyRetrieval", Boolean.toString(config.allowPublicKeyRetrieval()));
        properties.setProperty("characterEncoding", "UTF-8");
        properties.setProperty("serverTimezone", "UTC");
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, properties);
    }
}
