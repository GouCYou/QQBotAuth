package cn.cctstudio.qqbotauth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

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
    private String authServer = "login";
    private String unverifiedMessage = "请先在登录服完成 QQ 验证。";
    private String commandBlockedMessage = "请先完成登录和 QQ 验证，再使用其他指令。";
    private boolean blockCommands = true;
    private boolean hideCommandSuggestions = true;
    private CommandGatePolicy commandGatePolicy = CommandGatePolicy.fromCsv(
            CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);

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
        logger.info("[QQBot] Velocity transfer gate enabled; auth server is {}", authServer);
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
            } else {
                verifiedOnlinePlayers.remove(message.playerUuid());
            }
        }, () -> logger.warn("[QQBot] Rejected malformed verification state message"));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
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
        if (!blockCommands
                || !(event.getCommandSource() instanceof Player player)
                || verifiedOnlinePlayers.contains(player.getUniqueId())
                || commandGatePolicy.allows(event.getCommand())) {
            return;
        }

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
        if (!hideCommandSuggestions || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        event.getRootNode().getChildren().removeIf(node -> !commandGatePolicy.allows(node.getName()));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onLegacyTabComplete(TabCompleteEvent event) {
        if (!hideCommandSuggestions || verifiedOnlinePlayers.contains(event.getPlayer().getUniqueId())) {
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

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        verifiedOnlinePlayers.remove(event.getPlayer().getUniqueId());
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
            changed |= putDefault(properties, "unverified-message", unverifiedMessage);
            changed |= putDefault(properties, "block-commands", Boolean.toString(blockCommands));
            changed |= putDefault(properties, "hide-command-suggestions", Boolean.toString(hideCommandSuggestions));
            changed |= putDefault(properties, "allowed-commands", CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);
            changed |= putDefault(properties, "command-blocked-message", commandBlockedMessage);
            if (changed) {
                try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                    properties.store(writer, "QQBotAuth Velocity gate");
                }
            }

            authServer = properties.getProperty("auth-server", authServer).trim();
            unverifiedMessage = properties.getProperty("unverified-message", unverifiedMessage).trim();
            commandBlockedMessage = properties.getProperty(
                    "command-blocked-message", commandBlockedMessage).trim();
            blockCommands = Boolean.parseBoolean(properties.getProperty("block-commands", "true"));
            hideCommandSuggestions = Boolean.parseBoolean(
                    properties.getProperty("hide-command-suggestions", "true"));
            commandGatePolicy = CommandGatePolicy.fromCsv(properties.getProperty(
                    "allowed-commands", CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS));
            if (authServer.isBlank()) {
                throw new IllegalArgumentException("auth-server cannot be blank");
            }
        } catch (IOException | IllegalArgumentException exception) {
            logger.error("[QQBot] Could not load Velocity gate configuration; using safe defaults", exception);
            authServer = "login";
            unverifiedMessage = "请先在登录服完成 QQ 验证。";
            commandBlockedMessage = "请先完成登录和 QQ 验证，再使用其他指令。";
            blockCommands = true;
            hideCommandSuggestions = true;
            commandGatePolicy = CommandGatePolicy.fromCsv(CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);
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
