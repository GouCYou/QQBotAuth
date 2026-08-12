package cn.cctstudio.qqbotauth.command.impl;

import cn.cctstudio.qqbotauth.command.Command;
import cn.cctstudio.qqbotauth.command.CommandContext;
import cn.cctstudio.qqbotauth.verification.BindResult;
import cn.cctstudio.qqbotauth.verification.BindingService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public final class BindCommand implements Command {
    private final BindingService bindingService;

    public BindCommand(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @Override
    public String name() {
        return "绑定";
    }

    @Override
    public List<String> aliases() {
        return List.of("bind");
    }

    @Override
    public String description() {
        return "使用游戏内验证码绑定账号";
    }

    @Override
    public CompletionStage<Void> execute(CommandContext context) {
        String code = context.argument(0);
        if (code.isBlank()) {
            return context.replyMessage("bind-usage");
        }
        return bindingService.bind(code, context.event().groupOpenId(), context.event().memberOpenId())
                .thenCompose(result -> switch (result.status()) {
                    case SUCCESS -> context.replyMessage(
                            "bind-success", Map.of("minecraft", result.binding().minecraftName()));
                    case INVALID_OR_EXPIRED_CODE -> context.replyMessage("bind-invalid-code");
                    case QQ_ALREADY_BOUND -> context.replyMessage("bind-qq-already-bound");
                    case MINECRAFT_ALREADY_BOUND -> context.replyMessage("bind-minecraft-already-bound");
                    case CODE_ALREADY_USED -> context.replyMessage("bind-code-used");
                });
    }
}
