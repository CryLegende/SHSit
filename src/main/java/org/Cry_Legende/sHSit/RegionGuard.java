package org.Cry_Legende.sHSit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Protection des régions WorldGuard. Le flag « sit » (le même que celui de GSit, donc les régions
 * existantes gardent leur réglage) autorise ou interdit de s'asseoir ; sans flag, il faut pouvoir
 * construire dans la région.
 */
final class RegionGuard {

    private static StateFlag sitFlag;

    /** À appeler dans onLoad : WorldGuard n'accepte plus de flag une fois activé. */
    static void registerFlag(Logger logger) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag flag = new StateFlag("sit", true);
            registry.register(flag);
            sitFlag = flag;
        } catch (FlagConflictException e) {
            Flag<?> existing = registry.get("sit");
            if (existing instanceof StateFlag stateFlag) {
                sitFlag = stateFlag;
            } else {
                logger.warning("Le flag WorldGuard « sit » existe déjà avec un autre type : il est ignoré.");
            }
        } catch (IllegalStateException e) {
            // WorldGuard déjà activé (rechargement à chaud) : on reprend le flag enregistré au démarrage
            Flag<?> existing = registry.get("sit");
            if (existing instanceof StateFlag stateFlag) {
                sitFlag = stateFlag;
            } else {
                logger.warning("Flag WorldGuard « sit » non enregistré (WorldGuard déjà activé) : "
                        + "seul le droit de construire compte.");
            }
        }
    }

    boolean allows(Player player, Location where) {
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        com.sk89q.worldedit.util.Location location = BukkitAdapter.adapt(where);
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        if (sitFlag != null) {
            StateFlag.State state = query.queryState(location, localPlayer, sitFlag);
            if (state == StateFlag.State.DENY) {
                return false;
            }
            if (state == StateFlag.State.ALLOW) {
                return true;
            }
        }
        // Sans flag : il faut pouvoir construire ici. Le contournement WorldGuard compte, mais pas
        // contre un « sit: deny » explicite (seul shsit.bypass passe outre).
        return WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(where.getWorld()))
                || query.testBuild(location, localPlayer);
    }
}
