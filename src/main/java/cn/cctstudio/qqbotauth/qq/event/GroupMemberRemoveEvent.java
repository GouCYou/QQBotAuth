package cn.cctstudio.qqbotauth.qq.event;

/**
 * TODO: Extension point for a future official QQ group-member-removed event.
 * The current official QQ Bot documentation does not define a normal QQ group member leave payload,
 * so QQBotAuth intentionally does not subscribe to or construct this event yet.
 */
public record GroupMemberRemoveEvent(String eventId) implements QQEvent {
}
