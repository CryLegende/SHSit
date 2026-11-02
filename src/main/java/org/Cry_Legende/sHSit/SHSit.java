package org.Cry_Legende.sHSit;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SHSit : s'asseoir sur un coussin de Minecraft 26.3 (remplace GSit). Les protections d'île
 * (SuperiorSkyblock2) et de région (WorldGuard) sont facultatives : sans elles, seules les règles
 * du plugin s'appliquent.
 */
public final class SHSit extends JavaPlugin {

    private Settings settings;
    private SeatManager seats;

    @Override
    public void onLoad() {
        // Le flag « sit » doit exister avant l'activation de WorldGuard, qui charge alors les régions.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                RegionGuard.registerFlag(getLogger());
            } catch (LinkageError e) {
                getLogger().warning("WorldGuard illisible, flag « sit » ignoré : " + e);
            }
        }
    }

    @Override
    public void onEnable() {
        if (Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("cushion")) == null) {
            getLogger().severe("Ce serveur ne connaît pas le coussin (Minecraft 26.3 requis) : SHSit est désactivé.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        settings = Settings.load(this);

        RegionGuard regions = null;
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

        seats = new SeatManager(this, regions, islands);
        SitListener listener = new SitListener(this, seats);
        getServer().getPluginManager().registerEvents(listener, this);

        SitCommand executor = new SitCommand(this, seats, listener);
        for (String name : new String[] {"sit", "shsit"}) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }
        seats.startJanitor();
        getLogger().info("SHSit prêt (" + settings.seatBlocks.size() + " types de blocs, île : "
                + (islands != null ? "oui" : "non") + ", régions : " + (regions != null ? "oui" : "non") + ").");
    }

    @Override
    public void onDisable() {
        if (seats != null) {
            seats.releaseAll();
        }
    }

    Settings settings() {
        return settings;
    }

    void reload() {
        reloadConfig();
        settings = Settings.load(this);
    }
}
