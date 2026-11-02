package org.Cry_Legende.sHSit;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Tests de géométrie : surface d'un siège, place disponible, vue dégagée. */
final class Space {

    private static final double WIDTH = 0.6;
    private static final double HEIGHT = 1.8;
    /** Marge qui évite de compter un simple contact de face comme une collision. */
    private static final double EPSILON = 0.001;

    /**
     * Hauteur des yeux d'un joueur assis, au-dessus de la surface sur laquelle repose le coussin
     * (mesuré sur 26.3 : les pieds du joueur sont 0,35 sous l'origine du coussin, les yeux à 1,62 des pieds).
     */
    static final double EYE_ABOVE_SEAT = 1.27;

    private Space() {
    }

    /** Hauteur du dessus d'un bloc (0 à 1), ou NaN s'il n'a pas de collision. */
    static double topOf(Block block) {
        if (block.isPassable()) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            top = Double.isNaN(top) ? box.getMaxY() : Math.max(top, box.getMaxY());
        }
        return top;
    }

    /**
     * Hauteur de la surface où se pose le coussin quand on s'assoit sur ce bloc, ou NaN si le bloc
     * ne convient pas : seule la moitié basse des escaliers et des dalles sert de siège.
     */
    static double seatSurface(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Stairs stairs) {
            return stairs.getHalf() == Bisected.Half.BOTTOM ? 0.5 : Double.NaN;
        }
        if (data instanceof Slab slab) {
            return slab.getType() == Slab.Type.BOTTOM ? 0.5 : Double.NaN;
        }
        double top = topOf(block);
        return top > 0.0 && top <= 1.0 ? top : Double.NaN;
    }

    /** Vrai si la boîte touche un bloc plein ou partiel. */
    static boolean collides(World world, BoundingBox box) {
        int x0 = (int) Math.floor(box.getMinX());
        int x1 = (int) Math.floor(box.getMaxX());
        int y0 = (int) Math.floor(box.getMinY());
        int y1 = (int) Math.floor(box.getMaxY());
        int z0 = (int) Math.floor(box.getMinZ());
        int z1 = (int) Math.floor(box.getMaxZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isPassable()) {
                        continue;
                    }
                    BoundingBox relative = box.clone().shift(-x, -y, -z);
                    for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
                        if (part.overlaps(relative)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Vrai si un joueur debout aux pieds de ce point ne touche aucun bloc et repose sur le sol. */
    static boolean standable(Location feet) {
        World world = feet.getWorld();
        double half = WIDTH / 2.0 - EPSILON;
        BoundingBox body = new BoundingBox(feet.getX() - half, feet.getY() + EPSILON, feet.getZ() - half,
                feet.getX() + half, feet.getY() + HEIGHT - EPSILON, feet.getZ() + half);
        BoundingBox ground = new BoundingBox(feet.getX() - half, feet.getY() - 0.3, feet.getZ() - half,
                feet.getX() + half, feet.getY() - EPSILON, feet.getZ() + half);
        return !collides(world, body) && collides(world, ground);
    }

    /** Vrai si la tête d'un joueur assis sur cette surface ne touche aucun bloc ni liquide. */
    static boolean roomAbove(Location seat) {
        World world = seat.getWorld();
        double eyeY = seat.getY() + EYE_ABOVE_SEAT;
        double half = WIDTH / 2.0 - EPSILON;
        BoundingBox head = new BoundingBox(seat.getX() - half, eyeY - 0.15, seat.getZ() - half,
                seat.getX() + half, eyeY + 0.2, seat.getZ() + half);
        if (collides(world, head)) {
            return false;
        }
        return !world.getBlockAt(seat.getBlockX(), (int) Math.floor(eyeY), seat.getBlockZ()).isLiquid()
                && !seat.getBlock().isLiquid();
    }

    /**
     * Vrai si rien ne se trouve entre les deux points, hors le bloc ignoré (le siège lui-même).
     * Les blocs sans collision (herbe, torches, eau) ne comptent pas.
     */
    static boolean visible(World world, Vector from, Vector to, Block ignored) {
        Vector direction = to.clone().subtract(from);
        double length = direction.length();
        if (length < 1.0e-4) {
            return true;
        }
        RayTraceResult hit = world.rayTraceBlocks(from.toLocation(world), direction.normalize(), length,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) {
            return true;
        }
        return ignored != null && hit.getHitBlock().equals(ignored);
    }
}
