package cn.cctstudio.qqbotauth.command.impl;

import cn.cctstudio.qqbotauth.command.Command;
import cn.cctstudio.qqbotauth.command.CommandContext;
import cn.cctstudio.qqbotauth.verification.BindingService;

import java.util.List;
import java.util.concurrent.CompletionStage;

public final class UnbindCommand implements Command {
    private final BindingService bindingService;

    public UnbindCommand(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @Override
    public String name() {
        return "解绑";
    }

    @Override
    public List<String> aliases() {
        return List.of("unbind");
    }

    @Override
    public String description() {
        return "解除当前 QQ 的绑定";
    }

    @Override
    public CompletionStage<Void> execute(CommandContext context) {
        return bindingService.unbind(context.event().groupOpenId(), context.event().memberOpenId())
                .thenCompose(deleted -> context.replyMessage(
                        deleted ? "unbind-success" : "unbind-not-bound"));
    }
}
