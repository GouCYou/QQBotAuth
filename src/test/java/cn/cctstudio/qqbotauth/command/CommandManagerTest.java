package cn.cctstudio.qqbotauth.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandManagerTest {
    @Test
    void parsesBindWithOrWithoutSpace() {
        assertParsed("绑定 ABC123", "绑定", List.of("ABC123"));
        assertParsed("绑定ABC123", "绑定", List.of("ABC123"));
    }

    @Test
    void parsesCompactBindAfterMentionAndSlash() {
        assertParsed("<@!bot-id>绑定ABC123", "绑定", List.of("ABC123"));
        assertParsed("/绑定ABC123", "绑定", List.of("ABC123"));
    }

    @Test
    void leavesOtherCommandsUnchanged() {
        assertParsed("查询绑定", "查询绑定", List.of());
        assertParsed("帮助", "帮助", List.of());
    }

    private static void assertParsed(String input, String command, List<String> arguments) {
        CommandManager.ParsedCommand parsed = CommandManager.parse(input);
        assertEquals(command, parsed.name());
        assertEquals(arguments, parsed.arguments());
    }
}
