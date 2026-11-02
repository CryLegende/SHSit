package org.Cry_Legende.sHSit;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registre des places occupées. Chaque joueur assis monte sur un coussin posé par le plugin, non
 * sauvegardé, qui disparaît dès qu'il se relève. Toutes les règles anti-abus passent par sit().
 */
final class SeatManager {

    /** Position entière d'un bloc, sans garder le monde chargé en mémoire. */
    record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        Block block() {
            World world = Bukkit.getWorld(this.world);
            return world == null ? null : world.getBlockAt(x, y, z);
        }
    }

    /**
     * Une place occupée : le bloc qui porte le coussin, son état à l'assise (pour détecter qu'il a
     * changé), le point où le joueur se tenait avant et le centre de la place.
     */
    record Seat(UUID player, UUID cushion, BlockKey support, BlockData supportData, Location origin, Vector center) {
    }

    /** Résultat d'une tentative ; message est la clé du message à envoyer, ou null si le refus est silencieux. */
    enum Result {
        OK(null), COOLDOWN(null), UNAVAILABLE(null), BLOCKED(null), FAILED(null),
        WORLD("world"), OCCUPIED("occupied"), NO_ROOM("no-room"), ISLAND("island"), REGION("region"),
        NOT_ON_GROUND("not-on-ground");

        final String message;

        Result(String message) {
            this.message = message;
        }
    }

    private static final int[][] RING = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double[] LEVELS = {0.0, 0.5, 1.0, 2.0};

    private final SHSit plugin;
    private final RegionGuard regions;
    private final IslandGuard islands;
    private final NamespacedKey seatKey;

    private final Map<UUID, Seat> byPlayer = new HashMap<>();
    private final Map<UUID, Seat> byCushion = new HashMap<>();
    private final Map<BlockKey, UUID> byBlock = new HashMap<>();
    private final Map<UUID, Long> lastSit = new HashMap<>();
    /** Joueurs en cours de téléportation : le départ du coussin ne doit pas les ramener sur place. */
    private final Set<UUID> teleporting = new HashSet<>();
    private UUID pendingCushion;

    SeatManager(SHSit plugin, RegionGuard regions, IslandGuard islands) {
        this.plugin = plugin;
        this.regions = regions;
        this.islands = islands;
        this.seatKey = new NamespacedKey(plugin, "seat");
    }

    boolean isSitting(Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    /** Vrai pour tout coussin posé par ce plugin, même après un redémarrage. */
    boolean isSeatCushion(Entity entity) {
        return entity instanceof Cushion && entity.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE);
    }

    /** Vrai pendant que le plugin installe lui-même un joueur sur ce coussin. */
    boolean isPendingMount(Entity cushion) {
        return pendingCushion != null && pendingCushion.equals(cushion.getUniqueId());
    }

    /**
     * Assoit le joueur sur le bloc donné.
     *
     * @param support    bloc qui porte le coussin (un bloc ne porte qu'un joueur)
     * @param seat       point exact où se pose le coussin
     * @param clickPoint point cliqué sur le bloc, ou null pour /sit (la vue est alors inutile à vérifier)
     * @param yaw        orientation à donner au joueur, ou null pour ne pas le tourner
     */
    Result sit(Player player, Block support, Location seat, Vector clickPoint, Float yaw) {
        Result result = attempt(player, support, seat, clickPoint, yaw);
        if (plugin.settings().debug && result != Result.COOLDOWN) {
            plugin.getLogger().info(player.getName() + " -> " + result + " (" + support.getType() + " "
                    + support.getX() + " " + support.getY() + " " + support.getZ() + ")");
        }
        return result;
    }

    private Result attempt(Player player, Block support, Location seat, Vector clickPoint, Float yaw) {
        Settings settings = plugin.settings();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSit.get(id);
        if (last != null && now - last < settings.cooldownMillis) {
            return Result.COOLDOWN;
        }
        if (!isFree(player)) {
            return Result.UNAVAILABLE;
        }
        World world = seat.getWorld();
        if (!settings.worlds.isEmpty() && !settings.worlds.contains(world.getName())) {
            return Result.WORLD;
        }
        BlockKey key = BlockKey.of(support);
        if (byBlock.containsKey(key)) {
            return Result.OCCUPIED;
        }
        if (!player.hasPermission("shsit.bypass")) {
            if (regions != null && !regions.allows(player, seat)) {
                return Result.REGION;
            }
            if (islands != null && !islands.allows(player, seat, settings)) {
                return Result.ISLAND;
            }
        }

        // Anti-mur : le clic doit viser le siège sans rien au milieu, et le chemin du corps jusqu'au
        // siège doit être libre, sinon s'asseoir servirait à traverser un mur.
        Location feet = player.getLocation();
        Location eye = player.getEyeLocation();
        if (clickPoint != null) {
            if (eye.distanceSquared(seat) > settings.maxDistance * settings.maxDistance) {
                return Result.BLOCKED;
            }
            if (!Space.visible(world, eye.toVector(), clickPoint, support)) {
                return Result.BLOCKED;
            }
        }
        if (Math.abs(seat.getY() - feet.getY()) > 2.5) {
            return Result.BLOCKED;
        }
        Vector body = feet.toVector().add(new Vector(0.0, 0.9, 0.0));
        if (!Space.visible(world, body, seat.toVector().add(new Vector(0.0, 0.3, 0.0)), support)) {
            return Result.BLOCKED;
        }
        if (!Space.roomAbove(seat)) {
            return Result.NO_ROOM;
        }

        Cushion cushion;
        try {
            cushion = world.spawn(seat, Cushion.class, spawned -> {
                spawned.setColor(settings.cushionColor);
                spawned.setPersistent(false);
                spawned.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Impossible de poser un coussin : " + e.getMessage());
            return Result.FAILED;
        }
        if (!cushion.isValid()) {
            plugin.getLogger().warning("Le coussin n'a pas pu apparaître (apparition refusée par un autre plugin ?) en "
                    + seat.getBlockX() + " " + seat.getBlockY() + " " + seat.getBlockZ());
            return Result.FAILED;
        }

        boolean mounted;
        pendingCushion = cushion.getUniqueId();
        try {
            if (yaw != null) {
                player.setRotation(yaw, feet.getPitch());
            }
            mounted = cushion.addPassenger(player);
        } finally {
            pendingCushion = null;
        }
        if (!mounted || player.getVehicle() != cushion) {
            plugin.getLogger().warning(player.getName() + " n'a pas pu monter sur le coussin (montée refusée par un autre plugin ?)");
            cushion.remove();
            return Result.FAILED;
        }

        Seat placed = new Seat(id, cushion.getUniqueId(), key, normalized(support.getBlockData()), feet,
                seat.toVector().add(new Vector(0.0, 0.5, 0.0)));
        byPlayer.put(id, placed);
        byCushion.put(placed.cushion(), placed);
        byBlock.put(key, id);
        lastSit.put(id, now);
        return Result.OK;
    }

    /** Relève le joueur : retire le coussin et le replace sur un sol sûr. */
    void standUp(Player player) {
        Seat seat = byPlayer.get(player.getUniqueId());
        if (seat == null) {
            return;
        }
        unregister(seat);
        Entity cushion = Bukkit.getEntity(seat.cushion());
        if (cushion != null) {
            cushion.remove();
        }
        place(player, seat);
    }

    /** Le joueur vient de quitter le coussin (accroupi, téléportation, mort...). */
    void dismounted(Player player, Entity cushion) {
        Seat seat = byCushion.get(cushion.getUniqueId());
        if (seat == null) {
            return;
        }
        unregister(seat);
        boolean moved = teleporting.contains(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            cushion.remove();
            if (!moved && player.isOnline() && !player.isDead()) {
                place(player, seat);
            }
        });
    }

    /** À appeler quand une téléportation commence : le coussin sera retiré sans replacer le joueur. */
    void teleportStarted(Player player) {
        if (isSitting(player)) {
            UUID id = player.getUniqueId();
            teleporting.add(id);
            Bukkit.getScheduler().runTask(plugin, () -> teleporting.remove(id));
        }
    }

    void forget(Player player) {
        lastSit.remove(player.getUniqueId());
        teleporting.remove(player.getUniqueId());
    }

    /** Un bloc vient d'être cassé : celui qui s'y asseyait se relève tout de suite. */
    void supportBroken(Block block) {
        UUID id = byBlock.get(BlockKey.of(block));
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player != null) {
            standUp(player);
        }
    }

    /** Retire les coussins du plugin qui n'appartiennent à aucune place (reste d'un arrêt brutal). */
    void sweep(Collection<Entity> entities) {
        for (Entity entity : entities) {
            if (isSeatCushion(entity) && !byCushion.containsKey(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    void startJanitor() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 5L, 5L);
    }

    /** Relève tout le monde (arrêt du plugin) sans toucher à ceux qui sont déjà partis. */
    void releaseAll() {
        for (Seat seat : new ArrayList<>(byPlayer.values())) {
            Player player = Bukkit.getPlayer(seat.player());
            if (player != null && player.isOnline()) {
                standUp(player);
            } else {
                unregister(seat);
                Entity cushion = Bukkit.getEntity(seat.cushion());
                if (cushion != null) {
                    cushion.remove();
                }
            }
        }
    }

    /** Contrôle régulier : joueur parti, coussin disparu ou bloc modifié, la place est libérée. */
    private void check() {
        for (Seat seat : new ArrayList<>(byPlayer.values())) {
            Player player = Bukkit.getPlayer(seat.player());
            Entity cushion = Bukkit.getEntity(seat.cushion());
            if (player == null || !player.isOnline() || player.isDead()) {
                unregister(seat);
                if (cushion != null) {
                    cushion.remove();
                }
                continue;
            }
            if (cushion == null || !cushion.isValid() || player.getVehicle() != cushion) {
                unregister(seat);
                if (cushion != null) {
                    cushion.remove();
                }
                place(player, seat);
                continue;
            }
            Block block = seat.support().block();
            if (block == null || !normalized(block.getBlockData()).equals(seat.supportData())
                    || player.getGameMode() == GameMode.SPECTATOR) {
                standUp(player);
            }
        }
    }

    private boolean isFree(Player player) {
        return !player.isDead() && !player.isSleeping() && !player.isInsideVehicle()
                && player.getPassengers().isEmpty() && !player.isGliding() && !player.isSwimming()
                && !player.isFlying() && !player.isInWater() && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void unregister(Seat seat) {
        byPlayer.remove(seat.player());
        byCushion.remove(seat.cushion());
        byBlock.remove(seat.support());
    }

    /** L'eau qui coule sur une dalle ne doit pas relever le joueur : on ignore waterlogged. */
    private static BlockData normalized(BlockData data) {
        BlockData copy = data.clone();
        if (copy instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(false);
        }
        return copy;
    }

    /**
     * Replace le joueur après son départ du coussin. Le point où Minecraft le dépose est gardé s'il
     * est libre ; sinon on cherche un sol libre près du siège, visible depuis le siège (jamais de
     * l'autre côté d'un mur), en commençant par l'endroit où il se tenait avant de s'asseoir.
     */
    private void place(Player player, Seat seat) {
        World world = player.getWorld();
        if (!player.isOnline() || player.isDead() || !world.getUID().equals(seat.support().world())) {
            return;
        }
        Vector center = seat.center();
        if (usable(world, center, player.getLocation())) {
            return;
        }
        Location origin = seat.origin();
        if (origin.getWorld() != null && origin.getWorld().equals(world) && usable(world, center, origin)) {
            player.teleport(origin, PlayerTeleportEvent.TeleportCause.PLUGIN);
            return;
        }
        List<Location> candidates = new ArrayList<>();
        int baseX = (int) Math.floor(center.getX());
        int baseZ = (int) Math.floor(center.getZ());
        double baseY = Math.floor(center.getY() - 0.5 + 1.0e-6);
        for (int[] offset : RING) {
            for (double level : LEVELS) {
                Location candidate = new Location(world, baseX + offset[0] + 0.5, baseY + level, baseZ + offset[1] + 0.5,
                        player.getLocation().getYaw(), player.getLocation().getPitch());
                if (usable(world, center, candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distanceSquared(origin)));
        if (!candidates.isEmpty()) {
            player.teleport(candidates.get(0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    private boolean usable(World world, Vector center, Location feet) {
        return feet.distanceSquared(center.toLocation(world)) <= 9.0 && Space.standable(feet)
                && Space.visible(world, center, feet.toVector().add(new Vector(0.0, 0.9, 0.0)), null);
    }
}
