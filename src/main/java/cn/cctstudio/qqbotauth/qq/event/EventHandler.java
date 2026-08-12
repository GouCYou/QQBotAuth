package cn.cctstudio.qqbotauth.qq.event;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface EventHandler<T extends QQEvent> {
    CompletionStage<Void> handle(T event);
}
