package cn.cctstudio.qqbotauth.minecraft;

import cn.cctstudio.qqbotauth.config.MessageService;
import cn.cctstudio.qqbotauth.config.PluginConfig;
import cn.cctstudio.qqbotauth.verification.VerificationCode;
import cn.cctstudio.qqbotauth.util.HumanDurationFormatter;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class VerificationDialogService implements Listener {
    private static final Key REFRESH_KEY = Key.key("qqbotauth", "refresh");
    private static final Key CHECK_KEY = Key.key("qqbotauth", "check");

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final Supplier<PluginConfig> config;
    private PlayerVerificationManager verificationManager;

    public VerificationDialogService(
            JavaPlugin plugin,
            MessageService messages,
            Supplier<PluginConfig> config
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
    }

    public void register(PlayerVerificationManager manager) {
        this.verificationManager = manager;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void show(Player player, VerificationCode code) {
        assertMainThread();
        if (!config.get().player().dialogEnabled()) {
            return;
        }
        Map<String, String> values = Map.of(
                "group", displayGroup(),
                "code", code.value(),
                "expire", HumanDurationFormatter.remainingUntil(code.expiresAt())
        );
        Component body = messages.raw("dialog-body", values);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(messages.raw("dialog-title", values))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(body, 360)))
                        .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.builder(Component.text(messages.plain("dialog-refresh")))
                                .tooltip(Component.text("生成新的单次验证码"))
                                .action(DialogAction.customClick(REFRESH_KEY, null))
                                .build(),
                        ActionButton.builder(Component.text(messages.plain("dialog-check")))
                                .tooltip(Component.text("检查 QQ 群绑定是否已经完成"))
                                .action(DialogAction.customClick(CHECK_KEY, null))
                                .build()
                )).columns(2).build())
        );
        player.showDialog(dialog);
    }

    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        if (event.getIdentifier().equals(REFRESH_KEY)) {
            verificationManager.createAndShowCode(player, true);
        } else if (event.getIdentifier().equals(CHECK_KEY)) {
            verificationManager.checkBinding(player, true);
        }
    }

    private String displayGroup() {
        String group = config.get().qq().groupNumber();
        return group.isBlank() ? "服主配置的验证群" : group;
    }

    private void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Dialog operations must run on the server thread");
        }
    }
}
