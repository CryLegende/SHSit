package org.Cry_Legende.sHSit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Protection des régions WorldGuard. Quatre flags d'état (allow / deny), les mêmes noms que GSit pour
 * que les régions existantes gardent leur réglage : « sit », « pose » (couché, bellyflop, spin),
 * « crawl » et « playersit ». Aucune monture n'est créée pour s'asseoir, donc les flags « ride » et
 * « interact » ne gênent pas : seuls ces flags comptent. Seul un flag réellement posé sur une région
 * (ou sur la région globale du monde) décide ; sans flag, il faut pouvoir construire dans la région.
 * Les groupes de région des flags (membres / non-membres) ne sont pas utilisés.
 */
final class RegionGuard {

    private static final String[] FLAGS = {"sit", "pose", "crawl", "playersit"};
    private static final Map<String, StateFlag> REGISTERED = new HashMap<>();

    /** À appeler dans onLoad : WorldGuard n'accepte plus de flag une fois activé. */
    static void registerFlags(Logger logger) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        for (String name : FLAGS) {
            try {
                StateFlag flag = new StateFlag(name, true);
                registry.register(flag);
                REGISTERED.put(name, flag);
            } catch (FlagConflictException | IllegalStateException e) {
                // déjà enregistré (autre plugin, ou rechargement à chaud) : on reprend celui qui existe
                Flag<?> existing = registry.get(name);
                if (existing instanceof StateFlag stateFlag) {
                    REGISTERED.put(name, stateFlag);
                } else {
                    logger.warning("Flag WorldGuard « " + name + " » indisponible : seul le droit de construire compte.");
                }
            }
        }
    }

    /** Vrai si la région autorise cette pose à cet endroit. */
    boolean allows(Player player, Location where, Kind kind) {
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        com.sk89q.worldedit.util.Location location = BukkitAdapter.adapt(where);
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        StateFlag.State explicit = explicit(where, kind);
        if (explicit == StateFlag.State.DENY) {
            return false;
        }
        if (explicit == StateFlag.State.ALLOW) {
            return true;
        }
        // Sans flag : il faut pouvoir construire ici. Le contournement WorldGuard compte, mais pas
        // contre un « deny » explicite (seul shsit.bypass passe outre).
        return WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(where.getWorld()))
                || query.testBuild(location, localPlayer);
    }

    /**
     * Vrai si un flag « allow » posé sur une région couvre cet endroit. Sert à traiter un clic que
     * WorldGuard a annulé (flag interact: deny) : la région a dit oui à cette pose, elle reste possible.
     */
    boolean explicitlyAllows(Location where, Kind kind) {
        return explicit(where, kind) == StateFlag.State.ALLOW;
    }

    /** Détail de la décision, pour le journal de debug. */
    String describe(Player player, Location where, Kind kind) {
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        return "flag " + kind.flag + "=" + (REGISTERED.containsKey(kind.flag) ? String.valueOf(explicit(where, kind)) : "non enregistré")
                + ", contournement WG=" + WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(where.getWorld()))
                + ", testBuild=" + query.testBuild(BukkitAdapter.adapt(where), localPlayer);
    }

    /**
     * La valeur posée par les régions de cet endroit (null si aucune n'en pose), comme WorldGuard la
     * calcule : la priorité la plus haute l'emporte, à égalité « deny » l'emporte, une région sans valeur
     * hérite de son parent, et la région globale du monde ne compte qu'en dernier recours.
     */
    private static StateFlag.State explicit(Location where, Kind kind) {
        StateFlag flag = REGISTERED.get(kind.flag);
        if (flag == null) {
            return null;
        }
        ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(where));
        StateFlag.State best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (ProtectedRegion region : set.getRegions()) {
            StateFlag.State value = region.getFlag(flag);
            for (ProtectedRegion parent = region.getParent(); value == null && parent != null; parent = parent.getParent()) {
                value = parent.getFlag(flag);
            }
            if (value == null) {
                continue;
            }
            if (region.getPriority() > bestPriority) {
                best = value;
                bestPriority = region.getPriority();
            } else if (region.getPriority() == bestPriority && value == StateFlag.State.DENY) {
                best = value;
            }
        }
        if (best == null) {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(where.getWorld()));
            ProtectedRegion global = manager == null ? null : manager.getRegion(ProtectedRegion.GLOBAL_REGION);
            best = global == null ? null : global.getFlag(flag);
        }
        return best;
    }
}
