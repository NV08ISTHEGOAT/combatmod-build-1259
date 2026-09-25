package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.util.GuardMath;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.List;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Block placement. For full cubes the vanilla client can only click a face it can see, and the hit position it
 * sends lies exactly on that face. The look direction at the end of the tick has to point at the block, and
 * placement speed is limited by how fast a person can click.
 */
public final class ScaffoldCheck extends Check<ScaffoldCheck.State> {
    public static final class State {
        final ArrayDeque<Integer> placeTicks = new ArrayDeque<>();
        double aimBuffer;
        double rateBuffer;
    }

    public ScaffoldCheck() {
        super("Scaffold", Category.WORLD, "Impossible block placement (Scaffold, FastPlace, AirPlace)", false, 1.0, 0.0, 0.1);
        option("faceTolerance", 0.05);
        option("hitVectorTolerance", 0.001);
        option("aimExpand", 0.3);
        option("aimBuffer", 2.0);
        option("maxPlacesPerSecond", 20.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onPlace(ServerPlayerEntity player, PlayerData data, BlockHitResult hit, List<Vec3d> eyes) {
        if (!enabled() || player.isSpectator()) {
            return;
        }
        State st = state(data);
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = hit.getBlockPos();
        if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) && !hit.isInsideBlock()) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && Block.isShapeFullCube(state.getOutlineShape(world, pos))) {
                boolean visible = false;
                for (Vec3d eye : eyes) {
                    visible |= GuardMath.canSeeFace(eye, pos, hit.getSide(), opt("faceTolerance"));
                }
                if (!visible) {
                    flag(player, data, "face", "clicked the hidden " + hit.getSide().asString() + " face", 1.0);
                }
                double error = GuardMath.hitVectorError(hit.getPos(), pos, hit.getSide());
                if (error > opt("hitVectorTolerance")) {
                    flag(player, data, "hitvec", "hit position " + fmt(error) + " off the face", 1.0);
                }
            }
        }

        int tick = data.clientTick;
        st.placeTicks.addLast(tick);
        while (!st.placeTicks.isEmpty() && st.placeTicks.peekFirst() <= tick - 20) {
            st.placeTicks.removeFirst();
        }
        if (st.placeTicks.size() > opt("maxPlacesPerSecond")) {
            st.rateBuffer += 1.0;
            if (st.rateBuffer > 3.0) {
                flag(player, data, "rate", st.placeTicks.size() + " blocks per second", 0.5);
            }
        } else {
            st.rateBuffer = Math.max(0.0, st.rateBuffer - 0.1);
        }

        data.tick.places.add(new PlayerData.PendingPlace(eyes, pos, hit.getSide()));
    }

    public void onTickEnd(ServerPlayerEntity player, PlayerData data) {
        if (!enabled() || data.tick.places.isEmpty()) {
            return;
        }
        State st = state(data);
        Vec3d look = GuardMath.lookVector(player.getPitch(), player.getYaw());
        double range = player.getBlockInteractionRange() + 1.0;
        for (PlayerData.PendingPlace place : data.tick.places) {
            Box box = new Box(place.pos()).expand(opt("aimExpand"));
            boolean hit = false;
            for (Vec3d eye : place.eyes()) {
                hit |= GuardMath.rayHits(eye, look, range, box);
            }
            if (hit) {
                st.aimBuffer = Math.max(0.0, st.aimBuffer - 0.25);
            } else {
                st.aimBuffer += 1.0;
                if (st.aimBuffer > opt("aimBuffer")) {
                    flag(player, data, "aim", "placed a block it was not looking at", 1.0);
                }
            }
        }
    }
}
