package cn.cctstudio.qqbotauth.command;

import cn.cctstudio.qqbotauth.qq.QQApiClient;
import cn.cctstudio.qqbotauth.qq.QQGroupRegistry;
import cn.cctstudio.qqbotauth.qq.event.GroupMessageEvent;
import cn.cctstudio.qqbotauth.config.QQReplyMessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.Set;

public final class CommandManager {
    private static final Pattern LEADING_MENTION = Pattern.compile("^\\s*(?:<@!?[^>]+>|@\\S+)\\s*");

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final QQApiClient apiClient;
    private final QQGroupRegistry groupRegistry;
    private final Set<String> allowedGroupOpenIds;
    private final Consumer<String> warningLogger;
    private final QQReplyMessages messages;

    public CommandManager(
            QQApiClient apiClient,
            QQGroupRegistry groupRegistry,
            Set<String> allowedGroupOpenIds,
            QQReplyMessages messages,
            Consumer<String> warningLogger
    ) {
        this.apiClient = apiClient;
        this.groupRegistry = groupRegistry;
        this.allowedGroupOpenIds = Set.copyOf(allowedGroupOpenIds);
        this.messages = messages;
        this.warningLogger = warningLogger;
    }

    public void register(Command command) {
        addName(command.name(), command);
        for (String alias : command.aliases()) {
            addName(alias, command);
        }
    }

    public List<Command> commands() {
        return commands.values().stream().distinct().toList();
    }

    public CompletionStage<Void> handle(GroupMessageEvent event) {
        if (groupRegistry.observe(event.groupOpenId())) {
            warningLogger.accept("Observed QQ group_openid " + event.groupOpenId()
                    + "; add it to qq.allowed-group-openids to authorize commands from this group");
        }
        if (!allowedGroupOpenIds.contains(event.groupOpenId())) {
            return CompletableFuture.completedFuture(null);
        }
        ParsedCommand parsed = parse(event.content());
        if (parsed == null) {
            return CompletableFuture.completedFuture(null);
        }
        Command command = commands.get(normalize(parsed.name()));
        CommandContext context = new CommandContext(event, parsed.arguments(), apiClient, messages);
        if (command == null) {
            return context.replyMessage("unknown-command");
        }
        try {
            return command.execute(context).exceptionallyCompose(failure -> {
                warningLogger.accept("QQ command failed (" + command.name() + "): " + rootMessage(failure));
                return context.replyFailure(failure);
            });
        } catch (RuntimeException exception) {
            warningLogger.accept("QQ command failed (" + command.name() + "): " + rootMessage(exception));
            return context.replyFailure(exception);
        }
    }

    private void addName(String name, Command command) {
        String normalized = normalize(name);
        if (normalized.isBlank() || commands.putIfAbsent(normalized, command) != null) {
            throw new IllegalArgumentException("Duplicate or blank QQ command name: " + name);
        }
    }

    static ParsedCommand parse(String raw) {
        if (raw == null) {
            return null;
        }
        String content = LEADING_MENTION.matcher(raw).replaceFirst("").trim();
        while (content.startsWith("/")) {
            content = content.substring(1).trim();
        }
        if (content.isBlank()) {
            return new ParsedCommand("帮助", List.of());
        }
        String[] parts = content.split("\\s+");
        List<String> arguments = new ArrayList<>();
        String commandName = parts[0];

        // Players commonly omit the space in “绑定 ABC123”. Only split the exact
        // Chinese bind prefix so unrelated command names keep their original meaning.
        if (commandName.startsWith("绑定") && commandName.length() > "绑定".length()) {
            arguments.add(commandName.substring("绑定".length()));
            commandName = "绑定";
        }
        for (int i = 1; i < parts.length; i++) {
            arguments.add(parts[i]);
        }
        return new ParsedCommand(commandName, List.copyOf(arguments));
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    record ParsedCommand(String name, List<String> arguments) {
    }
}
