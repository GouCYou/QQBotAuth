package cn.cctstudio.qqbotauth.qq.event;

import java.time.Instant;

/**
 * Official QQ {@code GROUP_MEMBER_REMOVE} event.
 */
public record GroupMemberRemoveEvent(
        String eventId,
        String groupOpenId,
        String memberOpenId,
        String userOpenId,
        Instant timestamp
) implements QQEvent {
}
