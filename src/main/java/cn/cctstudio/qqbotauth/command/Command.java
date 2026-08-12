package cn.cctstudio.qqbotauth.command;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface Command {
    String name();

    default List<String> aliases() {
        return List.of();
    }

    String description();

    CompletionStage<Void> execute(CommandContext context);
}
