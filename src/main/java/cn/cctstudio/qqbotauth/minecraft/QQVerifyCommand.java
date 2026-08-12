package cn.cctstudio.qqbotauth.minecraft;

import cn.cctstudio.qqbotauth.QQBotAuthPlugin;
import cn.cctstudio.qqbotauth.qq.QQBotClient;
import cn.cctstudio.qqbotauth.qq.QQGroupRegistry;
import cn.cctstudio.qqbotauth.config.PluginConfig;
import cn.cctstudio.qqbotauth.verification.BindingService;
import cn.cctstudio.qqbotauth.verification.DatabaseMigrationService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class QQVerifyCommand implements CommandExecutor, TabCompleter {
    private final QQBotAuthPlugin plugin;
    private final PlayerVerificationManager playerManager;
    private final BindingService bindingService;
    private final QQGroupRegistry groupRegistry;
    private final Supplier<PluginConfig> config;

    public QQVerifyCommand(
            QQBotAuthPlugin plugin,
            PlayerVerificationManager playerManager,
            BindingService bindingService,
            QQGroupRegistry groupRegistry,
            Supplier<PluginConfig> config
    ) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.bindingService = bindingService;
        this.groupRegistry = groupRegistry;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "code".equalsIgnoreCase(args[0]) || "dialog".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("该操作只能由游戏内玩家使用。");
                return true;
            }
            playerManager.createAndShowCode(player, "code".equalsIgnoreCase(args.length == 0 ? "" : args[0]));
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subCommand) {
            case "status" -> status(sender);
            case "groups" -> groups(sender);
            case "reload" -> reload(sender);
            case "migrate" -> migrate(sender, args);
            default -> {
                sender.sendMessage("用法：/qqverify [code|dialog|status|groups|reload|migrate mysql]");
                yield true;
            }
        };
    }

    private boolean status(CommandSender sender) {
        if (sender instanceof Player player && !sender.hasPermission("qqbotauth.admin")) {
            PlayerVerificationState state = playerManager.state(player.getUniqueId());
            sender.sendMessage(state == null
                    ? "QQ 验证尚未开始，请先完成 AuthMe 登录。"
                    : "当前 QQ 验证状态：" + state.name());
            return true;
        }
        if (!sender.hasPermission("qqbotauth.admin")) {
            sender.sendMessage("你没有权限执行该操作。");
            return true;
        }
        QQBotClient client = plugin.qqBotClient();
        String gateway = client == null ? "未初始化" : client.state().name();
        sender.sendMessage("QQBotAuth " + plugin.getPluginMeta().getVersion());
        sender.sendMessage("Gateway：" + gateway);
        if (client != null) {
            sender.sendMessage("Session：" + client.sessionIdForStatus() + " / Seq：" + client.sequence());
        }
        int allowedGroups = config.get().qq().allowedGroupOpenIds().size();
        sender.sendMessage(allowedGroups == 0
                ? "群命令白名单：未配置，当前仅发现群来源，不执行群命令"
                : "群命令白名单：" + allowedGroups + " 个 group_openid");
        bindingService.count().whenComplete((count, failure) -> runMain(() -> sender.sendMessage(
                failure == null ? "绑定数量：" + count : "绑定数量：读取失败（请查看控制台）")));
        return true;
    }

    private boolean groups(CommandSender sender) {
        if (!sender.hasPermission("qqbotauth.admin")) {
            sender.sendMessage("你没有权限执行该操作。");
            return true;
        }
        List<QQGroupRegistry.Observation> observations = groupRegistry.observations();
        if (observations.isEmpty()) {
            sender.sendMessage("尚未发现群来源。请先在目标 QQ 群中 @机器人 发送任意指令，然后再次查询。");
            return true;
        }
        sender.sendMessage("已发现的 QQ 群来源（复制目标值到 qq.allowed-group-openids）：");
        for (QQGroupRegistry.Observation observation : observations) {
            boolean allowed = config.get().qq().allowedGroupOpenIds().contains(observation.groupOpenId());
            sender.sendMessage((allowed ? "[已允许] " : "[未允许] ") + observation.groupOpenId());
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("qqbotauth.admin")) {
            sender.sendMessage("你没有权限执行该操作。");
            return true;
        }
        try {
            plugin.reloadRuntime();
            sender.sendMessage(plugin.messages().message("reload-success"));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[QQBot] Configuration reload failed: " + exception.getMessage());
            sender.sendMessage(plugin.messages().message("reload-failed"));
        }
        return true;
    }

    private boolean migrate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("qqbotauth.admin")) {
            sender.sendMessage("你没有权限执行该操作。");
            return true;
        }
        if (args.length < 2 || !"mysql".equalsIgnoreCase(args[1])) {
            sender.sendMessage("用法：/qqverify migrate mysql");
            return true;
        }
        sender.sendMessage("正在后台迁移 SQLite 绑定数据到 MySQL……");
        DatabaseMigrationService migration = plugin.databaseMigrationService();
        migration.migrateSqliteToMySql().whenComplete((count, failure) -> runMain(() -> {
            if (failure == null) {
                sender.sendMessage("迁移完成，共写入 " + count + " 条绑定。确认无误后将 database.mode 改为 mysql 并重启服务器。");
            } else {
                sender.sendMessage("迁移失败，请查看控制台日志。SQLite 数据未被删除。");
                plugin.getLogger().warning("[QQBot] SQLite to MySQL migration failed: " + rootMessage(failure));
            }
        }));
        return true;
    }

    private void runMain(Runnable action) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = sender.hasPermission("qqbotauth.admin")
                    ? List.of("code", "dialog", "status", "groups", "reload", "migrate")
                    : List.of("code", "dialog", "status");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "migrate".equalsIgnoreCase(args[0]) && sender.hasPermission("qqbotauth.admin")) {
            return "mysql".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("mysql") : List.of();
        }
        return List.of();
    }
}
