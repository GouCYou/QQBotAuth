package cn.cctstudio.qqbotauth.qq.event;

import java.time.Instant;

public record GroupMessageEvent(
        String eventId,
        String messageId,
        String messageIndex,
        String groupOpenId,
        String memberOpenId,
        String memberRole,
        String content,
        Instant timestamp
) implements QQEvent {
}
