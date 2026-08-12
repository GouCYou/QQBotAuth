package cn.cctstudio.qqbotauth.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class PluginExecutors implements AutoCloseable {
    private final ExecutorService network;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService database;

    public PluginExecutors(Consumer<Throwable> errorHandler) {
        network = Executors.newFixedThreadPool(3, new NamedThreadFactory("QQBotAuth-Network-", errorHandler));
        scheduler = Executors.newScheduledThreadPool(2,
                new NamedThreadFactory("QQBotAuth-Scheduler-", errorHandler));
        database = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("QQBotAuth-Database-", errorHandler));
    }

    public ExecutorService network() {
        return network;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public ExecutorService database() {
        return database;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        network.shutdownNow();
        database.shutdown();
        try {
            if (!database.awaitTermination(2, TimeUnit.SECONDS)) {
                database.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            database.shutdownNow();
        }
    }
}
