package org.Cry_Legende.sHSit;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * %shsit_pose% : la pose du joueur (sit, lay, spin, bellyflop, crawl, playersit), vide s'il n'est pas posé.
 * %shsit_assis% : true si le joueur est assis (sur un bloc, sur place ou sur la tête d'un joueur), false sinon.
 * Seule classe du plugin qui touche à PlaceholderAPI : elle n'est chargée que s'il est présent.
 */
final class PoseExpansion extends PlaceholderExpansion {

    private final SHSit plugin;
    private final Sessions sessions;

    PoseExpansion(SHSit plugin, Sessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shsit";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        String pose = sessions.poseOf(player.getUniqueId());
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "pose" -> pose;
            case "assis" -> String.valueOf(pose.equals(Kind.SIT.id) || pose.equals(Kind.PLAYERSIT.id));
            default -> null;
        };
    }
}
