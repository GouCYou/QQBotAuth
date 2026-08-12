package cn.cctstudio.qqbotauth.qq.event;

/**
 * TODO: Extension point for a future official QQ group-member-added event.
 * The current official QQ Bot documentation does not define a normal QQ group member join payload,
 * so QQBotAuth intentionally does not subscribe to or construct this event yet.
 */
public record GroupMemberAddEvent(String eventId) implements QQEvent {
}
