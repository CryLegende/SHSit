package org.Cry_Legende.sHSit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Événements : s'asseoir d'un clic (sur un bloc ou sur un joueur) et tout ce qui met fin à une pose. */
final class SitListener implements Listener {

    private final SHSit plugin;
    private final Sessions sessions;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    SitListener(SHSit plugin, Sessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    /**
     * Clic droit sur un bloc de la liste. L'événement est traité même si un autre plugin l'a annulé
     * (WorldGuard le fait avec « interact: deny »), mais seulement quand la région dit explicitement
     * « sit: allow » : sans cela une région protégée ne pourrait jamais autoriser les bancs.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Sessions.Session own = sessions.of(player);
        if (own != null && (own.kind == Kind.LAY || own.kind == Kind.SPIN)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Settings settings = plugin.settings();
        Block block = event.getClickedBlock();
        if (block == null || !settings.seatBlocks.contains(block.getType())) {
            return;
        }
        if (settings.debug) {
            plugin.getLogger().info(player.getName() + " -> clic sur " + block.getType() + " " + block.getX() + " "
                    + block.getY() + " " + block.getZ());
        }
        if (player.isSneaking() || !player.hasPermission("shsit.sit")) {
            skipped(player, player.isSneaking() ? "accroupi" : "permission shsit.sit manquante");
            return;
        }
        if (settings.emptyHand && handsBusy(player)) {
            skipped(player, "mains non vides");
            return;
        }
        double surface = Space.seatSurface(block);
        if (Double.isNaN(surface)) {
            skipped(player, "moitié haute ou bloc sans surface");
            return;
        }
        Location seat = block.getLocation().add(0.5, surface, 0.5);
        if (event.useInteractedBlock() == Event.Result.DENY
                && (plugin.regions() == null || !plugin.regions().explicitlyAllows(seat, Kind.SIT))) {
            skipped(player, "clic annulé par un autre plugin");
            return;
        }
        Float yaw = settings.faceOpenSide && block.getBlockData() instanceof Stairs stairs
                ? yawOf(stairs.getFacing().getOppositeFace()) : null;
        Vector click = event.getInteractionPoint() == null ? null : event.getInteractionPoint().toVector();

        Sessions.Result result = sessions.startSeat(player, Kind.SIT, block, seat, click, yaw);
        if (result == Sessions.Result.OK) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        } else {
            warn(player, result);
        }
    }

    /** Clic droit sur un joueur, les mains vides : on s'assoit sur sa tête. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickPlayer(PlayerInteractEntityEvent event) {
        // PlayerInteractAtEntityEvent est une sous-classe : les deux événements passent ici, le second est refusé (déjà monté)
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Player target) || target.hasMetadata("NPC")) {
            return;
        }
        Player rider = event.getPlayer();
        if (rider.isSneaking() || !rider.hasPermission("shsit.playersit")
                || (plugin.settings().emptyHand && handsBusy(rider))) {
            skipped(rider, "clic sur " + target.getName() + " ignoré (accroupi " + rider.isSneaking() + ", permission "
                    + rider.hasPermission("shsit.playersit") + ", mains occupées " + handsBusy(rider) + " : "
                    + rider.getInventory().getItemInMainHand().getType() + " / " + rider.getInventory().getItemInOffHand().getType() + ")");
            return;
        }
        // Comme pour les blocs : un clic annulé (WorldGuard « interact: deny ») ne compte que si la région dit « playersit: allow »
        if (event.isCancelled() && (plugin.regions() == null
                || !plugin.regions().explicitlyAllows(target.getLocation(), Kind.PLAYERSIT))) {
            skipped(rider, "clic sur " + target.getName() + " annulé par un autre plugin");
            return;
        }
        Sessions.Result result = sessions.startPlayerSit(rider, target);
        if (result == Sessions.Result.OK) {
            event.setCancelled(true);
        } else {
            warn(rider, result);
        }
    }

    private static boolean handsBusy(Player player) {
        return !player.getInventory().getItemInMainHand().isEmpty() || !player.getInventory().getItemInOffHand().isEmpty();
    }

    private void skipped(Player player, String reason) {
        if (plugin.settings().debug) {
            plugin.getLogger().info(player.getName() + " -> ignoré : " + reason);
        }
    }

    /** Envoie le message du refus, au plus une fois toutes les 1,5 s par joueur. */
    void warn(Player player, Sessions.Result result) {
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

    // ------------------------------------------------------------------ fin de pose

    /** Accroupi : le joueur pose se relève, et ceux qui sont assis sur sa tête descendent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        if (sessions.has(player)) {
            sessions.end(player, true);
        }
        if (plugin.settings().ejectOnSneak && !player.getPassengers().isEmpty()) {
            sessions.ejectRiders(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            sessions.dismounted(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        sessions.teleported(player, event.getFrom(), event.getTo());
        if (!player.getPassengers().isEmpty()) {
            sessions.ejectRiders(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sessions.ejectRiders(player);
        sessions.end(player, false);
        sessions.forget(player);
        lastWarning.remove(player.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        sessions.end(event.getEntity(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        sessions.supportBroken(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (plugin.settings().standUpOnDamage && event.getFinalDamage() > 0.0
                && event.getEntity() instanceof Player player && sessions.has(player)) {
            Bukkit.getScheduler().runTask(plugin, () -> sessions.end(player, true));
        }
    }
}
