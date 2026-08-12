package cn.cctstudio.qqbotauth.command;

import cn.cctstudio.qqbotauth.qq.QQApiClient;
import cn.cctstudio.qqbotauth.qq.event.GroupMessageEvent;
import cn.cctstudio.qqbotauth.config.QQReplyMessages;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

public final class CommandContext {
    private final GroupMessageEvent event;
    private final List<String> arguments;
    private final QQApiClient apiClient;
    private final QQReplyMessages messages;
    private final AtomicInteger replySequence = new AtomicInteger(1);

    public CommandContext(
            GroupMessageEvent event,
            List<String> arguments,
            QQApiClient apiClient,
            QQReplyMessages messages
    ) {
        this.event = event;
        this.arguments = List.copyOf(arguments);
        this.apiClient = apiClient;
        this.messages = messages;
    }

    public GroupMessageEvent event() {
        return event;
    }

    public List<String> arguments() {
        return arguments;
    }

    public String argument(int index) {
        return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
    }

    public CompletionStage<Void> reply(String content) {
        return apiClient.sendGroupMessage(
                event.groupOpenId(), content, event.messageId(), replySequence.getAndIncrement()).thenApply(ignored -> null);
    }

    public CompletionStage<Void> replyMessage(String key) {
        return reply(messages.text(key));
    }

    public CompletionStage<Void> replyMessage(String key, Map<String, String> values) {
        return reply(messages.render(key, values));
    }

    public CompletionStage<Void> replyFailure(Throwable failure) {
        return replyMessage("operation-failed")
                .exceptionally(ignored -> null);
    }
}
