package com.example.combatguard.world;

import com.example.combatguard.math.AABB;
import com.example.combatguard.math.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collection;

/** World lookups used by the checks. Server thread only. */
public final class WorldQuery {
    private WorldQuery() {
    }

    public static BoundingBox bukkit(AABB b) {
        return new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    public static AABB aabb(BoundingBox b) {
        return new AABB(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
    }

    public static boolean chunkLoaded(World world, double x, double z) {
        return world.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    /** Block collision boxes only. */
    public static boolean hasBlockCollision(World world, AABB box) {
        int minX = (int) Math.floor(box.minX());
        int minY = (int) Math.floor(box.minY());
        int minZ = (int) Math.floor(box.minZ());
        int maxX = (int) Math.floor(box.maxX() - 1.0E-7);
        int maxY = (int) Math.floor(box.maxY() - 1.0E-7);
        int maxZ = (int) Math.floor(box.maxZ() - 1.0E-7);
        BoundingBox target = bukkit(box);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return true;
                }
                for (int y = minY; y <= maxY; y++) {
                    if (world.getType(x, y, z).isAir()) {
                        continue;
                    }
                    for (BoundingBox shape : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
                        if (shape.clone().shift(x, y, z).overlaps(target)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Entities players can stand on: boats, minecarts, shulkers, happy ghasts. */
    public static boolean hasPlatformEntity(World world, AABB box, Entity except) {
        return !world.getNearbyEntities(bukkit(box), e -> e != except && isPlatform(e)).isEmpty();
    }

    private static boolean isPlatform(Entity entity) {
        return entity instanceof Boat || entity instanceof Minecart || entity instanceof org.bukkit.entity.Shulker
            || "HAPPY_GHAST".equals(entity.getType().name());
    }

    /** Blocks and entities the player can stand on. */
    public static boolean hasCollision(World world, AABB box) {
        return hasBlockCollision(world, box) || hasPlatformEntity(world, box, null);
    }

    /** Slipperiness of the block that decides friction: 0.5 blocks under the feet, like vanilla. */
    public static double slipperiness(World world, Vec3 pos) {
        Material material = world.getType((int) Math.floor(pos.x()), (int) Math.floor(pos.y() - 0.500001), (int) Math.floor(pos.z()));
        return material.getSlipperiness();
    }

    /** Honey halves jump height; vanilla checks the block at the feet first, then the one below. */
    public static double jumpFactor(World world, Vec3 pos) {
        int x = (int) Math.floor(pos.x());
        int z = (int) Math.floor(pos.z());
        Material feet = world.getType(x, (int) Math.floor(pos.y()), z);
        if (feet == Material.HONEY_BLOCK) {
            return 0.5;
        }
        if (feet.isAir() || !feet.isSolid()) {
            Material below = world.getType(x, (int) Math.floor(pos.y() - 0.500001), z);
            if (below == Material.HONEY_BLOCK) {
                return 0.5;
            }
        }
        return 1.0;
    }

    /** A single full 1x1x1 collision box (stairs, slabs and fences are not). */
    public static boolean fullCube(Block block) {
        Collection<BoundingBox> boxes = block.getCollisionShape().getBoundingBoxes();
        if (boxes.size() != 1) {
            return false;
        }
        BoundingBox box = boxes.iterator().next();
        return box.getWidthX() >= 0.999 && box.getHeight() >= 0.999 && box.getWidthZ() >= 0.999;
    }

    /** Whether a solid block lies on the line between two points. */
    public static boolean blocked(World world, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-4) {
            return false;
        }
        Vector direction = new Vector(delta.x() / length, delta.y() / length, delta.z() / length);
        RayTraceResult result = world.rayTraceBlocks(new Location(world, from.x(), from.y(), from.z()), direction, length,
            FluidCollisionMode.NEVER, true);
        return result != null && result.getHitBlock() != null;
    }

    /** Another entity close enough to push the player around. */
    public static boolean pushingEntities(World world, Player player, AABB box) {
        return !world.getNearbyEntities(bukkit(box.expand(0.35)), e -> e != player && pushes(e)).isEmpty();
    }

    private static boolean pushes(Entity entity) {
        if (entity instanceof ArmorStand stand && stand.isMarker()) {
            return false;
        }
        if (entity instanceof LivingEntity living) {
            return living.isCollidable();
        }
        return entity instanceof Boat || entity instanceof Minecart;
    }
}
