package cn.cctstudio.qqbotauth.minecraft;

import cn.cctstudio.qqbotauth.config.MessageService;
import cn.cctstudio.qqbotauth.config.PluginConfig;
import cn.cctstudio.qqbotauth.velocity.ServerTransferService;
import cn.cctstudio.qqbotauth.verification.BindingRecord;
import cn.cctstudio.qqbotauth.verification.BindingService;
import cn.cctstudio.qqbotauth.verification.VerificationCode;
import cn.cctstudio.qqbotauth.verification.VerificationService;
import cn.cctstudio.qqbotauth.util.HumanDurationFormatter;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PlayerVerificationManager implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final BindingService bindingService;
    private final VerificationService verificationService;
    private final ServerTransferService transferService;
    private final VerificationDialogService dialogService;
    private final MessageService messages;
    private final Supplier<PluginConfig> config;
    private final Map<UUID, PlayerVerificationState> states = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> codeTimeouts = new ConcurrentHashMap<>();
    private final Set<UUID> completedBindings = ConcurrentHashMap.newKeySet();
    private BukkitTask reminderTask;

    public PlayerVerificationManager(
            JavaPlugin plugin,
            BindingService bindingService,
            VerificationService verificationService,
            ServerTransferService transferService,
            VerificationDialogService dialogService,
            MessageService messages,
            Supplier<PluginConfig> config
    ) {
        this.plugin = plugin;
        this.bindingService = bindingService;
        this.verificationService = verificationService;
        this.transferService = transferService;
        this.dialogService = dialogService;
        this.messages = messages;
        this.config = config;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        dialogService.register(this);
        restartReminderTask();
    }

    public void restartReminderTask() {
        assertMainThread();
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        int seconds = config.get().player().reminderSeconds();
        if (seconds <= 0) {
            return;
        }
        reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (states.get(player.getUniqueId()) == PlayerVerificationState.UNVERIFIED) {
                    showInstructions(player, false);
                }
            }
        }, seconds * 20L, seconds * 20L);
    }

    public void onAuthenticated(Player player) {
        runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (!config.get().qq().enabled()) {
                plugin.getLogger().info("[QQBot] QQ verification is disabled; allowing " + player.getName());
                states.put(player.getUniqueId(), PlayerVerificationState.TRANSFERRING);
                bypassVerification(player);
                return;
            }
            if (!config.get().qq().hasCredentials()) {
                plugin.getLogger().warning("[QQBot] Holding " + player.getName()
                        + " on the login server because QQ credentials are missing");
                states.put(player.getUniqueId(), PlayerVerificationState.UNVERIFIED);
                transferService.publishVerificationState(player, false);
                player.sendMessage(messages.message("service-unavailable"));
                return;
            }
            PlayerVerificationState old = states.put(player.getUniqueId(), PlayerVerificationState.CHECKING);
            if (old == PlayerVerificationState.CHECKING || old == PlayerVerificationState.TRANSFERRING) {
                return;
            }
            transferService.publishVerificationState(player, false);
            plugin.getLogger().info("[QQBot] AuthMe completed for " + player.getName()
                    + "; checking QQ binding asynchronously");
            player.sendMessage(messages.message("checking"));
            checkBinding(player, false);
        });
    }

    public void onAuthenticationLost(Player player) {
        runMain(() -> clearPlayer(player.getUniqueId(), player));
    }

    public void checkBinding(Player player, boolean userInitiated) {
        assertMainThread();
        UUID playerId = player.getUniqueId();
        if (!states.containsKey(playerId)) {
            player.sendMessage(messages.message("authme-required"));
            return;
        }
        if (userInitiated) {
            player.sendMessage(messages.message("checking"));
        }
        bindingService.findByMinecraft(playerId).whenComplete((binding, failure) -> runMain(() -> {
            if (!player.isOnline() || !states.containsKey(playerId)) {
                return;
            }
            if (failure != null) {
                plugin.getLogger().warning("[QQBot] Failed to check binding: " + rootMessage(failure));
                player.sendMessage(messages.message("interaction-blocked"));
                states.put(playerId, PlayerVerificationState.UNVERIFIED);
                return;
            }
            if (binding.isPresent()) {
                plugin.getLogger().info("[QQBot] Existing QQ binding found for " + player.getName());
                beginTransfer(player);
            } else {
                plugin.getLogger().info("[QQBot] No QQ binding found for " + player.getName()
                        + "; keeping player on the login server");
                states.put(playerId, PlayerVerificationState.UNVERIFIED);
                issueCode(player);
            }
        }));
    }

    public void createAndShowCode(Player player, boolean forceNew) {
        assertMainThread();
        if (states.get(player.getUniqueId()) != PlayerVerificationState.UNVERIFIED) {
            if (states.get(player.getUniqueId()) == PlayerVerificationState.TRANSFERRING) {
                player.sendMessage(messages.message("already-verified"));
            } else {
                player.sendMessage(messages.message("authme-required"));
            }
            return;
        }
        if (forceNew) {
            issueCode(player);
            return;
        }
        VerificationCode code = verificationService.current(player.getUniqueId()).orElse(null);
        if (code == null) {
            player.sendMessage(messages.message("code-expired"));
            return;
        }
        showCode(player, code);
        dialogService.show(player, code);
    }

    public void onBindingSuccess(BindingRecord binding) {
        completedBindings.add(binding.minecraftUuid());
        runMain(() -> {
            Player player = Bukkit.getPlayer(binding.minecraftUuid());
            if (player != null && player.isOnline()
                    && states.get(binding.minecraftUuid()) == PlayerVerificationState.UNVERIFIED) {
                beginTransfer(player);
            } else {
                completedBindings.remove(binding.minecraftUuid());
            }
        });
    }

    public boolean isAwaitingVerification(UUID playerId) {
        PlayerVerificationState state = states.get(playerId);
        return state == PlayerVerificationState.CHECKING || state == PlayerVerificationState.UNVERIFIED
                || state == PlayerVerificationState.TRANSFERRING;
    }

    public PlayerVerificationState state(UUID playerId) {
        return states.get(playerId);
    }

    private void beginTransfer(Player player) {
        assertMainThread();
        UUID playerId = player.getUniqueId();
        completedBindings.remove(playerId);
        cancelCodeTimeout(playerId);
        states.put(playerId, PlayerVerificationState.TRANSFERRING);
        verificationService.invalidate(playerId);
        player.closeDialog();
        player.sendMessage(messages.message("verification-success"));
        BukkitTask old = transfers.remove(playerId);
        if (old != null) {
            old.cancel();
        }
        PluginConfig.Transfer transfer = config.get().player().transfer();
        if (!transfer.enabled()) {
            transferService.publishVerificationState(player, true);
            states.remove(playerId);
            return;
        }
        int delay = transfer.delaySeconds();
        if (delay <= 0) {
            finishTransfer(player, transfer.verifiedServer());
            return;
        }
        final int[] remaining = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancelTransfer(playerId);
                return;
            }
            if (remaining[0] <= 0) {
                cancelTransfer(playerId);
                finishTransfer(player, config.get().player().transfer().verifiedServer());
                return;
            }
            String seconds = Integer.toString(remaining[0]--);
            player.showTitle(Title.title(
                    messages.raw("transfer-countdown-title", Map.of("seconds", seconds)),
                    messages.raw("transfer-countdown-subtitle", Map.of("seconds", seconds)),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(150))
            ));
        }, 0L, 20L);
        transfers.put(playerId, task);
    }

    private void bypassVerification(Player player) {
        PluginConfig.Transfer transfer = config.get().player().transfer();
        if (!transfer.enabled()) {
            transferService.publishVerificationState(player, true);
            states.remove(player.getUniqueId());
            return;
        }
        finishTransfer(player, transfer.verifiedServer());
    }

    private void finishTransfer(Player player, String serverName) {
        assertMainThread();
        if (!player.isOnline()) {
            return;
        }
        player.sendMessage(messages.message("transfer-now"));
        plugin.getLogger().info("[QQBot] Transferring verified player " + player.getName()
                + " to " + serverName);
        transferService.publishVerificationState(player, true);
        transferService.connect(player, serverName);
    }

    private void showInstructions(Player player, boolean openDialog) {
        VerificationCode code = verificationService.current(player.getUniqueId()).orElse(null);
        if (code == null) {
            return;
        }
        showCode(player, code);
        if (openDialog) {
            dialogService.show(player, code);
        }
    }

    private void issueCode(Player player) {
        assertMainThread();
        VerificationCode code = verificationService.create(player.getUniqueId(), player.getName());
        scheduleCodeTimeout(player, code);
        showCode(player, code);
        dialogService.show(player, code);
    }

    private void scheduleCodeTimeout(Player player, VerificationCode code) {
        UUID playerId = player.getUniqueId();
        cancelCodeTimeout(playerId);
        long millis = Math.max(1, Duration.between(Instant.now(), code.expiresAt()).toMillis());
        long ticks = Math.max(1, (millis + 49) / 50);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            codeTimeouts.remove(playerId);
            if (!player.isOnline()
                    || states.get(playerId) != PlayerVerificationState.UNVERIFIED
                    || completedBindings.contains(playerId)) {
                return;
            }
            verificationService.invalidate(playerId);
            states.remove(playerId);
            transferService.publishVerificationState(player, false);
            plugin.getLogger().info("[QQBot] QQ verification timed out for " + player.getName());
            player.kick(messages.message("verification-timeout"));
        }, ticks);
        codeTimeouts.put(playerId, task);
    }

    private void showCode(Player player, VerificationCode code) {
        Map<String, String> values = Map.of(
                "group", displayGroup(),
                "code", code.value(),
                "expire", HumanDurationFormatter.remainingUntil(code.expiresAt())
        );
        player.sendMessage(messages.message("verification-instructions", values));
        player.sendMessage(messages.message("code-created", values));
    }

    private String displayGroup() {
        String group = config.get().qq().groupNumber();
        return group.isBlank() ? "服主配置的验证群" : group;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!config.get().player().blockMovement() || !isAwaitingVerification(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() != null && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ())) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && blockedInteraction(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && blockedInteraction(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!config.get().player().blockServerCommand()
                || !isAwaitingVerification(event.getPlayer().getUniqueId())) {
            return;
        }
        String raw = event.getMessage().substring(1).trim();
        String label = raw.isBlank() ? "" : raw.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (config.get().player().allowedCommands().contains(label)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.message("command-blocked"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    private boolean blockedInteraction(Player player) {
        return config.get().player().blockInteraction() && isAwaitingVerification(player.getUniqueId());
    }

    private void clearPlayer(UUID playerId, Player player) {
        assertMainThread();
        states.remove(playerId);
        completedBindings.remove(playerId);
        verificationService.invalidate(playerId);
        cancelTransfer(playerId);
        cancelCodeTimeout(playerId);
        if (player.isOnline()) {
            transferService.publishVerificationState(player, false);
        }
    }

    private void cancelTransfer(UUID playerId) {
        BukkitTask task = transfers.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelCodeTimeout(UUID playerId) {
        BukkitTask task = codeTimeouts.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void runMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit player operations must run on the server thread");
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
    public void close() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        transfers.values().forEach(BukkitTask::cancel);
        transfers.clear();
        codeTimeouts.values().forEach(BukkitTask::cancel);
        codeTimeouts.clear();
        completedBindings.clear();
        states.clear();
    }
}
