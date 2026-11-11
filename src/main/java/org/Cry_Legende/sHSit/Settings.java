package org.Cry_Legende.sHSit;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Valeurs de config.yml, lues une fois à chaque chargement. */
final class Settings {

    final Set<String> worlds;
    final DyeColor cushionColor;
    final long cooldownMillis;
    final boolean emptyHand;
    final double maxDistance;
    final boolean standUpOnDamage;
    final boolean faceOpenSide;
    final boolean debug;
    final Set<Kind> enabled;
    final int maxStack;
    final boolean ejectOnSneak;
    final Set<Material> seatBlocks;
    final boolean islandCoop;
    final boolean islandVisitors;

    private Settings(JavaPlugin plugin, FileConfiguration config) {
        worlds = new HashSet<>(config.getStringList("settings.worlds"));
        cushionColor = parseColor(plugin, config.getString("settings.cushion-color", "WHITE"));
        cooldownMillis = Math.max(0L, Math.round(config.getDouble("settings.cooldown", 1.0) * 1000.0));
        emptyHand = config.getBoolean("settings.empty-hand", true);
        maxDistance = Math.max(1.0, config.getDouble("settings.max-distance", 4.5));
        standUpOnDamage = config.getBoolean("settings.stand-up-on-damage", true);
        faceOpenSide = config.getBoolean("settings.face-open-side", true);
        debug = config.getBoolean("settings.debug", false);
        enabled = EnumSet.noneOf(Kind.class);
        for (Kind kind : Kind.values()) {
            if (config.getBoolean("features." + kind.id, true)) {
                enabled.add(kind);
            }
        }
        maxStack = Math.max(1, config.getInt("playersit.max-stack", 3));
        ejectOnSneak = config.getBoolean("playersit.eject-on-sneak", true);
        seatBlocks = parseBlocks(plugin, config.getStringList("blocks"));
        islandCoop = config.getBoolean("island.coop", true);
        islandVisitors = config.getBoolean("island.visitors", false);
    }

    static Settings load(JavaPlugin plugin) {
        return new Settings(plugin, plugin.getConfig());
    }

    private static DyeColor parseColor(JavaPlugin plugin, String name) {
        try {
            return DyeColor.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Couleur de coussin inconnue : " + name + " (WHITE utilisée)");
            return DyeColor.WHITE;
        }
    }

    private static Set<Material> parseBlocks(JavaPlugin plugin, Iterable<String> entries) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(entry.substring(1));
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    plugin.getLogger().warning("Tag de blocs inconnu : " + entry);
                } else {
                    result.addAll(tag.getValues());
                }
            } else {
                Material material = Material.matchMaterial(entry);
                if (material == null || !material.isBlock()) {
                    plugin.getLogger().warning("Bloc inconnu : " + entry);
                } else {
                    result.add(material);
                }
            }
        }
        return result;
    }
}
