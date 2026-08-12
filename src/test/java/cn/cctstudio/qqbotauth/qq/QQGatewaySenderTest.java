package cn.cctstudio.qqbotauth.qq;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQGatewaySenderTest {
    @Test
    void waitsForPendingFrameBeforeSendingNextFrame() {
        List<String> sentPayloads = new ArrayList<>();
        List<CompletableFuture<WebSocket>> sends = new ArrayList<>();
        AtomicReference<WebSocket> socketReference = new AtomicReference<>();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class},
                (proxy, method, arguments) -> {
                    if ("sendText".equals(method.getName())) {
                        sentPayloads.add((String) arguments[0]);
                        CompletableFuture<WebSocket> future = new CompletableFuture<>();
                        sends.add(future);
                        return future;
                    }
                    if ("isOutputClosed".equals(method.getName())) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        socketReference.set(socket);

        QQGatewaySender sender = new QQGatewaySender();
        CompletionStage<Void> first = sender.send(socket, "heartbeat", () -> true);
        CompletionStage<Void> second = sender.send(socket, "identify", () -> true);

        assertEquals(List.of("heartbeat"), sentPayloads);
        assertFalse(second.toCompletableFuture().isDone());

        sends.get(0).complete(socketReference.get());
        assertEquals(List.of("heartbeat", "identify"), sentPayloads);
        sends.get(1).complete(socketReference.get());

        assertTrue(first.toCompletableFuture().isDone());
        assertTrue(second.toCompletableFuture().isDone());
    }
}
