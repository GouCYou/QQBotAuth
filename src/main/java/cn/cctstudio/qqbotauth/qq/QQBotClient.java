package cn.cctstudio.qqbotauth.qq;

import cn.cctstudio.qqbotauth.config.PluginConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class QQBotClient implements WebSocket.Listener, AutoCloseable {
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;
    private static final int GROUP_MEMBER_EVENT = 1 << 24;
    private static final int GROUP_AND_C2C_EVENT = 1 << 25;
    private static final int INTENTS = GROUP_MEMBER_EVENT | GROUP_AND_C2C_EVENT;

    private final PluginConfig.QQ config;
    private final QQApiClient apiClient;
    private final QQEventDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warningLogger;
    private final Gson gson = new Gson();
    private final QQGatewaySender gatewaySender = new QQGatewaySender();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final StringBuilder textBuffer = new StringBuilder();

    private volatile WebSocket webSocket;
    private volatile GatewayState state = GatewayState.STOPPED;
    private volatile String sessionId;
    private volatile Long sequence;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> heartbeatWatchdog;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Instant lastHeartbeatAck;

    public QQBotClient(
            PluginConfig.QQ config,
            QQApiClient apiClient,
            QQEventDispatcher dispatcher,
            ScheduledExecutorService scheduler,
            Consumer<String> infoLogger,
            Consumer<String> warningLogger
    ) {
        this.config = config;
        this.apiClient = apiClient;
        this.dispatcher = dispatcher;
        this.scheduler = scheduler;
        this.infoLogger = infoLogger;
        this.warningLogger = warningLogger;
    }

    public void start() {
        if (!config.enabled()) {
            state = GatewayState.DISABLED;
            infoLogger.accept("QQ Bot is disabled by configuration");
            return;
        }
        if (!config.hasCredentials()) {
            state = GatewayState.FAILED;
            warningLogger.accept("QQ Bot credentials are missing; Gateway connection was not started");
            return;
        }
        if (running.compareAndSet(false, true)) {
            connect(false);
        }
    }

    public GatewayState state() {
        return state;
    }

    public boolean isReady() {
        return state == GatewayState.READY;
    }

    public String sessionIdForStatus() {
        String id = sessionId;
        if (id == null || id.isBlank()) {
            return "-";
        }
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }

    public Long sequence() {
        return sequence;
    }

    private void connect(boolean resume) {
        if (!running.get()) {
            return;
        }
        long currentGeneration = generation.incrementAndGet();
        cancelHeartbeat();
        state = resume ? GatewayState.RECONNECTING : GatewayState.FETCHING_GATEWAY;
        infoLogger.accept(resume ? "Reconnecting to QQ Gateway..." : "Connecting to QQ Gateway...");
        apiClient.getGatewayUrl().thenCompose(gateway -> {
            if (!running.get() || currentGeneration != generation.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Gateway connection superseded"));
            }
            state = GatewayState.CONNECTING;
            return apiClient.httpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                    .buildAsync(gateway, this);
        }).thenAccept(socket -> {
            if (!running.get() || currentGeneration != generation.get()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin stopping");
                return;
            }
            this.webSocket = socket;
        }).exceptionally(failure -> {
            if (running.get() && currentGeneration == generation.get()) {
                warningLogger.accept("QQ Gateway connection failed: " + rootMessage(failure));
                scheduleReconnect(canResume());
            }
            return null;
        });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        gatewaySender.reset();
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (this.webSocket != webSocket) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        synchronized (textBuffer) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                handlePayload(webSocket, payload);
            }
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return webSocket.sendPong(message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        WebSocket active = this.webSocket;
        if (active != null && active != webSocket) {
            return CompletableFuture.completedFuture(null);
        }
        if (this.webSocket == webSocket) {
            this.webSocket = null;
        }
        cancelHeartbeat();
        if (running.get()) {
            if (statusCode == 4914 || statusCode == 4915) {
                state = GatewayState.FAILED;
                running.set(false);
                warningLogger.accept("QQ Gateway rejected the bot (close code " + statusCode + "); automatic reconnect stopped");
            } else {
                warningLogger.accept("QQ Gateway disconnected (code " + statusCode + ")");
                boolean resume = statusCode == 4009;
                if (statusCode == 4006 || statusCode == 4007) {
                    clearSession();
                    resume = false;
                }
                scheduleReconnect(resume && canResume());
            }
        } else {
            state = GatewayState.CLOSED;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (running.get() && (this.webSocket == null || this.webSocket == webSocket)) {
            warningLogger.accept("QQ Gateway error: " + rootMessage(error));
            reconnectNow(canResume());
        }
    }

    private void handlePayload(WebSocket socket, String raw) {
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Gateway payload was not an object");
            }
            JsonObject payload = parsed.getAsJsonObject();
            int opcode = payload.has("op") ? payload.get("op").getAsInt() : -1;
            if (payload.has("s") && !payload.get("s").isJsonNull()) {
                sequence = payload.get("s").getAsLong();
            }
            switch (opcode) {
                case OP_DISPATCH -> onDispatch(payload);
                case OP_HEARTBEAT -> sendHeartbeat(socket);
                case OP_RECONNECT -> reconnectNow(true);
                case OP_INVALID_SESSION -> onInvalidSession(payload);
                case OP_HELLO -> onHello(socket, payload);
                case OP_HEARTBEAT_ACK -> lastHeartbeatAck = Instant.now();
                default -> warningLogger.accept("Ignored unknown QQ Gateway opcode " + opcode);
            }
        } catch (RuntimeException exception) {
            warningLogger.accept("Ignored malformed QQ Gateway payload: " + rootMessage(exception));
        }
    }

    private void onHello(WebSocket socket, JsonObject payload) {
        JsonObject data = object(payload, "d");
        long heartbeatInterval = QQApiClient.longValue(data, "heartbeat_interval", 0);
        if (heartbeatInterval <= 0) {
            warningLogger.accept("QQ Gateway Hello did not contain a valid heartbeat interval");
            reconnectNow(false);
            return;
        }
        state = GatewayState.AUTHENTICATING;
        startHeartbeat(socket, heartbeatInterval);
        apiClient.getAccessToken().thenAccept(token -> {
            if (!running.get() || socket != webSocket) {
                return;
            }
            if (canResume()) {
                sendResume(socket, token);
            } else {
                sendIdentify(socket, token);
            }
        }).exceptionally(failure -> {
            warningLogger.accept("QQ Gateway authentication failed: " + rootMessage(failure));
            reconnectNow(false);
            return null;
        });
    }

    private void onDispatch(JsonObject payload) {
        String type = QQApiClient.string(payload, "t");
        String eventId = QQApiClient.string(payload, "id");
        JsonObject data = object(payload, "d");
        if ("READY".equals(type)) {
            sessionId = QQApiClient.string(data, "session_id");
            reconnectAttempts.set(0);
            state = GatewayState.READY;
            infoLogger.accept("QQ Gateway READY; event receiving is active");
            return;
        }
        if ("RESUMED".equals(type)) {
            reconnectAttempts.set(0);
            state = GatewayState.READY;
            infoLogger.accept("QQ Gateway session resumed");
            return;
        }
        dispatcher.dispatch(type, eventId, data);
    }

    private void onInvalidSession(JsonObject payload) {
        boolean resumable = payload.has("d") && !payload.get("d").isJsonNull() && payload.get("d").getAsBoolean();
        if (!resumable) {
            clearSession();
        }
        scheduleReconnect(resumable && canResume());
    }

    private void sendIdentify(WebSocket socket, String accessToken) {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + accessToken);
        data.addProperty("intents", INTENTS);
        var shard = new com.google.gson.JsonArray();
        shard.add(0);
        shard.add(1);
        data.add("shard", shard);
        JsonObject properties = new JsonObject();
        properties.addProperty("$os", System.getProperty("os.name", "unknown"));
        properties.addProperty("$browser", "QQBotAuth");
        properties.addProperty("$device", "QQBotAuth");
        data.add("properties", properties);
        send(socket, OP_IDENTIFY, data);
    }

    private void sendResume(WebSocket socket, String accessToken) {
        JsonObject data = new JsonObject();
        data.addProperty("token", "QQBot " + accessToken);
        data.addProperty("session_id", sessionId);
        data.addProperty("seq", sequence);
        send(socket, OP_RESUME, data);
    }

    private void startHeartbeat(WebSocket socket, long intervalMillis) {
        cancelHeartbeat();
        lastHeartbeatAck = Instant.now();
        heartbeatTask = scheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(socket), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        heartbeatWatchdog = scheduler.scheduleAtFixedRate(() -> {
            Instant ack = lastHeartbeatAck;
            if (running.get() && socket == webSocket && ack != null
                    && ack.plusMillis(intervalMillis * 2L).isBefore(Instant.now())) {
                warningLogger.accept("QQ Gateway heartbeat timed out; reconnecting");
                reconnectNow(true);
            }
        }, intervalMillis * 2L, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat(WebSocket socket) {
        if (!running.get() || socket != webSocket || socket.isOutputClosed()) {
            return;
        }
        JsonElement data = sequence == null ? JsonNull.INSTANCE : gson.toJsonTree(sequence);
        send(socket, OP_HEARTBEAT, data);
    }

    private void send(WebSocket socket, int opcode, JsonElement data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", opcode);
        payload.add("d", data);
        gatewaySender.send(
                socket,
                gson.toJson(payload),
                () -> running.get() && socket == webSocket && !socket.isOutputClosed()
        ).exceptionally(failure -> {
            if (running.get()) {
                warningLogger.accept("Failed to send QQ Gateway payload: " + rootMessage(failure));
            }
            return null;
        });
    }

    private void reconnectNow(boolean resume) {
        if (!running.get()) {
            return;
        }
        WebSocket current = webSocket;
        webSocket = null;
        generation.incrementAndGet();
        cancelHeartbeat();
        if (!resume) {
            clearSession();
        }
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(4000, "Reconnecting");
        }
        scheduleReconnect(resume && canResume());
    }

    private void scheduleReconnect(boolean resume) {
        if (!running.get()) {
            return;
        }
        ScheduledFuture<?> pending = reconnectTask;
        if (pending != null && !pending.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(config.reconnectMaxDelaySeconds(), 1L << Math.min(attempt - 1, 6));
        state = GatewayState.RECONNECTING;
        reconnectTask = scheduler.schedule(() -> connect(resume), delay, TimeUnit.SECONDS);
    }

    private boolean canResume() {
        return sessionId != null && !sessionId.isBlank() && sequence != null;
    }

    private void clearSession() {
        sessionId = null;
        sequence = null;
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
        ScheduledFuture<?> watchdog = heartbeatWatchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
            heartbeatWatchdog = null;
        }
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            state = GatewayState.CLOSED;
            return;
        }
        generation.incrementAndGet();
        cancelHeartbeat();
        ScheduledFuture<?> pending = reconnectTask;
        if (pending != null) {
            pending.cancel(false);
        }
        WebSocket current = webSocket;
        webSocket = null;
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled")
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(failure -> {
                        current.abort();
                        return null;
                    });
        }
        state = GatewayState.CLOSED;
        infoLogger.accept("QQ Gateway connection closed");
    }

    private static JsonObject object(JsonObject data, String key) {
        JsonElement value = data.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return Objects.toString(root.getMessage(), root.getClass().getSimpleName());
    }
}
