package org.Cry_Legende.sHSit;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /sit /lay /spin /bellyflop /crawl (chacun s'arrête en le retapant) et /shsit (reload, list, stand). */
final class SitCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ADMIN = List.of("reload", "list", "stand");

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
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("shsit")) {
            return admin(sender, args);
        }
        Kind kind = Kind.byId(name);
        if (kind == null) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "players-only");
            return true;
        }
        if (kind == Kind.SIT && args.length == 1 && args[0].equalsIgnoreCase("playertoggle")) {
            send(player, sessions.togglePlayerSit(player) ? "playertoggle-on" : "playertoggle-off");
            return true;
        }
        if (!player.hasPermission("shsit.use." + kind.id)) {
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
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            send(sender, "usage-admin", "commands", String.join("|", allowed));
            return true;
        }
        switch (sub) {
            case "reload" -> reload(sender);
            case "list" -> list(sender);
            default -> stand(sender, args);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reload();
            send(sender, "reloaded");
        } catch (SHSit.ReloadException e) {
            plugin.getLogger().warning(e.file + " illisible, l'ancienne configuration reste active : " + e.getMessage());
            send(sender, "reload-failed", "file", e.file, "error", e.getMessage());
        }
    }

    private void list(CommandSender sender) {
        List<Sessions.Pose> poses = sessions.poses();
        if (poses.isEmpty()) {
            send(sender, "list-empty");
            return;
        }
        send(sender, "list-header", "count", String.valueOf(poses.size()));
        for (Sessions.Pose pose : poses) {
            send(sender, "list-line", "player", pose.player(), "pose", pose.kind().id, "world", pose.world());
        }
    }

    private void stand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "usage-admin", "commands", String.join("|", allowed(sender)));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "player-not-found", "player", args[1]);
            return;
        }
        if (sessions.of(target) == null) {
            send(sender, "stand-none", "player", target.getName());
            return;
        }
        // Les poses se terminent depuis le thread principal, comme à la mort ou aux dégâts
        Bukkit.getScheduler().runTask(plugin, () -> sessions.end(target, true));
        send(sender, "stand-done", "player", target.getName());
    }

    /** Sous-commandes de /shsit que l'expéditeur peut utiliser. */
    private static List<String> allowed(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        for (String sub : ADMIN) {
            if (sender.hasPermission("shsit.admin." + sub)) {
                allowed.add(sub);
            }
        }
        return allowed;
    }

    private void send(CommandSender sender, String key, String... pairs) {
        Component message = plugin.messages().get(key, pairs);
        if (message != null) {
            sender.sendMessage(message);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("shsit")) {
            if (args.length == 1) {
                StringUtil.copyPartialMatches(args[0], allowed(sender), completions);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("stand") && sender.hasPermission("shsit.admin.stand")) {
                List<String> names = new ArrayList<>();
                for (Sessions.Pose pose : sessions.poses()) {
                    names.add(pose.player());
                }
                StringUtil.copyPartialMatches(args[1], names, completions);
            }
            return completions;
        }
        if (args.length == 1 && command.getName().equalsIgnoreCase("sit")) {
            return List.of("playertoggle");
        }
        return List.of();
    }
}
