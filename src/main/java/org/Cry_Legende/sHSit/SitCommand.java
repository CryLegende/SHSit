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

/** /sit /lay /spin /bellyflop /crawl (chacun s'arrête en le retapant) et /shsit. */
final class SitCommand implements CommandExecutor, TabCompleter {

    private final SHSit plugin;
    private final Sessions sessions;
    private final SitListener listener;

    SitCommand(SHSit plugin, Sessions sessions, SitListener listener) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("shsit")) {
            return admin(sender, args);
        }
        Kind kind = Kind.byId(name);
        if (kind == null) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (kind == Kind.SIT && args.length == 1 && args[0].equalsIgnoreCase("playertoggle")) {
            send(player, sessions.togglePlayerSit(player) ? "playertoggle-on" : "playertoggle-off");
            return true;
        }
        if (!player.hasPermission("shsit.command." + kind.id)) {
            send(player, "no-permission");
            return true;
        }
        Sessions.Session current = sessions.of(player);
        if (current != null) {
            sessions.end(player, true);
            if (current.kind == kind) {
                return true;
            }
        }

        // La pose se fait là où l'on se tient : le sol doit être juste sous les pieds.
        Location feet = player.getLocation();
        Block floor = feet.getWorld().getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() - 0.05), feet.getBlockZ());
        double top = Space.surfaceAt(floor, feet.getY());
        if (Double.isNaN(top)) {
            send(player, "not-on-ground");
            return true;
        }
        Location seat = new Location(feet.getWorld(), feet.getX(), floor.getY() + top, feet.getZ(), feet.getYaw(), feet.getPitch());
        Sessions.Result result = switch (kind) {
            case SIT, SPIN -> sessions.startSeat(player, kind, floor, seat, null, null);
            case LAY -> sessions.startLay(player, floor, seat);
            default -> sessions.startCrawl(player, kind);
        };
        listener.warn(player, result);
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
        if (args.length != 1) {
            return List.of();
        }
        if (command.getName().equalsIgnoreCase("shsit") && sender.hasPermission("shsit.admin")) {
            return List.of("reload");
        }
        if (command.getName().equalsIgnoreCase("sit")) {
            return List.of("playertoggle");
        }
        return List.of();
    }
}
