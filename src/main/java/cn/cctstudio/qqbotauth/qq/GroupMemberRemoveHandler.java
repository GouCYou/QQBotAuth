package cn.cctstudio.qqbotauth.qq;

import cn.cctstudio.qqbotauth.config.QQReplyMessages;
import cn.cctstudio.qqbotauth.qq.event.EventHandler;
import cn.cctstudio.qqbotauth.qq.event.GroupMemberRemoveEvent;
import cn.cctstudio.qqbotauth.verification.BindingService;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Removes a persisted binding when the official QQ Gateway reports that its member left the group. */
public final class GroupMemberRemoveHandler implements EventHandler<GroupMemberRemoveEvent> {
    private final BindingService bindingService;
    private final QQApiClient apiClient;
    private final QQGroupRegistry groupRegistry;
    private final Set<String> allowedGroupOpenIds;
    private final QQReplyMessages messages;
    private final Consumer<String> warningLogger;

    public GroupMemberRemoveHandler(
            BindingService bindingService,
            QQApiClient apiClient,
            QQGroupRegistry groupRegistry,
            Set<String> allowedGroupOpenIds,
            QQReplyMessages messages,
            Consumer<String> warningLogger
    ) {
        this.bindingService = bindingService;
        this.apiClient = apiClient;
        this.groupRegistry = groupRegistry;
        this.allowedGroupOpenIds = Set.copyOf(allowedGroupOpenIds);
        this.messages = messages;
        this.warningLogger = warningLogger;
    }

    @Override
    public CompletionStage<Void> handle(GroupMemberRemoveEvent event) {
        if (groupRegistry.observe(event.groupOpenId())) {
            warningLogger.accept("Observed QQ group_openid " + event.groupOpenId()
                    + "; add it to qq.allowed-group-openids to authorize this group");
        }
        if (!allowedGroupOpenIds.contains(event.groupOpenId())) {
            return CompletableFuture.completedFuture(null);
        }
        return bindingService.unbindRecord(event.groupOpenId(), event.memberOpenId())
                .thenCompose(binding -> binding.<CompletionStage<Void>>map(record -> {
                    String text = messages.render("member-left-unbound", Map.of(
                            "minecraft", record.minecraftName()));
                    return apiClient.sendGroupMessage(event.groupOpenId(), text, "", 1)
                            .thenApply(ignored -> null);
                }).orElseGet(() -> CompletableFuture.completedFuture(null)));
    }
}
