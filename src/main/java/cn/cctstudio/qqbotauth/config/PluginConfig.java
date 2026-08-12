package cn.cctstudio.qqbotauth.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

public record PluginConfig(
        QQ qq,
        QQReplyMessages qqMessages,
        Verification verification,
        Database database,
        Player player
) {
    public record QQ(
            boolean enabled,
            String appId,
            String appSecret,
            URI apiBaseUrl,
            URI accessTokenUrl,
            String groupNumber,
            Set<String> allowedGroupOpenIds,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            int reconnectMaxDelaySeconds
    ) {
        public boolean hasCredentials() {
            return !appId.isBlank() && !appSecret.isBlank();
        }
    }

    public record Verification(int codeLength, long expireSeconds) {
    }

    public record Database(DatabaseMode mode, SQLite sqlite, MySql mysql) {
    }

    public enum DatabaseMode {
        SQLITE,
        MYSQL;

        public static DatabaseMode parse(String value) {
            return "mysql".equalsIgnoreCase(value) ? MYSQL : SQLITE;
        }
    }

    public record SQLite(Path file) {
    }

    public record MySql(
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean useSsl,
            boolean allowPublicKeyRetrieval
    ) {
        public boolean isConfigured() {
            return !host.isBlank() && !database.isBlank() && !username.isBlank();
        }
    }

    public record Player(
            boolean blockMovement,
            boolean blockInteraction,
            boolean blockServerCommand,
            Set<String> allowedCommands,
            int reminderSeconds,
            boolean dialogEnabled,
            Transfer transfer
    ) {
    }

    public record Transfer(boolean enabled, String verifiedServer, int delaySeconds) {
    }
}
