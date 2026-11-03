package org.Cry_Legende.sHSit;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Toutes les poses en cours, une par joueur, et toutes les règles anti-abus (start*). Assis et spin :
 * faux véhicule en paquets. Couché : métadonnées de sommeil en paquets. Ramper et bellyflop : vraie nage
 * côté serveur et plafond fantôme côté client. Sur la tête d'un joueur : vraie monture, pour que les
 * flags WorldGuard « ride » et le parkour la voient.
 */
final class Sessions {

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

    static final class Session {
        final Player player;
        final Kind kind;
        /** Où le joueur se tenait avant la pose. */
        final Location origin;
        /** Où le serveur voit le joueur pendant la pose : il ne doit plus bouger. */
        Location rest;
        Vector center = new Vector();
        BlockKey support;
        BlockData supportData;
        int vehicleId;
        UUID vehicleUuid;
        Location vehicleAt;
        UUID carrier;
        Location bed;
        BlockData bedReal;
        BlockData bedFake;
        boolean sleepingIgnored;
        Location ceiling;
        BlockData ceilingFake;
        final Set<UUID> viewers = new HashSet<>();
        int age;

        Session(Player player, Kind kind) {
            this.player = player;
            this.kind = kind;
            this.origin = player.getLocation();
        }
    }

    /** Résultat d'une tentative ; message est la clé du message à envoyer, ou null si le refus est silencieux. */
    enum Result {
        OK(null), COOLDOWN(null), UNAVAILABLE(null), BLOCKED(null), FAILED(null), DISABLED(null),
        WORLD("world"), OCCUPIED("occupied"), NO_ROOM("no-room"), ISLAND("island"), REGION("region"),
        NOT_ON_GROUND("not-on-ground"), TARGET("target-refuses"), STACK("stack-full");

        final String message;

        Result(String message) {
            this.message = message;
        }
    }

    private static final int[][] RING = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double[] LEVELS = {0.0, 0.5, 1.0, 2.0};
    /** Hauteur de la tête assise au-dessus de la surface du siège (pieds à 0,6 sous la surface, yeux à 1,62 des pieds). */
    private static final double SEAT_DROP = 0.6;
    /** Décalage de l'entité couchée vers la tête par rapport au centre du corps (voir attemptLay). */
    private static final double HEAD_SHIFT = 0.6;

    private final SHSit plugin;
    private final RegionGuard regions;
    private final IslandGuard islands;
    private final PacketHook hook;
    private final NamespacedKey offKey;
    private final NamespacedKey freezeKey;

    private final Map<UUID, Session> byPlayer = new HashMap<>();
    private final Map<BlockKey, UUID> byBlock = new HashMap<>();
    private final Map<UUID, Long> lastStart = new HashMap<>();
    private final Set<UUID> selfTeleport = new HashSet<>();
    private CrawlHandler crawlHandler;
    private int crawlers;

    Sessions(SHSit plugin, RegionGuard regions, IslandGuard islands, PacketHook hook) {
        this.plugin = plugin;
        this.regions = regions;
        this.islands = islands;
        this.hook = hook;
        this.offKey = new NamespacedKey(plugin, "playersit_off");
        this.freezeKey = new NamespacedKey(plugin, "bellyflop");
    }

    Session of(Player player) {
        return byPlayer.get(player.getUniqueId());
    }

    boolean has(Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------------ règles communes

    private Result precheck(Player player, Kind kind, Location guardAt) {
        Settings settings = plugin.settings();
        if (!settings.enabled.contains(kind)) {
            return Result.DISABLED;
        }
        Long last = lastStart.get(player.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < settings.cooldownMillis) {
            return Result.COOLDOWN;
        }
        if (!isFree(player)) {
            return Result.UNAVAILABLE;
        }
        World world = guardAt.getWorld();
        if (!settings.worlds.isEmpty() && !settings.worlds.contains(world.getName())) {
            return Result.WORLD;
        }
        if (!player.hasPermission("shsit.bypass")) {
            if (plugin.settings().debug && regions != null) {
                plugin.getLogger().info(player.getName() + " -> région : " + regions.describe(player, guardAt, kind));
            }
            if (regions != null && !regions.allows(player, guardAt, kind)) {
                return Result.REGION;
            }
            if (islands != null && !islands.allows(player, guardAt, settings)) {
                return Result.ISLAND;
            }
        }
        return null;
    }

    private boolean isFree(Player player) {
        return !player.isDead() && !player.isSleeping() && !player.isInsideVehicle()
                && player.getPassengers().isEmpty() && !player.isGliding() && !player.isSwimming()
                && !player.isFlying() && !player.isInWater() && player.getGameMode() != GameMode.SPECTATOR;
    }

    private Result logged(Player player, Kind kind, Result result) {
        if (plugin.settings().debug && result != Result.COOLDOWN) {
            plugin.getLogger().info(player.getName() + " -> " + kind.id + " " + result);
        }
        return result;
    }

    // ------------------------------------------------------------------ assis et spin (faux véhicule)

    /**
     * @param support    bloc qui porte le siège (un bloc ne porte qu'un joueur)
     * @param seat       surface où se posent les fesses, au niveau du bloc
     * @param clickPoint point cliqué sur le bloc, ou null pour une commande
     * @param yaw        orientation à donner au joueur, ou null pour ne pas le tourner
     */
    Result startSeat(Player player, Kind kind, Block support, Location seat, Vector clickPoint, Float yaw) {
        return logged(player, kind, attemptSeat(player, kind, support, seat, clickPoint, yaw));
    }

    private Result attemptSeat(Player player, Kind kind, Block support, Location seat, Vector clickPoint, Float yaw) {
        Result refused = precheck(player, kind, seat);
        if (refused != null) {
            return refused;
        }
        Settings settings = plugin.settings();
        World world = seat.getWorld();
        BlockKey key = BlockKey.of(support);
        if (byBlock.containsKey(key)) {
            return Result.OCCUPIED;
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
        if (!Space.visible(world, feet.toVector().add(new Vector(0.0, 0.9, 0.0)),
                seat.toVector().add(new Vector(0.0, 0.3, 0.0)), support)) {
            return Result.BLOCKED;
        }

        boolean spin = kind == Kind.SPIN;
        if (!Space.roomAbove(seat, spin ? 1.62 : 1.62 - SEAT_DROP)) {
            return Result.NO_ROOM;
        }

        Session session = new Session(player, kind);
        Location rest = new Location(world, seat.getX(), spin ? seat.getY() : seat.getY() - SEAT_DROP, seat.getZ(),
                yaw != null ? yaw : feet.getYaw(), feet.getPitch());
        if (rest.distanceSquared(feet) > 0.0004 || yaw != null) {
            teleportSelf(player, rest);
        }
        session.rest = rest;
        session.center = seat.toVector().add(new Vector(0.0, 0.5, 0.0));
        session.support = key;
        session.supportData = normalized(support.getBlockData());
        session.vehicleId = Packets.newEntityId();
        session.vehicleUuid = UUID.randomUUID();
        session.vehicleAt = new Location(world, seat.getX(), spin ? seat.getY() + SEAT_DROP : seat.getY(), seat.getZ(),
                rest.getYaw(), 0.0f);
        register(session);
        syncViewers(session);
        return Result.OK;
    }

    // ------------------------------------------------------------------ couché

    Result startLay(Player player, Block floor, Location surface) {
        return logged(player, Kind.LAY, attemptLay(player, floor, surface));
    }

    private Result attemptLay(Player player, Block floor, Location surface) {
        Result refused = precheck(player, Kind.LAY, surface);
        if (refused != null) {
            return refused;
        }
        BlockKey key = BlockKey.of(floor);
        if (byBlock.containsKey(key)) {
            return Result.OCCUPIED;
        }
        World world = surface.getWorld();
        Location feet = player.getLocation();
        BlockFace direction = directionOf(feet.getYaw());
        boolean alongZ = direction == BlockFace.NORTH || direction == BlockFace.SOUTH;
        BoundingBox body = new BoundingBox(surface.getX() - (alongZ ? 0.3 : 0.9), surface.getY() + 0.05,
                surface.getZ() - (alongZ ? 0.9 : 0.3), surface.getX() + (alongZ ? 0.3 : 0.9), surface.getY() + 0.65,
                surface.getZ() + (alongZ ? 0.9 : 0.3));
        if (Space.collides(world, body)) {
            return Result.NO_ROOM;
        }

        Session session = new Session(player, Kind.LAY);
        // Le moteur de rendu vanilla dessine le corps couché de 1,52 bloc côté pieds à 0,28 côté tête de la position
        // de l'entité : pour que le corps soit centré sur la place, l'entité se tient 0,6 bloc côté tête (derrière le regard).
        Location rest = new Location(world, surface.getX() - direction.getModX() * HEAD_SHIFT, surface.getY() + 0.1125,
                surface.getZ() - direction.getModZ() * HEAD_SHIFT, feet.getYaw(), feet.getPitch());
        if (rest.distanceSquared(feet) > 0.0004) {
            teleportSelf(player, rest);
        }
        session.rest = rest;
        session.center = surface.toVector().add(new Vector(0.0, 0.5, 0.0));
        session.support = key;
        session.supportData = normalized(floor.getBlockData());
        // Le lit fantôme qui donne son sens à la pose est enterré en bas du monde, hors de vue
        session.bed = new Location(world, surface.getX(), world.getMinHeight(), surface.getZ());
        session.bedReal = session.bed.getBlock().getBlockData();
        Bed bed = (Bed) Material.WHITE_BED.createBlockData();
        bed.setPart(Bed.Part.HEAD);
        bed.setFacing(direction.getOppositeFace());
        session.bedFake = bed;
        session.sleepingIgnored = player.isSleepingIgnored();
        player.setSleepingIgnored(true);
        hook.track(player.getUniqueId(), true);
        register(session);
        syncViewers(session);
        return Result.OK;
    }

    // ------------------------------------------------------------------ ramper et bellyflop

    Result startCrawl(Player player, Kind kind) {
        return logged(player, kind, attemptCrawl(player, kind));
    }

    private Result attemptCrawl(Player player, Kind kind) {
        Location feet = player.getLocation();
        Result refused = precheck(player, kind, feet);
        if (refused != null) {
            return refused;
        }
        BoundingBox swim = new BoundingBox(feet.getX() - 0.3, feet.getY() + 0.01, feet.getZ() - 0.3, feet.getX() + 0.3,
                feet.getY() + 0.6, feet.getZ() + 0.3);
        if (Space.collides(feet.getWorld(), swim)) {
            return Result.NO_ROOM;
        }
        Session session = new Session(player, kind);
        session.rest = feet;
        register(session);
        player.setSwimming(true);
        if (kind == Kind.BELLYFLOP) {
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null && speed.getModifier(freezeKey) == null) {
                speed.addModifier(new AttributeModifier(freezeKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        if (crawlHandler == null) {
            crawlHandler = new CrawlHandler();
            Bukkit.getPluginManager().registerEvents(crawlHandler, plugin);
        }
        crawlers++;
        updateCeiling(session);
        return Result.OK;
    }

    /**
     * Le client se baisse tout seul sous un plafond : on lui en fabrique un juste au-dessus de la tête
     * (barrière, ou dalle haute si le joueur est sur un demi-bloc), visible de lui seul.
     */
    private void updateCeiling(Session session) {
        Player player = session.player;
        Location at = player.getLocation();
        World world = at.getWorld();
        Block cell = world.getBlockAt(at.getBlockX(), (int) Math.floor(at.getY()) + 1, at.getBlockZ());
        Location want = null;
        BlockData fake = null;
        if (cell.isPassable() && !cell.isLiquid()) {
            want = cell.getLocation();
            if (at.getY() - Math.floor(at.getY()) < 0.1) {
                fake = Material.BARRIER.createBlockData();
            } else {
                Slab slab = (Slab) Material.STONE_SLAB.createBlockData();
                slab.setType(Slab.Type.TOP);
                fake = slab;
            }
        }
        if (want == null ? session.ceiling == null : (session.ceiling != null && want.equals(session.ceiling)
                && fake.equals(session.ceilingFake))) {
            return;
        }
        if (session.ceiling != null) {
            player.sendBlockChange(session.ceiling, session.ceiling.getBlock().getBlockData());
        }
        session.ceiling = want;
        session.ceilingFake = fake;
        if (want != null) {
            player.sendBlockChange(want, fake);
        }
    }

    /** Écouteurs du ramper, enregistrés seulement tant que quelqu'un rampe. */
    final class CrawlHandler implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Session session = byPlayer.get(event.getPlayer().getUniqueId());
            if (session != null && session.kind.isCrawl() && event.hasChangedPosition()) {
                updateCeiling(session);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onSwim(EntityToggleSwimEvent event) {
            if (event.getEntity() instanceof Player player) {
                Session session = byPlayer.get(player.getUniqueId());
                if (session != null && session.kind.isCrawl()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ sur la tête d'un joueur

    /** Inverse le réglage « on peut s'asseoir sur moi » et renvoie le nouveau (vrai = autorisé). */
    boolean togglePlayerSit(Player player) {
        boolean allowed = player.getPersistentDataContainer().has(offKey, PersistentDataType.BYTE);
        if (allowed) {
            player.getPersistentDataContainer().remove(offKey);
        } else {
            player.getPersistentDataContainer().set(offKey, PersistentDataType.BYTE, (byte) 1);
        }
        return allowed;
    }

    Result startPlayerSit(Player rider, Player target) {
        return logged(rider, Kind.PLAYERSIT, attemptPlayerSit(rider, target));
    }

    private Result attemptPlayerSit(Player rider, Player target) {
        Settings settings = plugin.settings();
        if (target.equals(rider) || target.getWorld() != rider.getWorld()) {
            return Result.UNAVAILABLE;
        }
        Result refused = precheck(rider, Kind.PLAYERSIT, target.getLocation());
        if (refused != null) {
            return refused;
        }
        if (target.getPersistentDataContainer().has(offKey, PersistentDataType.BYTE)) {
            return Result.TARGET;
        }
        Session targetSession = of(target);
        if (targetSession != null && targetSession.kind != Kind.PLAYERSIT) {
            return Result.UNAVAILABLE;
        }
        if (rider.getLocation().distanceSquared(target.getLocation()) > settings.maxDistance * settings.maxDistance
                || !Space.visible(rider.getWorld(), rider.getEyeLocation().toVector(),
                target.getEyeLocation().toVector(), null)) {
            return Result.BLOCKED;
        }
        Entity top = target;
        int riders = 0;
        while (!top.getPassengers().isEmpty()) {
            Entity next = top.getPassengers().get(0);
            if (!(next instanceof Player)) {
                return Result.UNAVAILABLE;
            }
            top = next;
            riders++;
        }
        if (riders >= settings.maxStack) {
            return Result.STACK;
        }
        Session session = new Session(rider, Kind.PLAYERSIT);
        session.carrier = target.getUniqueId();
        session.rest = rider.getLocation();
        if (!top.addPassenger(rider)) {
            return Result.FAILED;
        }
        register(session);
        return Result.OK;
    }

    // ------------------------------------------------------------------ fin de pose

    void end(Player player, boolean relocate) {
        Session session = byPlayer.get(player.getUniqueId());
        if (session != null) {
            end(session, relocate);
        }
    }

    private void end(Session session, boolean relocate) {
        if (byPlayer.get(session.player.getUniqueId()) != session) {
            return;
        }
        if (plugin.settings().debug) {
            StackTraceElement[] trace = new Throwable().getStackTrace();
            plugin.getLogger().info(session.player.getName() + " -> fin de " + session.kind.id + " (replacé : " + relocate
                    + ") par " + trace[1].getMethodName() + " <- " + (trace.length > 2 ? trace[2].getMethodName() : "?"));
        }
        unregister(session);
        Player player = session.player;
        switch (session.kind) {
            case SIT, SPIN, LAY -> {
                for (UUID id : session.viewers) {
                    Player viewer = Bukkit.getPlayer(id);
                    if (viewer != null) {
                        stopFor(session, viewer);
                    }
                }
                if (session.kind == Kind.LAY) {
                    player.setSleepingIgnored(session.sleepingIgnored);
                    hook.track(player.getUniqueId(), false);
                }
            }
            case CRAWL, BELLYFLOP -> {
                player.setSwimming(false);
                AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null && speed.getModifier(freezeKey) != null) {
                    speed.removeModifier(freezeKey);
                }
                if (session.ceiling != null) {
                    player.sendBlockChange(session.ceiling, session.ceiling.getBlock().getBlockData());
                }
                if (--crawlers <= 0 && crawlHandler != null) {
                    HandlerList.unregisterAll(crawlHandler);
                    crawlHandler = null;
                    crawlers = 0;
                }
            }
            case PLAYERSIT -> player.leaveVehicle();
        }
        if (relocate && player.isOnline() && !player.isDead()) {
            place(player, session);
        }
    }

    /** Une téléportation venue d'ailleurs : la pose s'arrête avant, le joueur n'est pas ramené. */
    void teleported(Player player, Location from, Location to) {
        if (selfTeleport.contains(player.getUniqueId())) {
            return;
        }
        Session session = of(player);
        if (session != null && (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.01)) {
            end(session, false);
        }
    }

    /** Le joueur vient de quitter sa monture (accroupi, mort du porteur...). */
    void dismounted(Player rider) {
        Session session = of(rider);
        if (session != null && session.kind == Kind.PLAYERSIT) {
            boolean moved = selfTeleport.contains(rider.getUniqueId());
            end(session, !moved);
        }
    }

    void supportBroken(Block block) {
        UUID id = byBlock.get(BlockKey.of(block));
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player != null) {
            end(player, true);
        }
    }

    /** Fait descendre toute personne assise sur ce joueur. */
    void ejectRiders(Player carrier) {
        for (Entity passenger : new ArrayList<>(carrier.getPassengers())) {
            if (passenger instanceof Player rider) {
                Session session = of(rider);
                if (session != null && session.kind == Kind.PLAYERSIT) {
                    end(session, true);
                }
            }
        }
    }

    void forget(Player player) {
        lastStart.remove(player.getUniqueId());
        selfTeleport.remove(player.getUniqueId());
    }

    void releaseAll() {
        for (Session session : new ArrayList<>(byPlayer.values())) {
            end(session, session.player.isOnline());
        }
    }

    void startJanitor() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 5L, 5L);
    }

    /** Contrôle régulier : joueur parti, bloc modifié, joueur qui bouge, spectateurs qui arrivent. */
    private void check() {
        for (Session session : new ArrayList<>(byPlayer.values())) {
            Player player = session.player;
            if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                    || !player.getWorld().getUID().equals(session.origin.getWorld().getUID())) {
                end(session, false);
                continue;
            }
            session.age += 5;
            switch (session.kind) {
                case SIT, SPIN, LAY -> {
                    // Un joueur posé ne bouge plus : une petite dérive est recalée, un vrai déplacement met fin à la pose.
                    double drift = player.getLocation().distanceSquared(session.rest);
                    if (drift > 2.25) {
                        end(session, true);
                        continue;
                    }
                    if (drift > 0.0025) {
                        teleportSelf(player, session.rest);
                    }
                    Block block = session.support.block();
                    if (block == null || !normalized(block.getBlockData()).equals(session.supportData)) {
                        end(session, true);
                        continue;
                    }
                    syncViewers(session);
                    if (session.age % 20 == 0) {
                        refresh(session);
                    }
                    if (session.kind == Kind.LAY && session.age % 100 == 0) {
                        player.setStatistic(Statistic.TIME_SINCE_REST, 0);
                    }
                }
                case CRAWL, BELLYFLOP -> {
                    if (player.isInWater() || player.isFlying() || player.isGliding()) {
                        end(session, false);
                    } else if (session.kind == Kind.BELLYFLOP && player.getLocation().distanceSquared(session.rest) > 0.25) {
                        // couché sur le ventre, on ne marche plus : un client qui avance quand même est ramené
                        teleportSelf(player, session.rest);
                    }
                }
                case PLAYERSIT -> {
                    Player carrier = Bukkit.getPlayer(session.carrier);
                    if (carrier == null || !player.isInsideVehicle()) {
                        end(session, true);
                    } else if (session.age % 10 == 0 && !player.hasPermission("shsit.bypass")
                            && ((regions != null && !regions.allows(player, player.getLocation(), Kind.PLAYERSIT))
                            || (islands != null && !islands.allows(player, player.getLocation(), plugin.settings())))) {
                        end(session, true);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ spectateurs

    private void syncViewers(Session session) {
        Player player = session.player;
        Set<UUID> now = new HashSet<>();
        now.add(player.getUniqueId());
        for (Player viewer : player.getTrackedBy()) {
            if (viewer.canSee(player)) {
                now.add(viewer.getUniqueId());
            }
        }
        for (UUID id : now) {
            if (!session.viewers.contains(id)) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) {
                    startFor(session, viewer);
                }
            }
        }
        for (UUID id : new ArrayList<>(session.viewers)) {
            if (!now.contains(id)) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) {
                    stopFor(session, viewer);
                }
            }
        }
        session.viewers.clear();
        session.viewers.addAll(now);
    }

    private void startFor(Session session, Player viewer) {
        Player player = session.player;
        switch (session.kind) {
            case SIT, SPIN -> {
                Packets.spawnSeat(viewer, session.vehicleId, session.vehicleUuid, session.vehicleAt);
                Packets.mount(viewer, session.vehicleId, player.getEntityId());
                if (session.kind == Kind.SPIN) {
                    Packets.spin(viewer, player);
                }
            }
            case LAY -> {
                // Jamais au joueur couché : son client irait se poser sur le lit (au fond du monde) et s'assombrirait
                if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                    viewer.sendBlockChange(session.bed, session.bedFake);
                    Packets.lay(viewer, player, session.bed, session.rest);
                }
            }
            default -> {
            }
        }
    }

    private void stopFor(Session session, Player viewer) {
        Player player = session.player;
        switch (session.kind) {
            case SIT, SPIN -> {
                Packets.mount(viewer, session.vehicleId);
                Packets.destroy(viewer, session.vehicleId);
                if (session.kind == Kind.SPIN) {
                    Packets.restore(viewer, player);
                }
            }
            case LAY -> {
                if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                    viewer.sendBlockChange(session.bed, session.bedReal);
                    Packets.restore(viewer, player);
                }
            }
            default -> {
            }
        }
    }

    /** Le serveur renvoie parfois les métadonnées du joueur en entier : on remet la pose par-dessus. */
    private void refresh(Session session) {
        for (UUID id : session.viewers) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) {
                continue;
            }
            if (session.kind == Kind.SPIN) {
                Packets.spin(viewer, session.player);
            } else if (session.kind == Kind.LAY && !id.equals(session.player.getUniqueId())) {
                // la pose seule : renvoyer la position de sommeil ferait sauter l'entité au fond du monde puis la ramènerait
                Packets.layPose(viewer, session.player);
            }
        }
    }

    // ------------------------------------------------------------------ registre et replacement

    private void register(Session session) {
        byPlayer.put(session.player.getUniqueId(), session);
        if (session.support != null) {
            byBlock.put(session.support, session.player.getUniqueId());
        }
        lastStart.put(session.player.getUniqueId(), System.currentTimeMillis());
    }

    private void unregister(Session session) {
        byPlayer.remove(session.player.getUniqueId());
        if (session.support != null) {
            byBlock.remove(session.support);
        }
    }

    private void teleportSelf(Player player, Location to) {
        UUID id = player.getUniqueId();
        selfTeleport.add(id);
        try {
            player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            selfTeleport.remove(id);
        }
    }

    /** L'eau qui coule sur une dalle ne doit pas relever le joueur : on ignore waterlogged. */
    private static BlockData normalized(BlockData data) {
        BlockData copy = data.clone();
        if (copy instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(false);
        }
        return copy;
    }

    private static BlockFace directionOf(float yaw) {
        float y = ((yaw + 180.0f) % 360.0f + 360.0f) % 360.0f - 180.0f;
        if (y >= 135.0f || y < -135.0f) {
            return BlockFace.NORTH;
        }
        if (y < -45.0f) {
            return BlockFace.EAST;
        }
        return y < 45.0f ? BlockFace.SOUTH : BlockFace.WEST;
    }

    /**
     * Replace le joueur après la pose. Le point où il se trouve est gardé s'il est libre ; sinon on
     * cherche un sol libre, visible depuis le siège (jamais de l'autre côté d'un mur), en commençant
     * par l'endroit où il se tenait avant de s'asseoir.
     */
    private void place(Player player, Session session) {
        if (session.kind.isCrawl() || !player.isOnline() || player.isDead()) {
            return;
        }
        World world = player.getWorld();
        Location origin = session.origin;
        Vector center = session.center;
        if (session.kind == Kind.PLAYERSIT) {
            Player carrier = session.carrier == null ? null : Bukkit.getPlayer(session.carrier);
            origin = carrier != null ? carrier.getLocation() : player.getLocation();
            center = origin.toVector().add(new Vector(0.0, 0.5, 0.0));
        }
        if (!world.getUID().equals(origin.getWorld().getUID())) {
            return;
        }
        if (usable(world, center, player.getLocation())) {
            return;
        }
        if (usable(world, center, origin)) {
            teleportSelf(player, origin);
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
        Location reference = origin;
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distanceSquared(reference)));
        if (!candidates.isEmpty()) {
            teleportSelf(player, candidates.get(0));
        }
    }

    private boolean usable(World world, Vector center, Location feet) {
        return feet.distanceSquared(center.toLocation(world)) <= 9.0 && Space.standable(feet)
                && Space.visible(world, center, feet.toVector().add(new Vector(0.0, 0.9, 0.0)), null);
    }
}
