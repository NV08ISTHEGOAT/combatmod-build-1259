package com.example.combatguard.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.PistonExtensionBlock;
import net.minecraft.block.PistonHeadBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

/**
 * Summary of the blocks around a player's bounding box. Movement checks skip situations whose physics they
 * do not model (liquids, ladders, cobwebs, bouncy blocks, pistons, ...) instead of guessing.
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

    public static BlockEnv scan(ServerWorld world, Box box) {
        BlockEnv env = new BlockEnv();
        Box area = box.expand(0.4, 0.0, 0.4).stretch(0.0, -0.8, 0.0).stretch(0.0, 0.4, 0.0);
        int minX = MathHelper.floor(area.minX);
        int minY = MathHelper.floor(area.minY);
        int minZ = MathHelper.floor(area.minZ);
        int maxX = MathHelper.floor(area.maxX);
        int maxY = MathHelper.floor(area.maxY);
        int maxZ = MathHelper.floor(area.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    env.unloaded = true;
                    return env;
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (!state.getFluidState().isEmpty() || state.isOf(Blocks.BUBBLE_COLUMN)) {
                        env.liquid = true;
                    }
                    if (state.isIn(BlockTags.CLIMBABLE) || state.isOf(Blocks.SCAFFOLDING) || state.isOf(Blocks.POWDER_SNOW)) {
                        env.climbable = true;
                    }
                    if (state.isOf(Blocks.COBWEB) || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.HONEY_BLOCK)
                        || state.isOf(Blocks.SOUL_SAND) || state.getBlock().getVelocityMultiplier() != 1.0F) {
                        env.slowing = true;
                    }
                    if (state.isOf(Blocks.SLIME_BLOCK) || state.isIn(BlockTags.BEDS)) {
                        env.bouncy = true;
                    }
                    if (state.getBlock() instanceof PistonBlock || state.getBlock() instanceof PistonHeadBlock
                        || state.getBlock() instanceof PistonExtensionBlock) {
                        env.pistons = true;
                    }
                }
            }
        }
        return env;
    }
}
