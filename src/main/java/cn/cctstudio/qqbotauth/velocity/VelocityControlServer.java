package cn.cctstudio.qqbotauth.velocity;

import cn.cctstudio.qqbotauth.util.NamedThreadFactory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Velocity-side loopback listener. It is an embedded plugin transport, not a web service. */
final class VelocityControlServer implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Set<UUID> verifiedOnlinePlayers;
    private final InetAddress bindAddress;
    private final int port;
    private final String secret;
    private final boolean disconnectOnUnbind;
    private final Component disconnectMessage;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket serverSocket;

    VelocityControlServer(
            Object plugin,
            ProxyServer proxy,
            Logger logger,
            Set<UUID> verifiedOnlinePlayers,
            String bindAddress,
            int port,
            String secret,
            boolean disconnectOnUnbind,
            String disconnectMessage
    ) throws IOException {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.verifiedOnlinePlayers = verifiedOnlinePlayers;
        this.bindAddress = InetAddress.getByName(bindAddress);
        if (!this.bindAddress.isLoopbackAddress()) {
            throw new IOException("control-bind-address must be a loopback address");
        }
        this.port = port;
        this.secret = secret;
        this.disconnectOnUnbind = disconnectOnUnbind;
        this.disconnectMessage = Component.text(disconnectMessage, NamedTextColor.RED);
        this.executor = Executors.newSingleThreadExecutor(new NamedThreadFactory(
                "QQBotAuth-Velocity-Control-", failure -> logger.error(
                "[QQBot] Velocity control thread failed", failure)));
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, port), 16);
            serverSocket = socket;
            executor.execute(this::acceptLoop);
        } catch (IOException | RuntimeException exception) {
            running.set(false);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw exception;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket connection = serverSocket.accept()) {
                handle(connection);
            } catch (IOException exception) {
                if (running.get()) {
                    logger.warn("[QQBot] Velocity control connection failed: {}", exception.getMessage());
                }
            }
        }
    }

    private void handle(Socket connection) throws IOException {
        if (!connection.getInetAddress().isLoopbackAddress()) {
            return;
        }
        connection.setSoTimeout(3000);
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(connection.getInputStream()));
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(connection.getOutputStream()))) {
            Optional<VelocityControlMessage> decoded = VelocityControlMessage.readFrom(input, secret);
            if (decoded.isEmpty()) {
                output.writeByte(0);
                output.flush();
                logger.warn("[QQBot] Rejected an invalid Velocity control message");
                return;
            }
            VelocityControlMessage message = decoded.orElseThrow();
            UUID playerUuid = message.playerUuid();
            if (message.action() == VelocityControlMessage.Action.BOUND) {
                verifiedOnlinePlayers.add(playerUuid);
            } else {
                verifiedOnlinePlayers.remove(playerUuid);
                if (disconnectOnUnbind) {
                    proxy.getScheduler().buildTask(plugin, () -> disconnect(playerUuid)).schedule();
                }
            }
            output.writeByte(1);
            output.flush();
        }
    }

    private void disconnect(UUID playerUuid) {
        Optional<Player> online = proxy.getPlayer(playerUuid);
        if (online.isPresent()) {
            String playerName = online.get().getUsername();
            online.get().disconnect(disconnectMessage);
            logger.info("[QQBot] Disconnected unbound player {} from the proxy", playerName);
        }
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        executor.shutdownNow();
    }
}
