package cn.cctstudio.qqbotauth.authme;

import cn.cctstudio.qqbotauth.minecraft.PlayerVerificationManager;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuthMeHook implements Listener {
    private final JavaPlugin plugin;
    private final PlayerVerificationManager verificationManager;
    private AuthMeApi authMeApi;

    public AuthMeHook(JavaPlugin plugin, PlayerVerificationManager verificationManager) {
        this.plugin = plugin;
        this.verificationManager = verificationManager;
    }

    public void register() {
        if (!Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            throw new IllegalStateException("AuthMe is required but is not enabled");
        }
        authMeApi = AuthMeApi.getInstance();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean isAuthenticated(Player player) {
        return authMeApi != null && authMeApi.isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        verificationManager.onAuthenticated(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSessionRestored(RestoreSessionEvent event) {
        verificationManager.onAuthenticated(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogout(LogoutEvent event) {
        verificationManager.onAuthenticationLost(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isAuthenticated(player)) {
                verificationManager.onAuthenticated(player);
            }
        });
    }
}
