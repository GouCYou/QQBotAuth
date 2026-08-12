package cn.cctstudio.qqbotauth;

import cn.cctstudio.qqbotauth.authme.AuthMeHook;
import cn.cctstudio.qqbotauth.command.CommandManager;
import cn.cctstudio.qqbotauth.command.impl.BindCommand;
import cn.cctstudio.qqbotauth.command.impl.HelpCommand;
import cn.cctstudio.qqbotauth.command.impl.QueryCommand;
import cn.cctstudio.qqbotauth.command.impl.UnbindCommand;
import cn.cctstudio.qqbotauth.config.ConfigManager;
import cn.cctstudio.qqbotauth.config.MessageService;
import cn.cctstudio.qqbotauth.config.PluginConfig;
import cn.cctstudio.qqbotauth.minecraft.PlayerVerificationManager;
import cn.cctstudio.qqbotauth.minecraft.QQVerifyCommand;
import cn.cctstudio.qqbotauth.minecraft.VerificationDialogService;
import cn.cctstudio.qqbotauth.qq.QQApiClient;
import cn.cctstudio.qqbotauth.qq.QQBotClient;
import cn.cctstudio.qqbotauth.qq.QQEventDispatcher;
import cn.cctstudio.qqbotauth.qq.QQGroupRegistry;
import cn.cctstudio.qqbotauth.qq.event.GroupMemberAddEvent;
import cn.cctstudio.qqbotauth.qq.event.GroupMemberRemoveEvent;
import cn.cctstudio.qqbotauth.qq.event.GroupMessageEvent;
import cn.cctstudio.qqbotauth.util.PluginExecutors;
import cn.cctstudio.qqbotauth.velocity.ServerTransferService;
import cn.cctstudio.qqbotauth.verification.BindingRecord;
import cn.cctstudio.qqbotauth.verification.BindingRepository;
import cn.cctstudio.qqbotauth.verification.BindingRepositoryFactory;
import cn.cctstudio.qqbotauth.verification.BindingService;
import cn.cctstudio.qqbotauth.verification.DatabaseMigrationService;
import cn.cctstudio.qqbotauth.verification.VerificationService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class QQBotAuthPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private MessageService messages;
    private PluginExecutors executors;
    private BindingRepository bindingRepository;
    private VerificationService verificationService;
    private BindingService bindingService;
    private PlayerVerificationManager playerVerificationManager;
    private ServerTransferService transferService;
    private QQBotClient qqBotClient;
    private final QQGroupRegistry qqGroupRegistry = new QQGroupRegistry();

    @Override
    public void onEnable() {
        try {
            configManager = new ConfigManager(this);
            PluginConfig config = configManager.load();
            messages = new MessageService(this);
            executors = new PluginExecutors(throwable -> getLogger().log(
                    Level.SEVERE, "[QQBot] Uncaught asynchronous task failure", throwable));

            bindingRepository = BindingRepositoryFactory.create(config.database(), executors.database());
            bindingRepository.initialize().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    getLogger().log(Level.SEVERE, "[QQBot] Database initialization failed", failure);
                    if (isEnabled()) {
                        Bukkit.getScheduler().runTask(this, () -> Bukkit.getPluginManager().disablePlugin(this));
                    }
                } else {
                    getLogger().info("[QQBot] Binding database is ready (" + config.database().mode() + ")");
                }
            });

            verificationService = new VerificationService(
                    config.verification().codeLength(), Duration.ofSeconds(config.verification().expireSeconds()));
            AtomicReference<PlayerVerificationManager> managerReference = new AtomicReference<>();
            bindingService = new BindingService(bindingRepository, verificationService, binding -> {
                PlayerVerificationManager manager = managerReference.get();
                if (manager != null) {
                    manager.onBindingSuccess(binding);
                }
            });

            transferService = new ServerTransferService(this);
            transferService.register();
            VerificationDialogService dialogs = new VerificationDialogService(
                    this, messages, configManager::current);
            playerVerificationManager = new PlayerVerificationManager(
                    this,
                    bindingService,
                    verificationService,
                    transferService,
                    dialogs,
                    messages,
                    configManager::current
            );
            managerReference.set(playerVerificationManager);
            playerVerificationManager.register();

            AuthMeHook authMeHook = new AuthMeHook(this, playerVerificationManager);
            authMeHook.register();
            registerMinecraftCommand();
            startQq(config.qq());

            getLogger().info("[QQBot] QQBotAuth enabled; all QQ network operations run asynchronously");
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "[QQBot] Plugin startup failed", exception);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void startQq(PluginConfig.QQ config) {
        QQApiClient apiClient = new QQApiClient(config, executors.network(), this::warn);
        QQEventDispatcher dispatcher = new QQEventDispatcher(this::warn);
        if (config.enabled() && config.allowedGroupOpenIds().isEmpty()) {
            warn("No qq.allowed-group-openids are configured; group commands are in discovery-only mode");
        }
        CommandManager commandManager = new CommandManager(
                apiClient, qqGroupRegistry, config.allowedGroupOpenIds(),
                configManager.current().qqMessages(), this::warn);
        commandManager.register(new HelpCommand());
        commandManager.register(new BindCommand(bindingService));
        commandManager.register(new QueryCommand(bindingService));
        commandManager.register(new UnbindCommand(bindingService));
        dispatcher.register(GroupMessageEvent.class, commandManager::handle);
        dispatcher.register(GroupMemberAddEvent.class, event -> java.util.concurrent.CompletableFuture.completedFuture(null));
        dispatcher.register(GroupMemberRemoveEvent.class, event -> java.util.concurrent.CompletableFuture.completedFuture(null));
        qqBotClient = new QQBotClient(
                config,
                apiClient,
                dispatcher,
                executors.scheduler(),
                message -> getLogger().info("[QQBot] " + message),
                this::warn
        );
        qqBotClient.start();
    }

    private void registerMinecraftCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("qqverify"), "qqverify command missing from plugin.yml");
        QQVerifyCommand executor = new QQVerifyCommand(
                this, playerVerificationManager, bindingService, qqGroupRegistry, configManager::current);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public synchronized void reloadRuntime() {
        PluginConfig oldConfig = configManager.current();
        PluginConfig newConfig = configManager.reload();
        if (oldConfig.database().mode() != newConfig.database().mode()
                || !oldConfig.database().sqlite().equals(newConfig.database().sqlite())
                || !oldConfig.database().mysql().equals(newConfig.database().mysql())) {
            throw new IllegalStateException("数据库设置变更后必须完整重启服务器");
        }
        configManager.activate(newConfig);
        verificationService.reconfigure(
                newConfig.verification().codeLength(), Duration.ofSeconds(newConfig.verification().expireSeconds()));
        playerVerificationManager.restartReminderTask();
        QQBotClient previous = qqBotClient;
        if (previous != null) {
            previous.close();
        }
        startQq(newConfig.qq());
        getLogger().info("[QQBot] Configuration reloaded");
    }

    public QQBotClient qqBotClient() {
        return qqBotClient;
    }

    public MessageService messages() {
        return messages;
    }

    public DatabaseMigrationService databaseMigrationService() {
        return new DatabaseMigrationService(configManager.current().database(), executors.database());
    }

    private void warn(String message) {
        getLogger().warning("[QQBot] " + message);
    }

    @Override
    public void onDisable() {
        if (qqBotClient != null) {
            qqBotClient.close();
        }
        if (playerVerificationManager != null) {
            playerVerificationManager.close();
        }
        if (transferService != null) {
            transferService.unregister();
        }
        if (bindingRepository != null) {
            bindingRepository.close();
        }
        if (executors != null) {
            executors.close();
        }
        getLogger().info("[QQBot] QQBotAuth disabled; resources released");
    }
}
