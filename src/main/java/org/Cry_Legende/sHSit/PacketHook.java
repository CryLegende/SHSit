package org.Cry_Legende.sHSit;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un joueur couché (faux sommeil) se relève avec la touche qui quitte le lit : le serveur n'en sait
 * rien, c'est donc ce paquet qui le relève. Seuls les joueurs couchés sont regardés.
 */
final class PacketHook extends PacketListenerAbstract {

    private final SHSit plugin;
    private final Set<UUID> laying = ConcurrentHashMap.newKeySet();

    PacketHook(SHSit plugin) {
        this.plugin = plugin;
    }

    void track(UUID player, boolean lying) {
        if (lying) {
            laying.add(player);
        } else {
            laying.remove(player);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (laying.isEmpty() || event.getPacketType() != PacketType.Play.Client.ENTITY_ACTION || event.getUser() == null) {
            return;
        }
        UUID id = event.getUser().getUUID();
        if (id == null || !laying.contains(id)) {
            return;
        }
        WrapperPlayClientEntityAction.Action action = new WrapperPlayClientEntityAction(event).getAction();
        if (plugin.settings().debug) {
            plugin.getLogger().info(id + " -> paquet d'action " + action);
        }
        if (action == WrapperPlayClientEntityAction.Action.LEAVE_BED) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    plugin.sessions().end(player, true);
                }
            });
        }
    }
}
