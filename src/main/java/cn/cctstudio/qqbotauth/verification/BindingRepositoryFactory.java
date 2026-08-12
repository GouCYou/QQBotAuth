package cn.cctstudio.qqbotauth.verification;

import cn.cctstudio.qqbotauth.config.PluginConfig;

import java.util.concurrent.Executor;

public final class BindingRepositoryFactory {
    private BindingRepositoryFactory() {
    }

    public static BindingRepository create(PluginConfig.Database config, Executor executor) {
        return switch (config.mode()) {
            case SQLITE -> new SQLiteBindingRepository(config.sqlite().file(), executor);
            case MYSQL -> new MySqlBindingRepository(config.mysql(), executor);
        };
    }
}
