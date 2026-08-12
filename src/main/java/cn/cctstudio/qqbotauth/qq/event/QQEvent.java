package cn.cctstudio.qqbotauth.qq.event;

public sealed interface QQEvent permits GroupMessageEvent, GroupMemberAddEvent, GroupMemberRemoveEvent {
    String eventId();
}
