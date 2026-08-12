package cn.cctstudio.qqbotauth.velocity;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ServerTransferService {
    public static final String BUNGEE_CHANNEL = "BungeeCord";
    public static final String STATE_CHANNEL = "qqbotauth:state";

    private final JavaPlugin plugin;

    public ServerTransferService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, STATE_CHANNEL);
    }

    public void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, STATE_CHANNEL);
    }

    public void publishVerificationState(Player player, boolean verified) {
        assertMainThread();
        byte[] payload = new VerificationStateMessage(player.getUniqueId(), verified).encode();
        player.sendPluginMessage(plugin, STATE_CHANNEL, payload);
    }

    public void connect(Player player, String serverName) {
        assertMainThread();
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("Target server cannot be blank");
        }
        byte[] serverBytes = serverName.getBytes(StandardCharsets.UTF_8);
        if (serverBytes.length > 64) {
            throw new IllegalArgumentException("Target server name is too long");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("Connect");
                output.writeUTF(serverName);
            }
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not build proxy transfer message", impossible);
        }
    }

    private void assertMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Bukkit plugin messages must be sent on the server thread");
        }
    }
}
