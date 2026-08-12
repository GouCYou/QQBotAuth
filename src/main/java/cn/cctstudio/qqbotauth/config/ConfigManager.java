package cn.cctstudio.qqbotauth.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

public final class ConfigManager {
    private final JavaPlugin plugin;
    private final Map<String, String> environment;
    private volatile PluginConfig current;

    public ConfigManager(JavaPlugin plugin) {
        this(plugin, System.getenv());
    }

    ConfigManager(JavaPlugin plugin, Map<String, String> environment) {
        this.plugin = plugin;
        this.environment = environment;
    }

    public PluginConfig load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        current = parse(plugin.getConfig());
        return current;
    }

    public PluginConfig reload() {
        plugin.reloadConfig();
        return parse(plugin.getConfig());
    }

    public void activate(PluginConfig config) {
        current = config;
    }

    public PluginConfig current() {
        PluginConfig value = current;
        if (value == null) {
            throw new IllegalStateException("Configuration has not been loaded");
        }
        return value;
    }

    private PluginConfig parse(FileConfiguration config) {
        boolean qqEnabled = envBoolean("QQBOTAUTH_QQ_ENABLED", config.getBoolean("qq.enabled", true));
        String appId = env("QQBOTAUTH_QQ_APP_ID", config.getString("qq.app-id", ""));
        String appSecret = env("QQBOTAUTH_QQ_APP_SECRET", config.getString("qq.app-secret", ""));
        Set<String> allowedGroups = stringSet(
                "QQBOTAUTH_QQ_ALLOWED_GROUP_OPENIDS", config.getStringList("qq.allowed-group-openids"));
        PluginConfig.QQ qq = new PluginConfig.QQ(
                qqEnabled,
                appId.trim(),
                appSecret.trim(),
                secureUri(config.getString("qq.api-base-url", "https://api.bot.qq.com"), "qq.api-base-url"),
                secureUri(config.getString("qq.access-token-url", "https://api.bot.qq.com/app/getAppAccessToken"), "qq.access-token-url"),
                config.getString("qq.group-number", "").trim(),
                allowedGroups,
                bounded(config.getInt("qq.connect-timeout-seconds", 15), 3, 120, "qq.connect-timeout-seconds"),
                bounded(config.getInt("qq.request-timeout-seconds", 15), 3, 120, "qq.request-timeout-seconds"),
                bounded(config.getInt("qq.reconnect-max-delay-seconds", 60), 5, 600, "qq.reconnect-max-delay-seconds")
        );
        Map<String, String> qqMessageValues = new LinkedHashMap<>();
        QQReplyMessages.defaults().forEach((key, fallback) -> qqMessageValues.put(
                key, config.getString("qq-messages." + key, fallback)));
        QQReplyMessages qqMessages = new QQReplyMessages(qqMessageValues);

        PluginConfig.Verification verification = new PluginConfig.Verification(
                bounded(config.getInt("verification.code-length", 6), 4, 12, "verification.code-length"),
                bounded(config.getLong("verification.expire-seconds", 120), 30, 86_400, "verification.expire-seconds")
        );

        PluginConfig.DatabaseMode mode = PluginConfig.DatabaseMode.parse(
                env("QQBOTAUTH_DATABASE_MODE", config.getString("database.mode", "sqlite"))
        );
        String sqliteName = config.getString("database.sqlite.file", "bindings.db").trim();
        Path sqliteFile = plugin.getDataFolder().toPath().resolve(Path.of(sqliteName).getFileName()).normalize();
        PluginConfig.MySql mySql = new PluginConfig.MySql(
                env("QQBOTAUTH_MYSQL_HOST", config.getString("database.mysql.host", "127.0.0.1")).trim(),
                envInt("QQBOTAUTH_MYSQL_PORT", config.getInt("database.mysql.port", 3306), 1, 65_535),
                env("QQBOTAUTH_MYSQL_DATABASE", config.getString("database.mysql.database", "qqbotauth")).trim(),
                env("QQBOTAUTH_MYSQL_USERNAME", config.getString("database.mysql.username", "")).trim(),
                env("QQBOTAUTH_MYSQL_PASSWORD", config.getString("database.mysql.password", "")),
                envBoolean("QQBOTAUTH_MYSQL_USE_SSL", config.getBoolean("database.mysql.use-ssl", false)),
                envBoolean("QQBOTAUTH_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL",
                        config.getBoolean("database.mysql.allow-public-key-retrieval", true))
        );
        PluginConfig.Database database = new PluginConfig.Database(
                mode,
                new PluginConfig.SQLite(sqliteFile),
                mySql
        );
        if (mode == PluginConfig.DatabaseMode.MYSQL && !mySql.isConfigured()) {
            throw new IllegalArgumentException("database.mode=mysql, but the MySQL settings are incomplete");
        }

        PluginConfig.Transfer transfer = new PluginConfig.Transfer(
                config.getBoolean("player.transfer.enabled", true),
                config.getString("player.transfer.verified-server", "lobby").trim(),
                bounded(config.getInt("player.transfer.delay-seconds", 5), 0, 3600,
                        "player.transfer.delay-seconds")
        );
        PluginConfig.Player player = new PluginConfig.Player(
                config.getBoolean("player.block-movement", true),
                config.getBoolean("player.block-interaction", true),
                config.getBoolean("player.block-server-command", true),
                normalizedCommands(config.getStringList("player.allowed-commands")),
                bounded(config.getInt("player.reminder-seconds", 15), 0, 3600, "player.reminder-seconds"),
                config.getBoolean("player.dialog-enabled", true),
                transfer
        );
        return new PluginConfig(qq, qqMessages, verification, database, player);
    }

    private String env(String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean envBoolean(String name, boolean fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private int envInt(String name, int fallback, int min, int max) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private Set<String> stringSet(String environmentName, List<String> configured) {
        String environmentValue = environment.get(environmentName);
        Iterable<String> values = environmentValue == null || environmentValue.isBlank()
                ? configured : Arrays.asList(environmentValue.split(","));
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizedCommands(List<String> configured) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : configured) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isBlank() && !normalized.chars().anyMatch(Character::isWhitespace)) {
                result.add(normalized);
            }
        }
        if (result.isEmpty()) {
            result.add("qqverify");
            result.add("qqyz");
            result.add("qqbotauth:qqverify");
            result.add("qqbotauth:qqyz");
        }
        return Set.copyOf(result);
    }

    private static URI secureUri(String raw, String key) {
        URI uri = URI.create(raw.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(key + " must use HTTPS");
        }
        return uri;
    }

    private static int bounded(int value, int min, int max, String key) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static long bounded(long value, long min, long max, String key) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return value;
    }
}
