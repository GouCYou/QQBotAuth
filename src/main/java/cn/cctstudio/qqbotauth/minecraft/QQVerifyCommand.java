package cn.cctstudio.qqbotauth.minecraft;

import cn.cctstudio.qqbotauth.QQBotAuthPlugin;
import cn.cctstudio.qqbotauth.qq.QQBotClient;
import cn.cctstudio.qqbotauth.qq.QQGroupRegistry;
import cn.cctstudio.qqbotauth.config.PluginConfig;
import cn.cctstudio.qqbotauth.verification.BindingRecord;
import cn.cctstudio.qqbotauth.verification.BindingService;
import cn.cctstudio.qqbotauth.verification.DatabaseMigrationService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class QQVerifyCommand implements CommandExecutor, TabCompleter {
    private static final int BINDINGS_PER_PAGE = 8;
    private static final DateTimeFormatter BOUND_AT_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

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
        if (args.length == 0 || "bind".equalsIgnoreCase(args[0])
                || "code".equalsIgnoreCase(args[0]) || "dialog".equalsIgnoreCase(args[0])) {
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
            case "unbind" -> unbind(sender, args);
            case "lookup" -> lookup(sender, args);
            case "bindings" -> bindings(sender, args);
            default -> {
                sendAdminUsage(sender);
                yield true;
            }
        };
    }

    private boolean status(CommandSender sender) {
        if (sender instanceof Player player && !sender.hasPermission("qqbotauth.admin")) {
            bindingService.findByMinecraft(player.getUniqueId()).whenComplete((binding, failure) -> runMain(() -> {
                if (failure != null) operationFailed(sender, "查询 QQ 绑定失败", failure);
                else sender.sendMessage(binding.isPresent() ? "当前 QQ 状态：已绑定。" : "当前 QQ 状态：未绑定。");
            }));
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

    private boolean unbind(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            bindingService.unbindMinecraft(player.getUniqueId().toString())
                    .whenComplete((binding, failure) -> runMain(() -> {
                        if (failure != null) {
                            operationFailed(sender, "QQ 解绑失败", failure);
                        } else if (binding.isEmpty()) {
                            sender.sendMessage("您的游戏账号当前没有绑定 QQ。");
                        } else {
                            sendUnbindResult(sender, binding.orElseThrow(), true);
                        }
                    }));
            return true;
        }
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("用法：/qqverify unbind player <玩家名或UUID>");
            sender.sendMessage("或：/qqverify unbind qq <member_openid> [group_openid]");
            return true;
        }
        if ("player".equalsIgnoreCase(args[1])) {
            String identity = args[2].trim();
            sender.sendMessage("正在查询并解除玩家 " + identity + " 的 QQ 绑定……");
            bindingService.unbindMinecraft(identity).whenComplete((binding, failure) -> runMain(() -> {
                if (failure != null) {
                    operationFailed(sender, "按玩家解绑失败", failure);
                } else if (binding.isEmpty()) {
                    sender.sendMessage("没有找到该玩家的 QQ 绑定。玩家名不区分大小写，也可以改用完整 UUID 查询。");
                } else {
                    BindingRecord removed = binding.get();
                    sendUnbindResult(sender, removed, false);
                }
            }));
            return true;
        }
        if ("qq".equalsIgnoreCase(args[1])) {
            String memberOpenId = args[2].trim();
            if (args.length >= 4) {
                unbindQq(sender, memberOpenId, args[3].trim());
                return true;
            }
            bindingService.findByQqMember(memberOpenId).whenComplete((matches, failure) -> {
                if (failure != null) {
                    runMain(() -> operationFailed(sender, "查询 QQ 绑定失败", failure));
                } else if (matches.isEmpty()) {
                    runMain(() -> sender.sendMessage("没有找到该 member_openid 的绑定。"));
                } else if (matches.size() == 1) {
                    unbindQq(sender, memberOpenId, matches.getFirst().groupOpenId());
                } else {
                    runMain(() -> {
                        sender.sendMessage("该 member_openid 在多个群中存在绑定，请在指令末尾指定 group_openid：");
                        for (BindingRecord match : matches) {
                            sender.sendMessage("- " + match.groupOpenId() + " → " + match.minecraftName());
                        }
                    });
                }
            });
            return true;
        }
        sender.sendMessage("用法：/qqverify unbind player <玩家名或UUID>");
        sender.sendMessage("或：/qqverify unbind qq <member_openid> [group_openid]");
        return true;
    }

    private void unbindQq(CommandSender sender, String memberOpenId, String groupOpenId) {
        sender.sendMessage("正在解除指定 QQ 身份的绑定……");
        bindingService.unbindRecord(groupOpenId, memberOpenId).whenComplete((binding, failure) -> runMain(() -> {
            if (failure != null) {
                operationFailed(sender, "按 QQ 解绑失败", failure);
            } else if (binding.isEmpty()) {
                sender.sendMessage("没有找到完全匹配该 member_openid 和 group_openid 的绑定。");
            } else {
                BindingRecord removed = binding.get();
                sendUnbindResult(sender, removed, false);
            }
        }));
    }

    private boolean lookup(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("用法：/qqverify lookup player <玩家名或UUID>");
            sender.sendMessage("或：/qqverify lookup qq <member_openid> [group_openid]");
            return true;
        }
        if ("player".equalsIgnoreCase(args[1])) {
            bindingService.findByMinecraftIdentity(args[2]).whenComplete((binding, failure) -> runMain(() -> {
                if (failure != null) {
                    operationFailed(sender, "查询玩家绑定失败", failure);
                } else if (binding.isEmpty()) {
                    sender.sendMessage("没有找到该玩家的 QQ 绑定。玩家名不区分大小写，也可以改用完整 UUID 查询。");
                } else {
                    sender.sendMessage("查询到 1 条绑定：");
                    sendBinding(sender, binding.get());
                }
            }));
            return true;
        }
        if ("qq".equalsIgnoreCase(args[1])) {
            String memberOpenId = args[2].trim();
            if (args.length >= 4) {
                bindingService.findByQq(args[3].trim(), memberOpenId)
                        .whenComplete((binding, failure) -> runMain(() -> {
                            if (failure != null) {
                                operationFailed(sender, "查询 QQ 绑定失败", failure);
                            } else if (binding.isEmpty()) {
                                sender.sendMessage("没有找到完全匹配该 member_openid 和 group_openid 的绑定。");
                            } else {
                                sender.sendMessage("查询到 1 条绑定：");
                                sendBinding(sender, binding.get());
                            }
                        }));
            } else {
                bindingService.findByQqMember(memberOpenId).whenComplete((matches, failure) -> runMain(() -> {
                    if (failure != null) {
                        operationFailed(sender, "查询 QQ 绑定失败", failure);
                    } else if (matches.isEmpty()) {
                        sender.sendMessage("没有找到该 member_openid 的绑定。");
                    } else {
                        sender.sendMessage("查询到 " + matches.size() + " 条绑定：");
                        matches.forEach(binding -> sendBinding(sender, binding));
                    }
                }));
            }
            return true;
        }
        sender.sendMessage("查询类型只能是 player 或 qq。");
        return true;
    }

    private boolean bindings(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        int requestedPage = 1;
        if (args.length >= 2) {
            try {
                requestedPage = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("页码必须是正整数。用法：/qqverify bindings [页码]");
                return true;
            }
        }
        if (requestedPage < 1) {
            sender.sendMessage("页码必须是正整数。");
            return true;
        }
        int page = requestedPage;
        bindingService.findAll().whenComplete((all, failure) -> runMain(() -> {
            if (failure != null) {
                operationFailed(sender, "读取绑定列表失败", failure);
                return;
            }
            if (all.isEmpty()) {
                sender.sendMessage("当前没有任何 QQ 绑定记录。");
                return;
            }
            int totalPages = Math.max(1, (all.size() + BINDINGS_PER_PAGE - 1) / BINDINGS_PER_PAGE);
            if (page > totalPages) {
                sender.sendMessage("页码超出范围，当前共有 " + totalPages + " 页。当前绑定总数：" + all.size());
                return;
            }
            int from = (page - 1) * BINDINGS_PER_PAGE;
            int to = Math.min(all.size(), from + BINDINGS_PER_PAGE);
            sender.sendMessage("QQ 绑定列表，第 " + page + "/" + totalPages + " 页，共 " + all.size() + " 条：");
            all.subList(from, to).forEach(binding -> sendBinding(sender, binding));
        }));
        return true;
    }

    private void sendBinding(CommandSender sender, BindingRecord binding) {
        sender.sendMessage("- " + binding.minecraftName() + " (" + binding.minecraftUuid() + ")");
        sender.sendMessage("  QQ member_openid=" + binding.memberOpenId()
                + " / group_openid=" + binding.groupOpenId()
                + " / 绑定时间=" + BOUND_AT_FORMAT.format(binding.boundAt())
                + " / 状态=" + binding.status().name());
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("qqbotauth.admin")) {
            return true;
        }
        sender.sendMessage("你没有权限执行该操作。");
        return false;
    }

    private void operationFailed(CommandSender sender, String action, Throwable failure) {
        sender.sendMessage(action + "，请查看控制台日志。");
        plugin.getLogger().warning("[QQBot] " + action + ": " + rootMessage(failure));
    }

    private void sendAdminUsage(CommandSender sender) {
        sender.sendMessage("玩家：/qqbotauth bind | /qqbotauth unbind | /qqbotauth status");
        if (sender.hasPermission("qqbotauth.admin")) {
            sender.sendMessage("管理：/qqverify unbind player <玩家名或UUID>");
            sender.sendMessage("管理：/qqverify unbind qq <member_openid> [group_openid]");
            sender.sendMessage("管理：/qqverify lookup player <玩家名或UUID>");
            sender.sendMessage("管理：/qqverify lookup qq <member_openid> [group_openid]");
            sender.sendMessage("管理：/qqverify bindings [页码]");
        }
    }

    private void sendUnbindResult(CommandSender sender, BindingRecord removed, boolean self) {
        bindingService.hasAnyBinding(removed.minecraftUuid()).whenComplete((stillBound, failure) -> runMain(() -> {
            if (failure != null) {
                operationFailed(sender, "QQ 已解绑，但读取 Discord 绑定状态失败", failure);
                return;
            }
            String subject = self ? "QQ 已解绑" : "已解除 " + removed.minecraftName() + " 的 QQ 绑定";
            if (stillBound) {
                sender.sendMessage(subject + "；Discord 仍处于绑定状态，不会被踢出或受到限制。");
            } else if (config.get().player().forceVerification()) {
                sender.sendMessage(subject + "；QQ 与 Discord 均未绑定，强制绑定已开启，在线玩家会被断开连接。");
            } else {
                sender.sendMessage(subject + "；QQ 与 Discord 均未绑定，玩家不会被踢出，但聊天和会员购买会受限。");
            }
        }));
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
                    ? List.of("bind", "code", "dialog", "status", "groups", "reload", "migrate",
                    "unbind", "lookup", "bindings")
                    : List.of("bind", "unbind", "status");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "migrate".equalsIgnoreCase(args[0]) && sender.hasPermission("qqbotauth.admin")) {
            return "mysql".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("mysql") : List.of();
        }
        if (args.length == 2 && sender.hasPermission("qqbotauth.admin")
                && ("unbind".equalsIgnoreCase(args[0]) || "lookup".equalsIgnoreCase(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("player", "qq").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && sender.hasPermission("qqbotauth.admin")
                && ("unbind".equalsIgnoreCase(args[0]) || "lookup".equalsIgnoreCase(args[0]))
                && "player".equalsIgnoreCase(args[1])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 4 && sender.hasPermission("qqbotauth.admin")
                && ("unbind".equalsIgnoreCase(args[0]) || "lookup".equalsIgnoreCase(args[0]))
                && "qq".equalsIgnoreCase(args[1])) {
            String prefix = args[3].toUpperCase(Locale.ROOT);
            return config.get().qq().allowedGroupOpenIds().stream()
                    .filter(value -> value.toUpperCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return List.of();
    }
}
