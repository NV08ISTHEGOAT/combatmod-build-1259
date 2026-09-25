package com.example.combatguard.check.movement;

import com.example.combatguard.data.Exemptions;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.util.BlockEnv;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** One position packet, with the state needed to compare it against vanilla movement. */
public final class MoveContext {
    public final ServerPlayerEntity player;
    public final ServerWorld world;
    public final PlayerData data;
    public final PlayerMoveC2SPacket packet;
    public final Vec3d from;
    public final Vec3d to;
    public final double dx;
    public final double dy;
    public final double dz;
    public final double horizontal;
    /** Ground state the client reports after this move. */
    public final boolean onGround;
    /** Ground state at the start of this client tick (what the previous packet reported). */
    public final boolean groundAtStart;
    /** Client ticks covered by this packet (1 normally, more when the client skipped tiny movements). */
    public final int ticks;
    public final Box boxFrom;
    public final Box boxTo;
    public final BlockEnv envFrom;
    public final BlockEnv envTo;
    /** Another entity is close enough to push the player around. */
    public final boolean nearEntities;
    /** Knockback the client acknowledged before this move, or null. */
    public final Vec3d knockback;
    public final boolean knockbackAdditive;
    /** Flying, gliding, riding, spectating or riptide: physics the movement checks do not model. */
    public final boolean hardExempt;
    /** {@link #hardExempt}, or a grace period (teleport, respawn, world change, join, game mode change). */
    public final boolean exempt;

    /** Set by a check to teleport the player back to {@link #from}. */
    public boolean setback;
    /** Set by the ground spoof check to treat the packet as airborne. */
    public boolean forceAirborne;

    public MoveContext(ServerPlayerEntity player, PlayerData data, PlayerMoveC2SPacket packet, Vec3d from, Vec3d to, int ticks) {
        this.player = player;
        this.world = player.getEntityWorld();
        this.data = data;
        this.packet = packet;
        this.from = from;
        this.to = to;
        this.dx = to.x - from.x;
        this.dy = to.y - from.y;
        this.dz = to.z - from.z;
        this.horizontal = Math.sqrt(dx * dx + dz * dz);
        this.onGround = packet.isOnGround();
        this.groundAtStart = data.move.lastOnGround;
        this.ticks = Math.max(1, ticks);
        this.boxFrom = player.getBoundingBox();
        this.boxTo = boxFrom.offset(dx, dy, dz);
        this.envFrom = BlockEnv.scan(world, boxFrom);
        this.envTo = BlockEnv.scan(world, boxTo);
        this.nearEntities = !world.getOtherEntities(player, boxTo.expand(0.35), e -> e.isPushable() || e.isCollidable(player)).isEmpty();
        this.knockback = data.move.knockback;
        this.knockbackAdditive = data.move.knockbackAdditive;
        this.hardExempt = player.isSpectator()
            || player.getAbilities().flying
            || player.isGliding()
            || player.hasVehicle()
            || player.isUsingRiptide()
            || player.isSleeping()
            || player.isInTeleportationState();
        this.exempt = hardExempt || data.move.skip > 0 || Exemptions.movement(player, data);
    }

    /** Inside a ladder, vine or similar at the start or end of the move (not scaffolding or powder snow). */
    public boolean onLadder() {
        return isLadder(BlockPos.ofFloored(from)) || isLadder(BlockPos.ofFloored(to));
    }

    private boolean isLadder(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isIn(BlockTags.CLIMBABLE) && !state.isOf(Blocks.SCAFFOLDING);
    }

    /** Liquids, ladders, cobwebs, slime, pistons and similar: skip checks that assume plain air/ground physics. */
    public boolean specialBlocks() {
        return envFrom.special() || envTo.special() || data.move.lastSpecial;
    }

    public double slipperiness() {
        BlockPos below = BlockPos.ofFloored(from.x, from.y - 0.500001, from.z);
        return world.getBlockState(below).getBlock().getSlipperiness();
    }

    /** Movement speed attribute, assuming the player may be sprinting even if the sprint packet has not arrived. */
    public double movementSpeed() {
        double value = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        if (!player.isSprinting()) {
            value *= 1.3;
        }
        return data.maxRecentSpeed(value);
    }

    /** Largest horizontal acceleration the client could add during this tick. */
    public double acceleration() {
        if (groundAtStart) {
            double slip = slipperiness();
            return movementSpeed() * (0.21600002 / (slip * slip * slip));
        }
        return 0.026;
    }

    public double friction() {
        return groundAtStart ? slipperiness() * 0.91 : 0.91;
    }

    public boolean jumped() {
        return groundAtStart && !onGround && dy > 0.0;
    }
}
