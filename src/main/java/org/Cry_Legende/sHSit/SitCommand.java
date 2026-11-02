package org.Cry_Legende.sHSit;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /sit (s'asseoir sur place, ou se relever) et /shsit reload. */
final class SitCommand implements CommandExecutor, TabCompleter {

    private final SHSit plugin;
    private final SeatManager seats;
    private final SitListener listener;

    SitCommand(SHSit plugin, SeatManager seats, SitListener listener) {
        this.plugin = plugin;
        this.seats = seats;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("shsit")) {
            return admin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (!player.hasPermission("shsit.command")) {
            send(player, "no-permission");
            return true;
        }
        if (seats.isSitting(player)) {
            seats.standUp(player);
            return true;
        }

        // On s'assoit là où l'on se tient : le sol doit être juste sous les pieds.
        Location feet = player.getLocation();
        Block floor = feet.getWorld().getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() - 0.05), feet.getBlockZ());
        double top = Space.topOf(floor);
        if (Double.isNaN(top) || Math.abs(floor.getY() + top - feet.getY()) > 0.1) {
            send(player, "not-on-ground");
            return true;
        }
        Location seat = new Location(feet.getWorld(), feet.getX(), floor.getY() + top, feet.getZ());
        listener.warn(player, seats.sit(player, floor, seat, null, null));
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shsit.admin")) {
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reload();
            send(sender, "reloaded");
        } else {
            send(sender, "usage");
        }
        return true;
    }

    private void send(CommandSender sender, String key) {
        if (plugin.settings().message(key) != null) {
            sender.sendMessage(plugin.settings().message(key));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("shsit") && args.length == 1 && sender.hasPermission("shsit.admin")) {
            return List.of("reload");
        }
        return List.of();
    }
}
