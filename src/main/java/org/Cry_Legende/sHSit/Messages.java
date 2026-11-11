package org.Cry_Legende.sHSit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Textes de messages.yml (codes &amp; et couleurs hex &amp;#RRGGBB). Une clé absente du fichier prend le texte du jar,
 * une clé vide désactive le message.
 */
final class Messages {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    /** Clés de l'ancienne section messages: de config.yml dont la forme a changé : leur texte n'est pas repris. */
    private static final Set<String> NOT_MIGRATED = Set.of("usage");

    private final Map<String, String> texts;

    private Messages(Map<String, String> texts) {
        this.texts = texts;
    }

    /**
     * Crée messages.yml s'il n'existe pas : le fichier du jar, dont les textes sont remplacés par ceux de l'ancienne
     * section messages: de config.yml quand elle existe.
     */
    static void prepare(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (file.exists()) {
            return;
        }
        plugin.saveResource("messages.yml", false);
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("messages");
        if (legacy == null) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        for (String key : legacy.getKeys(false)) {
            if (!NOT_MIGRATED.contains(key) && legacy.isString(key) && yaml.isString(key)) {
                yaml.set(key, legacy.getString(key));
                changed = true;
            }
        }
        if (changed) {
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Écriture de messages.yml impossible : " + e.getMessage());
            }
        }
    }

    /** Lit messages.yml ; lève l'erreur YAML telle quelle pour que l'appelant garde l'ancien état. */
    static Messages load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(plugin.getDataFolder(), "messages.yml"));
        return merge(yaml, jarDefaults(plugin));
    }

    /** Textes du jar seuls, quand messages.yml est illisible au démarrage. */
    static Messages defaults(JavaPlugin plugin) {
        return merge(new YamlConfiguration(), jarDefaults(plugin));
    }

    private static Messages merge(YamlConfiguration file, YamlConfiguration defaults) {
        Set<String> keys = new LinkedHashSet<>(defaults.getKeys(false));
        keys.addAll(file.getKeys(false));
        Map<String, String> texts = new HashMap<>();
        for (String key : keys) {
            String value = file.isString(key) ? file.getString(key) : defaults.getString(key);
            if (value != null && !value.isEmpty()) {
                texts.put(key, value);
            }
        }
        return new Messages(texts);
    }

    private static YamlConfiguration jarDefaults(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("messages.yml")) {
            return in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /** Message de la clé, ou null s'il est absent ou vide. Chaque paire de {@code pairs} remplace un {nom} par sa valeur. */
    Component get(String key, String... pairs) {
        String text = texts.get(key);
        if (text == null) {
            return null;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return LEGACY.deserialize(text);
    }
}
