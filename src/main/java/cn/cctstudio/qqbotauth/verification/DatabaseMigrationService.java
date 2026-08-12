package cn.cctstudio.qqbotauth.verification;

import cn.cctstudio.qqbotauth.config.PluginConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class DatabaseMigrationService {
    private final PluginConfig.Database databaseConfig;
    private final Executor executor;

    public DatabaseMigrationService(PluginConfig.Database databaseConfig, Executor executor) {
        this.databaseConfig = databaseConfig;
        this.executor = executor;
    }

    public CompletableFuture<Integer> migrateSqliteToMySql() {
        if (!databaseConfig.mysql().isConfigured()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("请先在 config.yml 中完整配置 MySQL"));
        }
        BindingRepository source = new SQLiteBindingRepository(databaseConfig.sqlite().file(), executor);
        BindingRepository target = new MySqlBindingRepository(databaseConfig.mysql(), executor);
        return source.initialize()
                .thenCompose(ignored -> target.initialize())
                .thenCompose(ignored -> source.findAll())
                .thenCompose(bindings -> {
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (BindingRecord binding : bindings) {
                        chain = chain.thenCompose(ignored -> target.save(binding));
                    }
                    return chain.thenApply(ignored -> bindings.size());
                });
    }
}
