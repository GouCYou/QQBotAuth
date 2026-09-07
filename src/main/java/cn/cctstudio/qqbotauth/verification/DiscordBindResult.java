package cn.cctstudio.qqbotauth.verification;

public record DiscordBindResult(Status status, DiscordBindingRecord binding) {
    public enum Status {
        SUCCESS,
        ALREADY_BOUND,
        DISCORD_ALREADY_BOUND
    }
}
