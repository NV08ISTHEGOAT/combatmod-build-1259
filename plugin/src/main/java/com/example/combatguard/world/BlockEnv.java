package com.example.combatguard.world;

import com.example.combatguard.math.AABB;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/**
 * Summary of the blocks around a player's box. Movement checks skip physics they do not model (liquids, ladders,
 * cobwebs, bouncy blocks, pistons) instead of guessing.
 */
public final class BlockEnv {
    public boolean liquid;
    public boolean climbable;
    public boolean slowing;
    public boolean bouncy;
    public boolean pistons;
    public boolean unloaded;

    public boolean special() {
        return liquid || climbable || slowing || bouncy || pistons || unloaded;
    }

    public static BlockEnv scan(World world, AABB box) {
        BlockEnv env = new BlockEnv();
        AABB area = box.expand(0.4, 0.0, 0.4).stretch(0.0, -0.8, 0.0).stretch(0.0, 0.4, 0.0);
        int minX = (int) Math.floor(area.minX());
        int minY = (int) Math.floor(area.minY());
        int minZ = (int) Math.floor(area.minZ());
        int maxX = (int) Math.floor(area.maxX());
        int maxY = (int) Math.floor(area.maxY());
        int maxZ = (int) Math.floor(area.maxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    env.unloaded = true;
                    return env;
                }
                for (int y = minY; y <= maxY; y++) {
                    Material type = world.getType(x, y, z);
                    if (type.isAir()) {
                        continue;
                    }
                    if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN
                        || type == Material.KELP || type == Material.KELP_PLANT || type == Material.SEAGRASS
                        || type == Material.TALL_SEAGRASS) {
                        env.liquid = true;
                    } else if (type.isSolid() || !type.isOccluding()) {
                        BlockData data = world.getBlockData(x, y, z);
                        if (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                            env.liquid = true;
                        }
                    }
                    if (Tag.CLIMBABLE.isTagged(type) || type == Material.SCAFFOLDING || type == Material.POWDER_SNOW) {
                        env.climbable = true;
                    }
                    if (type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH || type == Material.HONEY_BLOCK
                        || type == Material.SOUL_SAND) {
                        env.slowing = true;
                    }
                    if (type == Material.SLIME_BLOCK || Tag.BEDS.isTagged(type)) {
                        env.bouncy = true;
                    }
                    if (type == Material.PISTON || type == Material.STICKY_PISTON || type == Material.PISTON_HEAD
                        || type == Material.MOVING_PISTON) {
                        env.pistons = true;
                    }
                }
            }
        }
        return env;
    }
}
