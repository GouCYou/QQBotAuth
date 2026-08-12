package cn.cctstudio.qqbotauth.qq;

public enum GatewayState {
    DISABLED,
    STOPPED,
    FETCHING_GATEWAY,
    CONNECTING,
    AUTHENTICATING,
    READY,
    RECONNECTING,
    CLOSED,
    FAILED
}
