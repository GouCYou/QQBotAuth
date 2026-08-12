package cn.cctstudio.qqbotauth.verification;

public record BindResult(Status status, BindingRecord binding) {
    public enum Status {
        SUCCESS,
        INVALID_OR_EXPIRED_CODE,
        QQ_ALREADY_BOUND,
        MINECRAFT_ALREADY_BOUND,
        CODE_ALREADY_USED
    }

    public static BindResult of(Status status) {
        return new BindResult(status, null);
    }
}
