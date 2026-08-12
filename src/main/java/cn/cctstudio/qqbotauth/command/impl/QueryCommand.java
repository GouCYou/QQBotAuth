package cn.cctstudio.qqbotauth.command.impl;

import cn.cctstudio.qqbotauth.command.Command;
import cn.cctstudio.qqbotauth.command.CommandContext;
import cn.cctstudio.qqbotauth.verification.BindingService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public final class QueryCommand implements Command {
    private final BindingService bindingService;

    public QueryCommand(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @Override
    public String name() {
        return "查询";
    }

    @Override
    public List<String> aliases() {
        return List.of("status");
    }

    @Override
    public String description() {
        return "查看当前 QQ 的绑定状态";
    }

    @Override
    public CompletionStage<Void> execute(CommandContext context) {
        return bindingService.findByQq(context.event().groupOpenId(), context.event().memberOpenId())
                .thenCompose(binding -> binding
                        .map(value -> context.replyMessage(
                                "query-bound", Map.of("minecraft", value.minecraftName())))
                        .orElseGet(() -> context.replyMessage("query-unbound")));
    }
}
