package org.Cry_Legende.sHSit;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Les seuls paquets que le plugin envoie : un faux véhicule invisible (item_display, sans modèle ni
 * hitbox cliquable) sur lequel le client fait monter le joueur, et quelques métadonnées de pose.
 * Rien de tout cela n'existe côté serveur : pas d'entité, pas d'événement de monture.
 */
final class Packets {

    // Indices des métadonnées de joueur sur 26.3 : Entity#pose, LivingEntity#flags, LivingEntity#sleepingPos
    private static final int POSE = 6;
    private static final int LIVING_FLAGS = 8;
    private static final int SLEEPING_POS = 14;
    private static final byte SPIN_ATTACK_FLAG = 0x04;

    private Packets() {
    }

    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
        } catch (RuntimeException ignored) {
            // joueur en cours de déconnexion
        }
    }

    /** Faux véhicule posé à cet endroit : ses passagers ont les pieds 0,6 bloc plus bas. */
    static void spawnSeat(Player viewer, int id, UUID uuid, Location at) {
        send(viewer, new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.ITEM_DISPLAY,
                new Vector3d(at.getX(), at.getY(), at.getZ()), 0.0f, at.getYaw(), 0.0f, 0,
                Optional.of(new Vector3d(0.0, 0.0, 0.0))));
    }

    static void mount(Player viewer, int vehicle, int... riders) {
        send(viewer, new WrapperPlayServerSetPassengers(vehicle, riders));
    }

    static void destroy(Player viewer, int... ids) {
        send(viewer, new WrapperPlayServerDestroyEntities(ids));
    }

    /** Pose de rotation « spin » (trident) pour ce joueur, vue par le spectateur et par lui-même. */
    static void spin(Player viewer, Player rider) {
        send(viewer, new WrapperPlayServerEntityMetadata(rider.getEntityId(), List.of(
                new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, EntityPose.SPIN_ATTACK),
                new EntityData<>(LIVING_FLAGS, EntityDataTypes.BYTE, SPIN_ATTACK_FLAG))));
    }

    /**
     * Joueur couché vu par un SPECTATEUR (jamais par le joueur lui-même) : pose « endormi » dont le lit
     * (fantôme) se trouve en bas du monde. Le client vanilla téléporte l'entité sur son lit dès qu'il reçoit
     * la position de sommeil (LivingEntity#setPosToBed) : le paquet de téléportation qui suit la remet là
     * où le serveur la connaît. Envoyée au joueur couché, cette position le ferait tomber au fond du monde
     * (le client envoie sa nouvelle position au serveur) et assombrirait son écran comme dans un vrai lit.
     */
    static void lay(Player viewer, Player lying, Location bed, Location at) {
        send(viewer, new WrapperPlayServerEntityMetadata(lying.getEntityId(), List.of(
                new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, EntityPose.SLEEPING),
                new EntityData<>(SLEEPING_POS, EntityDataTypes.OPTIONAL_BLOCK_POSITION,
                        Optional.of(new Vector3i(bed.getBlockX(), bed.getBlockY(), bed.getBlockZ()))))));
        send(viewer, new WrapperPlayServerEntityTeleport(lying.getEntityId(),
                new Vector3d(at.getX(), at.getY(), at.getZ()), at.getYaw(), at.getPitch(), true));
    }

    /** Remet seulement la pose « endormi » (la position de sommeil, elle, ne bouge pas : la renvoyer ferait sauter l'entité). */
    static void layPose(Player viewer, Player lying) {
        send(viewer, new WrapperPlayServerEntityMetadata(lying.getEntityId(), List.of(
                new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, EntityPose.SLEEPING))));
    }

    /** Remet la pose réelle du joueur (celle que le serveur connaît) chez ce spectateur. */
    static void restore(Player viewer, Player player) {
        EntityPose pose;
        try {
            pose = EntityPose.valueOf(player.getPose().name());
        } catch (IllegalArgumentException e) {
            pose = player.isSneaking() ? EntityPose.CROUCHING : EntityPose.STANDING;
        }
        send(viewer, new WrapperPlayServerEntityMetadata(player.getEntityId(), List.of(
                new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, pose),
                new EntityData<>(LIVING_FLAGS, EntityDataTypes.BYTE, (byte) 0),
                new EntityData<>(SLEEPING_POS, EntityDataTypes.OPTIONAL_BLOCK_POSITION, Optional.empty()))));
    }
}
