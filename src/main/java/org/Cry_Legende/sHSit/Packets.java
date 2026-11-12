package org.Cry_Legende.sHSit;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.HumanoidArm;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.MainHand;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Les seuls paquets que le plugin envoie : un faux véhicule invisible (item_display, sans modèle ni
 * hitbox cliquable) sur lequel le client fait monter le joueur, quelques métadonnées de pose, et la copie
 * couchée que le joueur allongé voit de lui-même. Rien de tout cela n'existe côté serveur : pas d'entité,
 * pas d'événement de monture.
 */
final class Packets {

    // Indices des métadonnées de joueur sur 26.3 : Entity#pose, LivingEntity#flags, LivingEntity#sleepingPos,
    // Avatar#mainHand, Avatar#modelCustomisation (couches du skin)
    private static final int POSE = 6;
    private static final int LIVING_FLAGS = 8;
    private static final int SLEEPING_POS = 14;
    private static final int MAIN_HAND = 15;
    private static final int SKIN_PARTS = 16;
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
     * Pose « endormi » de cette entité, avec un lit (fantôme) en bas du monde qui donne au client le sens du corps.
     * À envoyer AVANT le bloc du lit : à la réception de la position de sommeil, le client vanilla pose l'entité sur
     * son lit (LivingEntity#setPosToBed) si ce lit existe déjà chez lui ; sans lit, l'entité reste où elle est.
     */
    static void sleep(Player viewer, int entityId, Location bed) {
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, EntityPose.SLEEPING),
                new EntityData<>(SLEEPING_POS, EntityDataTypes.OPTIONAL_BLOCK_POSITION,
                        Optional.of(new Vector3i(bed.getBlockX(), bed.getBlockY(), bed.getBlockZ()))))));
    }

    /** Remet seulement la pose « endormi » (la position de sommeil, elle, ne bouge pas : la renvoyer ferait sauter l'entité). */
    static void layPose(Player viewer, int entityId) {
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(POSE, EntityDataTypes.ENTITY_POSE, EntityPose.SLEEPING))));
    }

    /** Replace l'entité là où elle doit être chez ce spectateur (sans effet si elle y est déjà). */
    static void teleport(Player viewer, int entityId, Location at) {
        send(viewer, new WrapperPlayServerEntityTeleport(entityId, new Vector3d(at.getX(), at.getY(), at.getZ()),
                at.getYaw(), at.getPitch(), true));
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

    /**
     * Copie du joueur (même skin, même équipement, absente de la liste des joueurs), envoyée à lui seul. Elle doit
     * vivre au moins un tick chez le client avant de recevoir la pose de sommeil : au premier tick d'une entité qui
     * dort, le client la pose sur son lit, ou la réveille si le lit n'existe pas.
     */
    static void spawnClone(Player viewer, int id, UUID uuid, Location at) {
        List<TextureProperty> textures = new ArrayList<>();
        for (ProfileProperty property : viewer.getPlayerProfile().getProperties()) {
            textures.add(new TextureProperty(property.getName(), property.getValue(), property.getSignature()));
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                new UserProfile(uuid, viewer.getName(), textures));
        info.setListed(false);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED), info));
        send(viewer, new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.PLAYER,
                new Vector3d(at.getX(), at.getY(), at.getZ()), at.getPitch(), at.getYaw(), at.getYaw(), 0,
                Optional.of(new Vector3d(0.0, 0.0, 0.0))));
        byte skin = (byte) viewer.getClientOption(ClientOption.SKIN_PARTS).getRaw();
        send(viewer, new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(SKIN_PARTS, EntityDataTypes.BYTE, skin),
                new EntityData<>(MAIN_HAND, EntityDataTypes.HUMANOID_ARM,
                        viewer.getMainHand() == MainHand.LEFT ? HumanoidArm.LEFT : HumanoidArm.RIGHT))));
        cloneEquipment(viewer, id);
    }

    /** L'équipement du joueur, porté par sa copie. */
    static void cloneEquipment(Player viewer, int id) {
        EntityEquipment equipment = viewer.getEquipment();
        send(viewer, new WrapperPlayServerEntityEquipment(id, List.of(
                new Equipment(EquipmentSlot.MAIN_HAND, SpigotConversionUtil.fromBukkitItemStack(equipment.getItemInMainHand())),
                new Equipment(EquipmentSlot.OFF_HAND, SpigotConversionUtil.fromBukkitItemStack(equipment.getItemInOffHand())),
                new Equipment(EquipmentSlot.HELMET, SpigotConversionUtil.fromBukkitItemStack(equipment.getHelmet())),
                new Equipment(EquipmentSlot.CHEST_PLATE, SpigotConversionUtil.fromBukkitItemStack(equipment.getChestplate())),
                new Equipment(EquipmentSlot.LEGGINGS, SpigotConversionUtil.fromBukkitItemStack(equipment.getLeggings())),
                new Equipment(EquipmentSlot.BOOTS, SpigotConversionUtil.fromBukkitItemStack(equipment.getBoots())))));
    }

    /** Retire la copie du joueur de son client. */
    static void removeClone(Player viewer, int id, UUID uuid) {
        destroy(viewer, id);
        send(viewer, new WrapperPlayServerPlayerInfoRemove(uuid));
    }

    /**
     * Place la caméra du joueur sur cette entité, comme un spectateur. Le client ne dessine plus son propre joueur,
     * n'applique plus les touches de déplacement et n'envoie plus sa position tant que la caméra n'est pas lui.
     */
    static void camera(Player viewer, int entityId) {
        send(viewer, new WrapperPlayServerCamera(entityId));
    }
}
