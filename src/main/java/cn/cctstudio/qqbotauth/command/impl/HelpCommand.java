package cn.cctstudio.qqbotauth.command.impl;

import cn.cctstudio.qqbotauth.command.Command;
import cn.cctstudio.qqbotauth.command.CommandContext;

import java.util.List;
import java.util.concurrent.CompletionStage;

public final class HelpCommand implements Command {
    @Override
    public String name() {
        return "帮助";
    }

    @Override
    public List<String> aliases() {
        return List.of("help");
    }

    @Override
    public String description() {
        return "查看验证指令说明";
    }

    @Override
    public CompletionStage<Void> execute(CommandContext context) {
        return context.replyMessage("help");
    }
}
