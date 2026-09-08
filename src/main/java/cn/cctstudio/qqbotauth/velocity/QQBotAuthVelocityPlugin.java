package cn.cctstudio.qqbotauth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import cn.cctstudio.qqbotauth.util.UnboundChatRateLimiter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class QQBotAuthVelocityPlugin {
    private static final MinecraftChannelIdentifier STATE_CHANNEL =
            MinecraftChannelIdentifier.from(ServerTransferService.STATE_CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> verifiedOnlinePlayers = ConcurrentHashMap.newKeySet();
    private final UnboundChatRateLimiter chatRateLimiter = new UnboundChatRateLimiter();
    private String authServer = "login";
    private String unverifiedMessage = "请先在登录服完成 QQ 验证。";
    private String commandBlockedMessage = "请先完成登录和 QQ 验证，再使用其他指令。";
    private String chatBlockedMessage = "冷却还剩 {seconds} 秒，请前往官网个人中心绑定 Discord，或加入 QQ 群 {group} 绑定 QQ 后解除限制。";
    private String serverReminderMessage = "您尚未绑定 QQ 或 Discord，每 30 秒只能发言一次且无法购买会员。请前往官网个人中心绑定 Discord，或加入 QQ 群 {group} 绑定 QQ 后解除限制。";
    private String bindingGroup = "640906149";
    private int chatCooldownSeconds = 30;
    private Set<String> purchaseQuantityBypassServers = Set.of("survival");
    private boolean forceVerification = true;
    private static final String DEFAULT_RESTRICTED_CHAT_COMMANDS =
            "shout,msg,tell,w,whisper,reply,r,me,say,broadcast,bc,emsg,etell,ewhisper,ereply,eme";
    private Set<String> restrictedChatCommands = parseCommands(DEFAULT_RESTRICTED_CHAT_COMMANDS);
    private boolean blockCommands = true;
    private boolean hideCommandSuggestions = true;
    private CommandGatePolicy commandGatePolicy = CommandGatePolicy.fromCsv(
            CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);
    private boolean controlEnabled = true;
    private String controlBindAddress = "127.0.0.1";
    private int controlPort = 25579;
    private String controlSecret = "";
    private String unboundKickMessage = "您的 QQ 绑定已解除，请重新进入登录服完成验证。";
    private VelocityControlServer controlServer;

    @Inject
    public QQBotAuthVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        loadConfiguration();
        proxy.getChannelRegistrar().register(STATE_CHANNEL);
        startControlServer();
        logger.info("[QQBot] Velocity transfer gate enabled; auth server is {}", authServer);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (controlServer != null) {
            controlServer.close();
            controlServer = null;
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!STATE_CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)
                || !authServer.equalsIgnoreCase(source.getServerInfo().getName())) {
            logger.warn("[QQBot] Rejected verification state from an untrusted backend");
            return;
        }
        VerificationStateMessage.decode(event.getData()).ifPresentOrElse(message -> {
            Player player = source.getPlayer();
            if (!player.getUniqueId().equals(message.playerUuid())) {
                logger.warn("[QQBot] Rejected verification state with mismatched player UUID");
                return;
            }
            if (message.verified()) {
                verifiedOnlinePlayers.add(message.playerUuid());
                chatRateLimiter.clear(message.playerUuid());
            } else {
                verifiedOnlinePlayers.remove(message.playerUuid());
            }
        }, () -> logger.warn("[QQBot] Rejected malformed verification state message"));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!forceVerification) return;
        RegisteredServer target = event.getOriginalServer();
        if (authServer.equalsIgnoreCase(target.getServerInfo().getName())
                || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        proxy.getServer(authServer).ifPresentOrElse(
                login -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(login)),
                () -> event.setResult(ServerPreConnectEvent.ServerResult.denied())
        );
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)
                || verifiedOnlinePlayers.contains(player.getUniqueId())) {
            return;
        }

        if (!forceVerification) {
            if (isRestrictedChatCommand(event.getCommand())) {
                UnboundChatRateLimiter.Decision decision = chatRateLimiter.acquire(
                        player.getUniqueId(), chatCooldownSeconds);
                if (!decision.allowed()) {
                    event.setResult(CommandExecuteEvent.CommandResult.denied());
                    sendCooldown(player, decision.remainingSeconds());
                }
            }
            return;
        }

        if (!blockCommands || commandGatePolicy.allows(event.getCommand())) return;

        // Velocity cannot safely consume commands containing signed message arguments.
        // Forward those unchanged so the Paper-side gate can cancel them without breaking chat signing.
        if (event.getInvocationInfo().signedState() == CommandExecuteEvent.SignedState.SIGNED_WITH_ARGS) {
            event.setResult(CommandExecuteEvent.CommandResult.forwardToServer());
            return;
        }

        event.setResult(CommandExecuteEvent.CommandResult.denied());
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        if (!forceVerification || !hideCommandSuggestions
                || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        event.getRootNode().getChildren().removeIf(node -> !commandGatePolicy.allows(node.getName()));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onLegacyTabComplete(TabCompleteEvent event) {
        if (!forceVerification || !hideCommandSuggestions
                || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        String partial = event.getPartialMessage().stripLeading();
        String withoutSlash = partial.startsWith("/") ? partial.substring(1) : partial;
        if (withoutSlash.chars().anyMatch(Character::isWhitespace)) {
            if (!commandGatePolicy.allows(withoutSlash)) {
                event.getSuggestions().clear();
            }
            return;
        }
        event.getSuggestions().removeIf(suggestion -> !commandGatePolicy.allows(suggestion));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onChat(PlayerChatEvent event) {
        if (forceVerification || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) return;
        if (isPurchaseQuantityBypass(event.getPlayer(), event.getMessage())) return;
        UnboundChatRateLimiter.Decision decision = chatRateLimiter.acquire(
                event.getPlayer().getUniqueId(), chatCooldownSeconds);
        if (decision.allowed()) return;
        event.setResult(PlayerChatEvent.ChatResult.denied());
        sendCooldown(event.getPlayer(), decision.remainingSeconds());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (forceVerification || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) return;
        boolean onAuthServer = event.getPlayer().getCurrentServer()
                .map(connection -> authServer.equalsIgnoreCase(connection.getServerInfo().getName()))
                .orElse(false);
        if (!onAuthServer) event.getPlayer().sendMessage(red(renderBindingMessage(serverReminderMessage)));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        verifiedOnlinePlayers.remove(event.getPlayer().getUniqueId());
        chatRateLimiter.clear(event.getPlayer().getUniqueId());
    }

    private void loadConfiguration() {
        Path configFile = dataDirectory.resolve("config.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(configFile)) {
                try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            boolean changed = putDefault(properties, "auth-server", authServer);
            changed |= putDefault(properties, "force-verification", Boolean.toString(forceVerification));
            changed |= putDefault(properties, "unverified-message", unverifiedMessage);
            changed |= putDefault(properties, "chat-blocked-message", chatBlockedMessage);
            changed |= putDefault(properties, "server-reminder-message", serverReminderMessage);
            changed |= putDefault(properties, "binding-group", bindingGroup);
            changed |= putDefault(properties, "chat-cooldown-seconds", Integer.toString(chatCooldownSeconds));
            changed |= putDefault(properties, "purchase-quantity-bypass-servers", "survival");
            changed |= putDefault(properties, "restricted-chat-commands", DEFAULT_RESTRICTED_CHAT_COMMANDS);
            changed |= putDefault(properties, "block-commands", Boolean.toString(blockCommands));
            changed |= putDefault(properties, "hide-command-suggestions", Boolean.toString(hideCommandSuggestions));
            changed |= putDefault(properties, "allowed-commands", CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);
            changed |= putDefault(properties, "command-blocked-message", commandBlockedMessage);
            changed |= putDefault(properties, "control-enabled", Boolean.toString(controlEnabled));
            changed |= putDefault(properties, "control-bind-address", controlBindAddress);
            changed |= putDefault(properties, "control-port", Integer.toString(controlPort));
            changed |= putDefault(properties, "control-secret", controlSecret);
            changed |= putDefault(properties, "unbound-kick-message", unboundKickMessage);
            if (changed) {
                try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                    properties.store(writer, "QQBotAuth Velocity gate");
                }
            }

            authServer = properties.getProperty("auth-server", authServer).trim();
            forceVerification = Boolean.parseBoolean(properties.getProperty("force-verification", "true"));
            unverifiedMessage = properties.getProperty("unverified-message", unverifiedMessage).trim();
            chatBlockedMessage = properties.getProperty(
                    "chat-blocked-message", chatBlockedMessage).trim();
            serverReminderMessage = properties.getProperty(
                    "server-reminder-message", serverReminderMessage).trim();
            bindingGroup = properties.getProperty("binding-group", bindingGroup).trim();
            chatCooldownSeconds = parseBoundedInteger(properties.getProperty(
                    "chat-cooldown-seconds", "30"), 1, 3600, "chat-cooldown-seconds");
            purchaseQuantityBypassServers = parseCommands(properties.getProperty(
                    "purchase-quantity-bypass-servers", "survival"));
            restrictedChatCommands = parseCommands(properties.getProperty(
                    "restricted-chat-commands", DEFAULT_RESTRICTED_CHAT_COMMANDS));
            commandBlockedMessage = properties.getProperty(
                    "command-blocked-message", commandBlockedMessage).trim();
            blockCommands = Boolean.parseBoolean(properties.getProperty("block-commands", "true"));
            hideCommandSuggestions = Boolean.parseBoolean(
                    properties.getProperty("hide-command-suggestions", "true"));
            commandGatePolicy = CommandGatePolicy.fromCsv(properties.getProperty(
                    "allowed-commands", CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS));
            controlEnabled = Boolean.parseBoolean(properties.getProperty("control-enabled", "true"));
            controlBindAddress = properties.getProperty(
                    "control-bind-address", controlBindAddress).trim();
            controlPort = parsePort(properties.getProperty("control-port", "25579"));
            controlSecret = properties.getProperty("control-secret", "").trim();
            unboundKickMessage = properties.getProperty(
                    "unbound-kick-message", unboundKickMessage).trim();
            if (authServer.isBlank()) {
                throw new IllegalArgumentException("auth-server cannot be blank");
            }
            if (controlEnabled && !controlSecret.isBlank() && controlSecret.length() < 24) {
                throw new IllegalArgumentException("control-secret must contain at least 24 characters");
            }
        } catch (IOException | IllegalArgumentException exception) {
            logger.error("[QQBot] Could not load Velocity gate configuration; using safe defaults", exception);
            authServer = "login";
            forceVerification = true;
            unverifiedMessage = "请先在登录服完成 QQ 验证。";
            commandBlockedMessage = "请先完成登录和 QQ 验证，再使用其他指令。";
            blockCommands = true;
            hideCommandSuggestions = true;
            commandGatePolicy = CommandGatePolicy.fromCsv(CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);
            chatCooldownSeconds = 30;
            purchaseQuantityBypassServers = Set.of("survival");
            controlEnabled = false;
        }
    }

    private void startControlServer() {
        if (!controlEnabled) {
            logger.warn("[QQBot] Velocity unbind control is disabled");
            return;
        }
        if (controlSecret.isBlank()) {
            logger.warn("[QQBot] Velocity unbind control is disabled because control-secret is not configured");
            return;
        }
        try {
            controlServer = new VelocityControlServer(
                    this,
                    proxy,
                    logger,
                    verifiedOnlinePlayers,
                    controlBindAddress,
                    controlPort,
                    controlSecret,
                    forceVerification,
                    unboundKickMessage
            );
            controlServer.start();
            logger.info("[QQBot] Velocity unbind control is listening on loopback port {}", controlPort);
        } catch (IOException | RuntimeException exception) {
            controlServer = null;
            logger.error("[QQBot] Could not start Velocity unbind control", exception);
        }
    }

    private boolean isRestrictedChatCommand(String raw) {
        String command = raw == null ? "" : raw.stripLeading().toLowerCase(java.util.Locale.ROOT);
        String[] parts = command.split("\\s+", 3);
        if (parts.length == 0) return false;
        String root = parts[0];
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);
        if (restrictedChatCommands.contains(root)) return true;
        if (parts.length < 2) return false;
        return (root.equals("cmi") && restrictedChatCommands.contains(parts[1]))
                || (root.equals("cct") && parts[1].equals("shout"));
    }

    private boolean isPurchaseQuantityBypass(Player player, String message) {
        if (!UnboundChatRateLimiter.isPurchaseQuantity(message)) return false;
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().toLowerCase(java.util.Locale.ROOT))
                .filter(purchaseQuantityBypassServers::contains)
                .isPresent();
    }

    private void sendCooldown(Player player, long seconds) {
        player.sendMessage(red(renderBindingMessage(chatBlockedMessage)
                .replace("{seconds}", Long.toString(seconds))));
    }

    private String renderBindingMessage(String message) {
        return message.replace("{group}", bindingGroup);
    }

    private static Set<String> parseCommands(String csv) {
        Set<String> commands = ConcurrentHashMap.newKeySet();
        for (String value : csv.split(",")) {
            String command = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!command.isEmpty() && command.chars().noneMatch(Character::isWhitespace)) {
                commands.add(command);
            }
        }
        return Set.copyOf(commands);
    }

    private static Component red(String value) {
        return Component.text(value, NamedTextColor.RED);
    }

    private static int parsePort(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > 65_535) {
                throw new IllegalArgumentException("control-port must be between 1 and 65535");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("control-port must be an integer", exception);
        }
    }

    private static int parseBoundedInteger(String raw, int min, int max, String key) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    private static boolean putDefault(Properties properties, String key, String value) {
        if (properties.containsKey(key)) {
            return false;
        }
        properties.setProperty(key, value);
        return true;
    }
}
