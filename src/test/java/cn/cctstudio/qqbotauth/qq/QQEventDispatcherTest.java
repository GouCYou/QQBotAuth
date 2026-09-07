package cn.cctstudio.qqbotauth.qq;

import cn.cctstudio.qqbotauth.qq.event.GroupMemberRemoveEvent;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QQEventDispatcherTest {
    @Test
    void parsesOfficialGroupMemberRemovePayload() {
        QQEventDispatcher dispatcher = new QQEventDispatcher(message -> { });
        AtomicReference<GroupMemberRemoveEvent> received = new AtomicReference<>();
        dispatcher.register(GroupMemberRemoveEvent.class, event -> {
            received.set(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        JsonObject data = new JsonObject();
        data.addProperty("timestamp", 1_784_276_759L);
        data.addProperty("group_openid", "group");
        data.addProperty("member_openid", "member");
        data.addProperty("user_openid", "user");

        dispatcher.dispatch("GROUP_MEMBER_REMOVE", "event", data).toCompletableFuture().join();

        assertEquals(new GroupMemberRemoveEvent(
                "event", "group", "member", "user", Instant.ofEpochSecond(1_784_276_759L)), received.get());
    }
}
