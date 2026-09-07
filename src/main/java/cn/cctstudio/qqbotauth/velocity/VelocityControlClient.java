package cn.cctstudio.qqbotauth.velocity;

import cn.cctstudio.qqbotauth.config.PluginConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Paper-side asynchronous control connection used when an unbound player may be on any proxy backend. */
public final class VelocityControlClient {
    private static final int MAX_ATTEMPTS = 6;

    private final Supplier<PluginConfig> config;
    private final Executor networkExecutor;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> warningLogger;

    public VelocityControlClient(
            Supplier<PluginConfig> config,
            Executor networkExecutor,
            ScheduledExecutorService scheduler,
            Consumer<String> warningLogger
    ) {
        this.config = config;
        this.networkExecutor = networkExecutor;
        this.scheduler = scheduler;
        this.warningLogger = warningLogger;
    }

    public CompletableFuture<Void> disconnectUnboundPlayer(UUID playerUuid) {
        return updateBindingState(playerUuid, false);
    }

    public CompletableFuture<Void> markBound(UUID playerUuid) {
        return updateBindingState(playerUuid, true);
    }

    public CompletableFuture<Void> updateBindingState(UUID playerUuid, boolean bound) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        CompletableFuture<Void> result = new CompletableFuture<>();
        attempt(playerUuid, bound, 1, result);
        return result;
    }

    private void attempt(UUID playerUuid, boolean bound, int attempt, CompletableFuture<Void> result) {
        if (result.isDone()) {
            return;
        }
        PluginConfig.VelocityControl settings = config.get().velocityControl();
        if (!settings.enabled()) {
            result.complete(null);
            return;
        }
        if (!settings.isConfigured()) {
            result.completeExceptionally(new IllegalStateException(
                    "velocity-control.secret is not configured"));
            return;
        }
        CompletableFuture.runAsync(() -> sendOnce(playerUuid, bound, settings), networkExecutor)
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                        return;
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    long delay = Math.min(15, 1L << Math.min(attempt - 1, 4));
                    try {
                        scheduler.schedule(() -> attempt(playerUuid, bound, attempt + 1, result),
                                delay, TimeUnit.SECONDS);
                    } catch (RejectedExecutionException exception) {
                        result.completeExceptionally(exception);
                    }
                });
        if (attempt == 1) {
            result.exceptionally(failure -> {
                warningLogger.accept("Could not update the player's binding state on Velocity: "
                        + rootMessage(failure));
                return null;
            });
        }
    }

    private void sendOnce(UUID playerUuid, boolean bound, PluginConfig.VelocityControl settings) {
        try {
            InetAddress address = InetAddress.getByName(settings.host());
            if (!address.isLoopbackAddress()) {
                throw new IOException("velocity-control.host must resolve to a loopback address");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, settings.port()), settings.connectTimeoutMillis());
                socket.setSoTimeout(settings.connectTimeoutMillis());
                try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()));
                     DataInputStream input = new DataInputStream(
                             new BufferedInputStream(socket.getInputStream()))) {
                    new VelocityControlMessage(playerUuid, bound
                            ? VelocityControlMessage.Action.BOUND
                            : VelocityControlMessage.Action.UNBOUND).writeTo(output, settings.secret());
                    output.flush();
                    if (input.readUnsignedByte() != 1) {
                        throw new IOException("Velocity rejected the control message");
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Velocity control connection failed", exception);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
