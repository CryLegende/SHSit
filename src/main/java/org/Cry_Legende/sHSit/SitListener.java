package org.Cry_Legende.sHSit;

import io.papermc.paper.event.entity.EntityBreakByEntityEvent;
import io.papermc.paper.event.entity.EntityBreakEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Événements : s'asseoir d'un clic, et tout ce qui protège le coussin (casse, vol, mort du siège). */
final class SitListener implements Listener {

    /** Un coussin du plugin vient d'être détruit par autre chose que le plugin : son objet ne doit pas tomber. */
    private record DropGuard(UUID world, Vector where, int tick) {
    }

    private final SHSit plugin;
    private final SeatManager seats;
    private final Map<UUID, Long> lastWarning = new HashMap<>();
    private DropGuard dropGuard;

    SitListener(SHSit plugin, SeatManager seats) {
        this.plugin = plugin;
        this.seats = seats;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Settings settings = plugin.settings();
        Block block = event.getClickedBlock();
        if (block == null || !settings.seatBlocks.contains(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (settings.debug) {
            plugin.getLogger().info(player.getName() + " -> clic sur " + block.getType() + " " + block.getX() + " "
                    + block.getY() + " " + block.getZ() + " (face " + event.getBlockFace() + ")");
        }
        if (player.isSneaking() || !player.hasPermission("shsit.sit")) {
            skipped(player, player.isSneaking() ? "accroupi" : "permission shsit.sit manquante");
            return;
        }
        if (settings.emptyHand && (!player.getInventory().getItemInMainHand().isEmpty()
                || !player.getInventory().getItemInOffHand().isEmpty())) {
            skipped(player, "main non vide");
            return;
        }
        double surface = Space.seatSurface(block);
        if (Double.isNaN(surface)) {
            skipped(player, "moitié haute ou bloc sans surface");
            return;
        }
        Location seat = block.getLocation().add(0.5, surface, 0.5);
        Float yaw = settings.faceOpenSide && block.getBlockData() instanceof Stairs stairs
                ? yawOf(stairs.getFacing().getOppositeFace()) : null;
        Vector click = event.getInteractionPoint() == null ? null : event.getInteractionPoint().toVector();

        SeatManager.Result result = seats.sit(player, block, seat, click, yaw);
        if (result == SeatManager.Result.OK) {
            event.setCancelled(true);
        } else {
            warn(player, result);
        }
    }

    private void skipped(Player player, String reason) {
        if (plugin.settings().debug) {
            plugin.getLogger().info(player.getName() + " -> ignoré : " + reason);
        }
    }

    /** Envoie le message du refus, au plus une fois toutes les 1,5 s par joueur. */
    void warn(Player player, SeatManager.Result result) {
        if (result.message == null || plugin.settings().message(result.message) == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last != null && now - last < 1500L) {
            return;
        }
        lastWarning.put(player.getUniqueId(), now);
        player.sendMessage(plugin.settings().message(result.message));
    }

    private static float yawOf(BlockFace face) {
        return switch (face) {
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }

    // ------------------------------------------------------------------ départ du siège

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && seats.isSeatCushion(event.getDismounted())) {
            seats.dismounted(player, event.getDismounted());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        seats.teleportStarted(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        try {
            seats.standUp(event.getPlayer());
        } finally {
            seats.forget(event.getPlayer());
            lastWarning.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        seats.supportBroken(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (plugin.settings().standUpOnDamage && event.getFinalDamage() > 0.0
                && event.getEntity() instanceof Player player && seats.isSitting(player)) {
            Bukkit.getScheduler().runTask(plugin, () -> seats.standUp(player));
        }
    }

    // ------------------------------------------------------------------ protection du coussin

    /** Seul le plugin monte un joueur sur un de ses coussins. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (seats.isSeatCushion(event.getMount()) && !seats.isPendingMount(event.getMount())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractCushion(PlayerInteractEntityEvent event) {
        if (seats.isSeatCushion(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurtCushion(EntityDamageEvent event) {
        if (seats.isSeatCushion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakCushion(EntityBreakEvent event) {
        if (seats.isSeatCushion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakCushionByEntity(EntityBreakByEntityEvent event) {
        if (seats.isSeatCushion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Filet de sécurité : si un coussin du plugin est détruit par autre chose que le plugin (la
     * foudre le tue sans passer par un événement annulable), l'objet qu'il lâche est supprimé.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCushionRemoved(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (event.getCause() == EntityRemoveEvent.Cause.PLUGIN || event.getCause() == EntityRemoveEvent.Cause.UNLOAD
                || event.getCause() == EntityRemoveEvent.Cause.DESPAWN || !seats.isSeatCushion(entity)) {
            return;
        }
        dropGuard = new DropGuard(entity.getWorld().getUID(), entity.getLocation().toVector(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        DropGuard guard = dropGuard;
        if (guard == null || Bukkit.getCurrentTick() - guard.tick() > 2) {
            return;
        }
        Location where = event.getLocation();
        if (where.getWorld().getUID().equals(guard.world())
                && where.toVector().distanceSquared(guard.where()) < 4.0
                && event.getEntity().getItemStack().getType().name().endsWith("_CUSHION")) {
            event.setCancelled(true);
        }
    }

    /** Un coussin du plugin retrouvé dans un chunk chargé n'a plus de joueur : on le retire. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        seats.sweep(event.getEntities().stream().map(entity -> (Entity) entity).toList());
    }
}
