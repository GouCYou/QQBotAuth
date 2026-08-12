package cn.cctstudio.qqbotauth.velocity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class CommandGatePolicy {
    static final String DEFAULT_ALLOWED_COMMANDS = String.join(",",
            "login", "l", "register", "reg", "email", "captcha",
            "qqverify", "qqyz",
            "authme:login", "authme:l", "authme:register", "authme:reg",
            "authme:email", "authme:captcha",
            "qqbotauth:qqverify", "qqbotauth:qqyz");

    private final Set<String> allowedCommands;

    CommandGatePolicy(Set<String> allowedCommands) {
        this.allowedCommands = Set.copyOf(allowedCommands);
    }

    static CommandGatePolicy fromCsv(String configured) {
        Set<String> commands = new LinkedHashSet<>();
        Arrays.stream(configured.split(","))
                .map(CommandGatePolicy::commandLabel)
                .filter(label -> !label.isBlank())
                .forEach(commands::add);
        if (commands.isEmpty()) {
            return fromCsv(DEFAULT_ALLOWED_COMMANDS);
        }
        return new CommandGatePolicy(commands);
    }

    boolean allows(String command) {
        return allowedCommands.contains(commandLabel(command));
    }

    static String commandLabel(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.stripLeading();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).stripLeading();
        }
        int separator = firstWhitespace(normalized);
        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
