package cn.cctstudio.qqbotauth.qq;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Serializes WebSocket frames because the JDK client permits only one pending send operation. */
final class QQGatewaySender {
    private final Object lock = new Object();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    CompletionStage<Void> send(WebSocket socket, String payload, BooleanSupplier stillValid) {
        synchronized (lock) {
            CompletableFuture<Void> result = tail.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> {
                        if (!stillValid.getAsBoolean()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return socket.sendText(payload, true).thenApply(sent -> null);
                    });
            tail = result.handle((ignored, failure) -> null);
            return result;
        }
    }

    void reset() {
        synchronized (lock) {
            tail = CompletableFuture.completedFuture(null);
        }
    }
}
