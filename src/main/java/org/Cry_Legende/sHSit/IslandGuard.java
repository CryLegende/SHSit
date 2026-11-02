package org.Cry_Legende.sHSit;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Protection des îles SuperiorSkyblock2 : seuls les membres (et les coop) s'assoient sur une île. */
final class IslandGuard {

    boolean allows(Player player, Location where, Settings settings) {
        Island island = SuperiorSkyblockAPI.getIslandAt(where);
        // L'île spawn est publique : seules les régions WorldGuard y règlent l'assise
        if (island == null || island.isSpawn()) {
            return true;
        }
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
        if (superiorPlayer == null) {
            return false;
        }
        if (superiorPlayer.hasBypassModeEnabled() || island.isMember(superiorPlayer)) {
            return true;
        }
        if (settings.islandCoop && island.isCoop(superiorPlayer)) {
            return true;
        }
        return settings.islandVisitors;
    }
}
