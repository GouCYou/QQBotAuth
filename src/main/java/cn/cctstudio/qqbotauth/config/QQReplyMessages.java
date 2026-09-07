package cn.cctstudio.qqbotauth.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class QQReplyMessages {
    private static final Map<String, String> DEFAULTS = defaultsInternal();
    private final Map<String, String> messages;

    public QQReplyMessages(Map<String, String> messages) {
        this.messages = Map.copyOf(messages);
    }

    public String text(String key) {
        return render(key, Map.of());
    }

    public String render(String key, Map<String, String> values) {
        String message = messages.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        return message;
    }

    public static Map<String, String> defaults() {
        return DEFAULTS;
    }

    private static Map<String, String> defaultsInternal() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("help", "可用指令：\n"
                + "绑定 <验证码> - 绑定 CCTStudio 账号\n"
                + "查询 - 查看当前绑定\n"
                + "解绑 - 解除当前 QQ 在本群的绑定\n"
                + "帮助 - 查看本说明");
        values.put("unknown-command", "没有找到这个指令。发送“帮助”查看可用指令。");
        values.put("operation-failed", "操作失败，请稍后重试。若问题持续，请联系服务器管理员。");
        values.put("bind-usage", "用法：绑定 <游戏内显示的验证码>");
        values.put("bind-success", "绑定成功！CCTStudio 账号：<minecraft>。现在可正常游戏。");
        values.put("bind-invalid-code", "验证码无效或已过期，请重新进入服务器获取新的验证码。");
        values.put("bind-qq-already-bound", "这个 QQ 账号已经绑定了其他 CCTStudio 账号，请先发送 “解绑”。");
        values.put("bind-minecraft-already-bound", "这个 CCTStudio 账号已经完成绑定。");
        values.put("bind-code-used", "验证码已经被使用，请重新进入服务器获取新的验证码。");
        values.put("query-bound", "已绑定 CCTStudio 账号：<minecraft>");
        values.put("query-unbound", "当前 QQ 尚未绑定 CCTStudio 账号。");
        values.put("unbind-success", "解绑成功。");
        values.put("unbind-not-bound", "当前 QQ 没有可解除的绑定。");
        values.put("member-left-unbound", "绑定该账号的群成员已退出，CCTStudio 账号 <minecraft> 已自动解绑。");
        return Map.copyOf(values);
    }
}
