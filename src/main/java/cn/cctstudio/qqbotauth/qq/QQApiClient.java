package cn.cctstudio.qqbotauth.qq;

import cn.cctstudio.qqbotauth.config.PluginConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class QQApiClient {
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final PluginConfig.QQ config;
    private final HttpClient httpClient;
    private final Gson gson;
    private final Clock clock;
    private final Consumer<String> warningLogger;
    private final Object tokenLock = new Object();
    private volatile CachedToken cachedToken;
    private CompletableFuture<CachedToken> tokenRequest;

    public QQApiClient(PluginConfig.QQ config, Executor executor, Consumer<String> warningLogger) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new Gson(), Clock.systemUTC(), warningLogger);
    }

    QQApiClient(
            PluginConfig.QQ config,
            HttpClient httpClient,
            Gson gson,
            Clock clock,
            Consumer<String> warningLogger
    ) {
        this.config = config;
        this.httpClient = httpClient;
        this.gson = gson;
        this.clock = clock;
        this.warningLogger = warningLogger;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public CompletableFuture<String> getAccessToken() {
        CachedToken available = cachedToken;
        if (available != null && available.usableAt(clock.instant())) {
            return CompletableFuture.completedFuture(available.value());
        }
        synchronized (tokenLock) {
            available = cachedToken;
            if (available != null && available.usableAt(clock.instant())) {
                return CompletableFuture.completedFuture(available.value());
            }
            if (tokenRequest == null || tokenRequest.isDone()) {
                tokenRequest = requestAccessToken();
                tokenRequest.whenComplete((token, failure) -> {
                    synchronized (tokenLock) {
                        if (failure == null) {
                            cachedToken = token;
                        }
                        tokenRequest = null;
                    }
                });
            }
            return tokenRequest.thenApply(CachedToken::value);
        }
    }

    public void invalidateAccessToken() {
        cachedToken = null;
    }

    public CompletableFuture<URI> getGatewayUrl() {
        return authenticatedJson("GET", URI.create(trimTrailingSlash(config.apiBaseUrl().toString()) + "/gateway"), null, true)
                .thenApply(json -> {
                    String url = string(json, "url");
                    if (url.isBlank()) {
                        throw new QQApiException(200, 0, "Gateway response did not contain a URL", "");
                    }
                    URI gateway = URI.create(url);
                    if (!"wss".equalsIgnoreCase(gateway.getScheme())) {
                        throw new QQApiException(200, 0, "Gateway URL did not use WSS", "");
                    }
                    return gateway;
                });
    }

    public CompletableFuture<QQMessageReceipt> sendGroupMessage(
            String groupOpenId,
            String content,
            String replyMessageId,
            int messageSequence
    ) {
        JsonObject body = textMessage(content, replyMessageId, messageSequence);
        URI endpoint = endpoint("/v2/groups/" + encodePath(groupOpenId) + "/messages");
        return authenticatedJson("POST", endpoint, body, true).thenApply(this::messageReceipt);
    }

    public CompletableFuture<QQMessageReceipt> sendPrivateMessage(
            String userOpenId,
            String content,
            String replyMessageId,
            int messageSequence
    ) {
        JsonObject body = textMessage(content, replyMessageId, messageSequence);
        URI endpoint = endpoint("/v2/users/" + encodePath(userOpenId) + "/messages");
        return authenticatedJson("POST", endpoint, body, true).thenApply(this::messageReceipt);
    }

    private CompletableFuture<CachedToken> requestAccessToken() {
        JsonObject body = new JsonObject();
        body.addProperty("appId", config.appId());
        body.addProperty("clientSecret", config.appSecret());
        HttpRequest request = HttpRequest.newBuilder(config.accessTokenUrl())
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    JsonObject json = parseObject(response.body(), response.statusCode(), "");
                    if (response.statusCode() < 200 || response.statusCode() >= 300 || json.has("err_code")) {
                        throw apiFailure(response.statusCode(), response, json);
                    }
                    String token = string(json, "access_token");
                    long expires = longValue(json, "expires_in", 0);
                    if (token.isBlank() || expires <= 0) {
                        throw new QQApiException(response.statusCode(), 0,
                                "AccessToken response was incomplete", traceId(response, json));
                    }
                    return new CachedToken(token, clock.instant().plusSeconds(expires));
                });
    }

    private CompletableFuture<JsonObject> authenticatedJson(
            String method,
            URI endpoint,
            JsonObject body,
            boolean retryAuthentication
    ) {
        return getAccessToken().thenCompose(token -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                    .header("Authorization", "QQBot " + token)
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json; charset=utf-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                gson.toJson(body), StandardCharsets.UTF_8));
            }
            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }).thenCompose(response -> {
            JsonObject json = parseObject(response.body(), response.statusCode(), response.headers()
                    .firstValue("X-Tps-trace-ID").orElse(""));
            if (response.statusCode() == 401 && retryAuthentication) {
                invalidateAccessToken();
                return authenticatedJson(method, endpoint, body, false);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300 || hasApiError(json)) {
                return CompletableFuture.failedFuture(apiFailure(response.statusCode(), response, json));
            }
            return CompletableFuture.completedFuture(json);
        });
    }

    private JsonObject textMessage(String content, String replyMessageId, int messageSequence) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("QQ message content cannot be blank");
        }
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", 0);
        body.addProperty("content", content);
        if (replyMessageId != null && !replyMessageId.isBlank()) {
            body.addProperty("msg_id", replyMessageId);
            body.addProperty("msg_seq", Math.max(1, messageSequence));
        }
        return body;
    }

    private QQMessageReceipt messageReceipt(JsonObject json) {
        String rawTime = string(json, "timestamp");
        Instant timestamp = clock.instant();
        if (!rawTime.isBlank()) {
            try {
                timestamp = OffsetDateTime.parse(rawTime).toInstant();
            } catch (DateTimeParseException exception) {
                warningLogger.accept("QQ API returned an unrecognized message timestamp");
            }
        }
        return new QQMessageReceipt(string(json, "id"), timestamp);
    }

    private QQApiException apiFailure(int status, HttpResponse<String> response, JsonObject json) {
        return new QQApiException(
                status,
                longValue(json, "err_code", 0),
                string(json, "message"),
                traceId(response, json)
        );
    }

    private String traceId(HttpResponse<?> response, JsonObject json) {
        String bodyTrace = string(json, "trace_id");
        return bodyTrace.isBlank()
                ? response.headers().firstValue("X-Tps-trace-ID").orElse("")
                : bodyTrace;
    }

    private JsonObject parseObject(String raw, int status, String traceId) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException exception) {
            throw new CompletionException(new QQApiException(status, 0,
                    "QQ API returned invalid JSON", traceId));
        }
        throw new CompletionException(new QQApiException(status, 0,
                "QQ API returned a non-object JSON response", traceId));
    }

    private boolean hasApiError(JsonObject json) {
        return json.has("err_code") && longValue(json, "err_code", 0) != 0;
    }

    private URI endpoint(String path) {
        return URI.create(trimTrailingSlash(config.apiBaseUrl().toString()) + path);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encodePath(String value) {
        Objects.requireNonNull(value, "value");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean usableAt(Instant now) {
            return now.isBefore(expiresAt.minus(REFRESH_MARGIN));
        }
    }
}
