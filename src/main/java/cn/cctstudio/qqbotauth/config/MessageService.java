package cn.cctstudio.qqbotauth.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class MessageService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Component message(String key) {
        return message(key, Map.of());
    }

    public Component message(String key, Map<String, String> values) {
        FileConfiguration config = plugin.getConfig();
        String prefix = config.getString("messages.prefix", "");
        String template = config.getString("messages." + key, "<red>Missing message: " + key + "</red>");
        TagResolver[] resolvers = values.entrySet().stream()
                .map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
        return miniMessage.deserialize(prefix + template, resolvers);
    }

    public Component raw(String key, Map<String, String> values) {
        String template = plugin.getConfig().getString("messages." + key, "<red>Missing message: " + key + "</red>");
        TagResolver[] resolvers = values.entrySet().stream()
                .map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
        return miniMessage.deserialize(template, resolvers);
    }

    public String plain(String key) {
        return plugin.getConfig().getString("messages." + key, key);
    }
}
