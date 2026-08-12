package cn.cctstudio.qqbotauth.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();
    private final Consumer<Throwable> errorHandler;

    public NamedThreadFactory(String prefix, Consumer<Throwable> errorHandler) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((ignored, throwable) -> errorHandler.accept(throwable));
        return thread;
    }
}
