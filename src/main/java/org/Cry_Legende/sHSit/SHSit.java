package org.Cry_Legende.sHSit;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * SHSit : s'asseoir, s'allonger, ramper, tournoyer et s'asseoir sur la tête des autres (remplace GSit).
 * Les sièges sont des paquets (packetevents), pas des entités. Les protections d'île (SuperiorSkyblock2)
 * et de région (WorldGuard) sont facultatives : sans elles, seules les règles du plugin s'appliquent.
 */
public final class SHSit extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private Sessions sessions;
    private RegionGuard regions;

    @Override
    public void onLoad() {
        // Les flags doivent exister avant l'activation de WorldGuard, qui charge alors les régions.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                RegionGuard.registerFlags(getLogger());
            } catch (LinkageError e) {
                getLogger().warning("WorldGuard illisible, flags ignorés : " + e);
            }
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(this);
        // Crée messages.yml au premier démarrage, avec les textes de l'ancienne section messages: de config.yml
        Messages.prepare(this);
        try {
            messages = Messages.load(this);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("messages.yml illisible, textes du jar utilisés : " + e.getMessage());
            messages = Messages.defaults(this);
        }

        if (getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                regions = new RegionGuard();
            } catch (LinkageError e) {
                getLogger().warning("WorldGuard illisible, protection des régions désactivée : " + e);
            }
        }
        IslandGuard islands = null;
        if (getServer().getPluginManager().isPluginEnabled("SuperiorSkyblock2")) {
            try {
                islands = new IslandGuard();
            } catch (LinkageError e) {
                getLogger().warning("SuperiorSkyblock2 illisible, protection des îles désactivée : " + e);
            }
        }

        sessions = new Sessions(this, regions, islands);
        SitListener listener = new SitListener(this, sessions);
        getServer().getPluginManager().registerEvents(listener, this);

        SitCommand executor = new SitCommand(this, sessions, listener);
        for (String name : new String[] {"sit", "lay", "spin", "bellyflop", "crawl", "shsit"}) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }
        sessions.startJanitor();
        getLogger().info("SHSit prêt (" + settings.enabled.size() + " fonctions, " + settings.seatBlocks.size()
                + " types de blocs, île : " + (islands != null ? "oui" : "non") + ", régions : "
                + (regions != null ? "oui" : "non") + ").");
    }

    @Override
    public void onDisable() {
        if (sessions != null) {
            sessions.releaseAll();
        }
    }

    Settings settings() {
        return settings;
    }

    Messages messages() {
        return messages;
    }

    Sessions sessions() {
        return sessions;
    }

    RegionGuard regions() {
        return regions;
    }

    /**
     * Relit config.yml et messages.yml. Les deux sont lus à part et ne remplacent l'état en cours que s'ils sont
     * valides ; sinon l'ancienne configuration reste active et l'exception porte le fichier en cause.
     */
    void reload() throws ReloadException {
        readYaml("config.yml");
        Messages loaded;
        try {
            readYaml("messages.yml");
            loaded = Messages.load(this);
        } catch (IOException | InvalidConfigurationException e) {
            throw new ReloadException("messages.yml", e);
        }
        reloadConfig();
        settings = Settings.load(this);
        messages = loaded;
    }

    private YamlConfiguration readYaml(String name) throws ReloadException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), name));
        } catch (IOException | InvalidConfigurationException e) {
            throw new ReloadException(name, e);
        }
        return yaml;
    }

    /** Fichier de configuration illisible pendant un reload. */
    static final class ReloadException extends Exception {

        final String file;

        ReloadException(String file, Throwable cause) {
            super(cause.getMessage() == null ? cause.toString() : cause.getMessage().lines().findFirst().orElse(""), cause);
            this.file = file;
        }
    }
}
