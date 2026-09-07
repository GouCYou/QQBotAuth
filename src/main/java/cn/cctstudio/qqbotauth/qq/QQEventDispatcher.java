package cn.cctstudio.qqbotauth.qq;

import cn.cctstudio.qqbotauth.qq.event.EventHandler;
import cn.cctstudio.qqbotauth.qq.event.GroupMemberAddEvent;
import cn.cctstudio.qqbotauth.qq.event.GroupMemberRemoveEvent;
import cn.cctstudio.qqbotauth.qq.event.GroupMessageEvent;
import cn.cctstudio.qqbotauth.qq.event.QQEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class QQEventDispatcher {
    private final Map<Class<? extends QQEvent>, List<EventHandler<? extends QQEvent>>> handlers =
            new ConcurrentHashMap<>();
    private final Map<String, Long> recentMessages = new ConcurrentHashMap<>();
    private final Consumer<String> warningLogger;

    public QQEventDispatcher(Consumer<String> warningLogger) {
        this.warningLogger = warningLogger;
    }

    public <T extends QQEvent> void register(Class<T> eventType, EventHandler<T> handler) {
        handlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public CompletionStage<Void> dispatch(String eventType, String eventId, JsonObject data) {
        if ("GROUP_DEL_ROBOT".equals(eventType)) {
            warningLogger.accept("QQ Bot was removed from group " + QQApiClient.string(data, "group_openid")
                    + "; events and replies from that group are no longer available");
            return CompletableFuture.completedFuture(null);
        }
        Optional<? extends QQEvent> parsed = parse(eventType, eventId, data);
        if (parsed.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        QQEvent event = parsed.get();
        if (event instanceof GroupMessageEvent message && isDuplicate(message)) {
            return CompletableFuture.completedFuture(null);
        }
        List<EventHandler<? extends QQEvent>> selected = handlers.getOrDefault(event.getClass(), List.of());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (EventHandler<? extends QQEvent> handler : selected) {
            chain = chain.thenCompose(ignored -> invoke(handler, event).toCompletableFuture());
        }
        return chain.exceptionally(failure -> {
            warningLogger.accept("QQ event handler failed for " + eventType + ": " + rootMessage(failure));
            return null;
        });
    }

    private Optional<? extends QQEvent> parse(String eventType, String eventId, JsonObject data) {
        try {
            return switch (eventType) {
                case "GROUP_AT_MESSAGE_CREATE" -> Optional.of(new GroupMessageEvent(
                        eventId,
                        required(data, "id"),
                        messageIndex(data),
                        required(data, "group_openid"),
                        authorValue(data, "member_openid"),
                        authorValue(data, "member_role"),
                        QQApiClient.string(data, "content").trim(),
                        rfc3339(data, "timestamp")
                ));
                case "GROUP_MEMBER_REMOVE" -> Optional.of(new GroupMemberRemoveEvent(
                        eventId,
                        required(data, "group_openid"),
                        required(data, "member_openid"),
                        QQApiClient.string(data, "user_openid"),
                        unixSeconds(data, "timestamp")
                ));
                case "GROUP_MEMBER_ADD" -> Optional.of(new GroupMemberAddEvent(
                        eventId,
                        required(data, "group_openid"),
                        required(data, "member_openid"),
                        QQApiClient.string(data, "user_openid"),
                        unixSeconds(data, "timestamp")
                ));
                default -> Optional.empty();
            };
        } catch (RuntimeException exception) {
            warningLogger.accept("Ignored malformed QQ event " + eventType + ": " + rootMessage(exception));
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Void> invoke(EventHandler<? extends QQEvent> handler, QQEvent event) {
        return ((EventHandler<QQEvent>) handler).handle(event);
    }

    private boolean isDuplicate(GroupMessageEvent event) {
        long now = System.currentTimeMillis();
        if (recentMessages.size() > 2048) {
            recentMessages.entrySet().removeIf(entry -> entry.getValue() < now - 600_000);
        }
        String key = event.messageId() + ':' + event.messageIndex();
        return recentMessages.putIfAbsent(key, now) != null;
    }

    private String messageIndex(JsonObject data) {
        JsonObject scene = object(data, "message_scene");
        JsonElement extensions = scene.get("ext");
        if (extensions != null && extensions.isJsonArray()) {
            JsonArray array = extensions.getAsJsonArray();
            for (JsonElement value : array) {
                String text = value.getAsString();
                if (text.startsWith("msg_idx=")) {
                    return text.substring("msg_idx=".length());
                }
            }
        }
        return "";
    }

    private static String authorValue(JsonObject data, String key) {
        String value = QQApiClient.string(object(data, "author"), key);
        if (value.isBlank() && "member_openid".equals(key)) {
            value = QQApiClient.string(object(data, "author"), "id");
        }
        if (value.isBlank() && "member_openid".equals(key)) {
            throw new IllegalArgumentException("Missing author.member_openid");
        }
        return value;
    }

    private static String required(JsonObject data, String key) {
        String value = QQApiClient.string(data, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static JsonObject object(JsonObject data, String key) {
        JsonElement value = data.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static Instant rfc3339(JsonObject data, String key) {
        String raw = required(data, key);
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid " + key, exception);
        }
    }

    private static Instant unixSeconds(JsonObject data, String key) {
        long value = QQApiClient.longValue(data, key, Long.MIN_VALUE);
        if (value == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Missing or invalid " + key);
        }
        return Instant.ofEpochSecond(value);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
