package cn.cctstudio.qqbotauth.qq.event;

import java.time.Instant;

/** Official QQ {@code GROUP_MEMBER_ADD} event, currently reserved for future business handling. */
public record GroupMemberAddEvent(
        String eventId,
        String groupOpenId,
        String memberOpenId,
        String userOpenId,
        Instant timestamp
) implements QQEvent {
}
