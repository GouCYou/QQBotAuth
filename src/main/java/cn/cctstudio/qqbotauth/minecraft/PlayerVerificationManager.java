package cn.cctstudio.qqbotauth.minecraft;

import cn.cctstudio.qqbotauth.config.MessageService;
import cn.cctstudio.qqbotauth.config.PluginConfig;
import cn.cctstudio.qqbotauth.velocity.ServerTransferService;
import cn.cctstudio.qqbotauth.verification.BindingRecord;
import cn.cctstudio.qqbotauth.verification.BindingService;
import cn.cctstudio.qqbotauth.verification.VerificationCode;
import cn.cctstudio.qqbotauth.util.HumanDurationFormatter;
import cn.cctstudio.qqbotauth.util.UnboundChatRateLimiter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class PlayerVerificationManager implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final BindingService bindingService;
    private final ServerTransferService transferService;
    private final VerificationDialogService dialogService;
    private final MessageService messages;
    private final Supplier<PluginConfig> config;
    private final Consumer<UUID> bindingRewardListener;
    private final boolean authGateEnabled;
    private final Map<UUID, PlayerVerificationState> states = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> codeTimeouts = new ConcurrentHashMap<>();
    private final Set<UUID> completedBindings = ConcurrentHashMap.newKeySet();
    private final UnboundChatRateLimiter chatRateLimiter = new UnboundChatRateLimiter();
    private BukkitTask reminderTask;

    public PlayerVerificationManager(
            JavaPlugin plugin,
            BindingService bindingService,
            ServerTransferService transferService,
            VerificationDialogService dialogService,
            MessageService messages,
            Supplier<PluginConfig> config,
            Consumer<UUID> bindingRewardListener,
            boolean authGateEnabled
    ) {
        this.plugin = plugin;
        this.bindingService = bindingService;
        this.transferService = transferService;
        this.dialogService = dialogService;
        this.messages = messages;
        this.config = config;
        this.bindingRewardListener = bindingRewardListener;
        this.authGateEnabled = authGateEnabled;
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
        int seconds = authGateEnabled ? config.get().player().reminderSeconds() : 0;
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
            if (config.get().player().forceVerification()
                    && (!config.get().qq().enabled() || !config.get().qq().hasCredentials())) {
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
            if (authGateEnabled) {
                player.sendMessage(messages.message("authme-required"));
            } else {
                checkManualBinding(player, userInitiated);
            }
            return;
        }
        if (userInitiated) {
            player.sendMessage(messages.message("checking"));
        }
        bindingService.hasAnyBinding(playerId).whenComplete((bound, failure) -> runMain(() -> {
            if (!player.isOnline() || !states.containsKey(playerId)) {
                return;
            }
            if (failure != null) {
                plugin.getLogger().warning("[QQBot] Failed to check binding: " + rootMessage(failure));
                player.sendMessage(messages.message("service-unavailable"));
                states.put(playerId, PlayerVerificationState.UNVERIFIED);
                return;
            }
            if (bound) {
                plugin.getLogger().info("[QQBot] Existing QQ or Discord binding found for " + player.getName());
                bindingRewardListener.accept(playerId);
                beginTransfer(player);
            } else {
                plugin.getLogger().info("[QQBot] No QQ or Discord binding found for " + player.getName());
                states.put(playerId, PlayerVerificationState.UNVERIFIED);
                if (config.get().player().forceVerification()
                        && config.get().qq().enabled() && config.get().qq().hasCredentials()) {
                    issueCode(player);
                }
                if (!config.get().player().forceVerification()) {
                    transferUnverified(player);
                }
            }
        }));
    }

    public void createAndShowCode(Player player, boolean forceNew) {
        assertMainThread();
        if (!authGateEnabled) {
            startManualBinding(player, forceNew);
            return;
        }
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
        VerificationCode code = bindingService.currentVerificationCode(player.getUniqueId()).orElse(null);
        if (code == null) {
            player.sendMessage(messages.message("code-expired"));
            return;
        }
        showCode(player, code);
        dialogService.show(player, code);
    }

    public void onBindingSuccess(BindingRecord binding) {
        onSocialBindingSuccess(binding.minecraftUuid());
    }

    public void onSocialBindingSuccess(UUID playerUuid) {
        completedBindings.add(playerUuid);
        bindingRewardListener.accept(playerUuid);
        runMain(() -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()
                    && states.get(playerUuid) == PlayerVerificationState.UNVERIFIED) {
                beginTransfer(player);
            } else {
                completedBindings.remove(playerUuid);
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
        bindingService.invalidateVerificationCode(playerId);
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

    private void transferUnverified(Player player) {
        assertMainThread();
        PluginConfig.Transfer transfer = config.get().player().transfer();
        if (!transfer.enabled()) {
            transferService.publishVerificationState(player, false);
            states.remove(player.getUniqueId());
            return;
        }
        transferService.publishVerificationState(player, false);
        player.sendMessage(messages.message("unbound-server-reminder", Map.of("group", displayGroup())));
        int delay = transfer.delaySeconds();
        if (delay <= 0) {
            finishUnverifiedTransfer(player, transfer.verifiedServer());
            return;
        }
        UUID playerId = player.getUniqueId();
        cancelTransfer(playerId);
        final int[] remaining = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                cancelTransfer(playerId);
                return;
            }
            if (remaining[0] <= 0) {
                cancelTransfer(playerId);
                finishUnverifiedTransfer(player, config.get().player().transfer().verifiedServer());
                return;
            }
            String seconds = Integer.toString(remaining[0]--);
            player.showTitle(Title.title(
                    messages.raw("unbound-transfer-countdown-title", Map.of("seconds", seconds)),
                    messages.raw("unbound-transfer-countdown-subtitle", Map.of("seconds", seconds)),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(150))
            ));
        }, 0L, 20L);
        transfers.put(playerId, task);
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

    private void finishUnverifiedTransfer(Player player, String serverName) {
        assertMainThread();
        if (!player.isOnline()) return;
        player.sendMessage(messages.message("unbound-transfer-now"));
        plugin.getLogger().info("[QQBot] Relaxed verification is enabled; transferring unbound player "
                + player.getName() + " to " + serverName);
        transferService.publishVerificationState(player, false);
        transferService.connect(player, serverName);
    }

    private void showInstructions(Player player, boolean openDialog) {
        VerificationCode code = bindingService.currentVerificationCode(player.getUniqueId()).orElse(null);
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
        bindingService.createVerificationCode(player.getUniqueId(), player.getName())
                .whenComplete((code, failure) -> runMain(() -> {
                    if (!player.isOnline()) return;
                    if (failure != null) {
                        plugin.getLogger().warning("[QQBot] Failed to create binding code: " + rootMessage(failure));
                        player.sendMessage(messages.message("service-unavailable"));
                        return;
                    }
                    scheduleCodeTimeout(player, code);
                    showCode(player, code);
                    dialogService.show(player, code);
                }));
    }

    private void scheduleCodeTimeout(Player player, VerificationCode code) {
        UUID playerId = player.getUniqueId();
        cancelCodeTimeout(playerId);
        long millis = Math.max(1, Duration.between(Instant.now(), code.expiresAt()).toMillis());
        long ticks = Math.max(1, (millis + 49) / 50);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            codeTimeouts.remove(playerId);
            if (completedBindings.contains(playerId)) return;
            bindingService.findByMinecraft(playerId).whenComplete((binding, failure) -> runMain(() -> {
                if (failure != null) {
                    plugin.getLogger().warning("[QQBot] Failed to check timed-out binding: "
                            + rootMessage(failure));
                    if (player.isOnline()) player.sendMessage(messages.message("service-unavailable"));
                    return;
                }
                if (binding.isPresent()) {
                    if (player.isOnline() && !authGateEnabled) {
                        player.closeDialog();
                        player.sendMessage(messages.message("already-verified"));
                    }
                    return;
                }
                bindingService.invalidateVerificationCode(playerId);
                if (!player.isOnline()) return;
                if (authGateEnabled && states.get(playerId) != PlayerVerificationState.UNVERIFIED) return;
                states.remove(playerId);
                transferService.publishVerificationState(player, false);
                plugin.getLogger().info("[QQBot] QQ verification timed out for " + player.getName());
                player.sendMessage(messages.message("verification-timeout"));
                if (authGateEnabled && config.get().player().forceVerification()) {
                    player.kick(messages.message("verification-timeout"));
                }
            }));
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

    private void startManualBinding(Player player, boolean forceNew) {
        UUID playerId = player.getUniqueId();
        bindingService.findByMinecraft(playerId).whenComplete((binding, failure) -> runMain(() -> {
            if (!player.isOnline()) return;
            if (failure != null) {
                player.sendMessage(messages.message("service-unavailable"));
            } else if (binding.isPresent()) {
                player.sendMessage(messages.message("already-verified"));
            } else {
                VerificationCode current = bindingService.currentVerificationCode(playerId).orElse(null);
                if (forceNew || current == null) issueCode(player);
                else {
                    showCode(player, current);
                    dialogService.show(player, current);
                }
            }
        }));
    }

    private void checkManualBinding(Player player, boolean userInitiated) {
        if (userInitiated) player.sendMessage(messages.message("checking"));
        bindingService.findByMinecraft(player.getUniqueId()).whenComplete((binding, failure) -> runMain(() -> {
            if (!player.isOnline()) return;
            if (failure != null) player.sendMessage(messages.message("service-unavailable"));
            else if (binding.isPresent()) {
                cancelCodeTimeout(player.getUniqueId());
                player.closeDialog();
                player.sendMessage(messages.message("already-verified"));
            } else {
                showInstructions(player, false);
            }
        }));
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
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && blockedInteraction(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && blockedInteraction(player)) {
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
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isAwaitingVerification(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                attacker = player;
            }
        }
        if (attacker != null && blockedInteraction(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isAwaitingVerification(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && blockedInteraction(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (blockedInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!isAwaitingVerification(event.getPlayer().getUniqueId())) {
            return;
        }
        UnboundChatRateLimiter.Decision decision = chatRateLimiter.acquire(
                event.getPlayer().getUniqueId(), config.get().player().chatCooldownSeconds());
        if (decision.allowed()) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.message(
                "chat-blocked", Map.of(
                        "group", displayGroup(),
                        "seconds", Long.toString(decision.remainingSeconds()))));
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
        chatRateLimiter.clear(playerId);
        if (config.get().player().forceVerification()) {
            bindingService.invalidateVerificationCode(playerId);
        }
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
