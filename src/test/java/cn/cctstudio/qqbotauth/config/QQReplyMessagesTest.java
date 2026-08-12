package cn.cctstudio.qqbotauth.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QQReplyMessagesTest {
    @Test
    void rendersConfiguredReplyPlaceholders() {
        Map<String, String> configured = new HashMap<>(QQReplyMessages.defaults());
        configured.put("bind-success", "欢迎 <minecraft>，绑定完成");
        QQReplyMessages messages = new QQReplyMessages(configured);

        assertEquals("欢迎 PlayerOne，绑定完成",
                messages.render("bind-success", Map.of("minecraft", "PlayerOne")));
    }
}
