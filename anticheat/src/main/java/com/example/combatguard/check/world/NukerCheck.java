package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.util.GuardMath;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Block breaking. The crosshair is on one block per tick, so starting to break two different blocks in the same
 * tick is impossible, and like placing, a full cube can only be hit on a face the player can see.
 */
public final class NukerCheck extends Check<Void> {
    public NukerCheck() {
        super("Nuker", Category.WORLD, "Breaking several or hidden blocks at once (Nuker, GhostHand)", false, 1.0, 0.0, 0.1);
        option("faceTolerance", 0.05);
    }

    public void onStartBreaking(ServerPlayerEntity player, PlayerData data, BlockPos pos, Direction side, List<Vec3d> eyes) {
        data.tick.startDigPositions.add(pos.asLong());
        if (!enabled() || player.isSpectator()) {
            return;
        }
        ServerWorld world = player.getEntityWorld();
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !Block.isShapeFullCube(state.getOutlineShape(world, pos))) {
            return;
        }
        boolean visible = false;
        for (Vec3d eye : eyes) {
            if (new net.minecraft.util.math.Box(pos).contains(eye)) {
                return;
            }
            visible |= GuardMath.canSeeFace(eye, pos, side, opt("faceTolerance"));
        }
        if (!visible) {
            flag(player, data, "face", "broke the hidden " + side.asString() + " face", 1.0);
        }
    }

    public void onTickEnd(ServerPlayerEntity player, PlayerData data) {
        int blocks = data.tick.startDigPositions.size();
        if (enabled() && blocks >= 2) {
            flag(player, data, "multi", blocks + " blocks started in one tick", 1.0);
        }
    }
}
