package com.example.combatguard.check.movement;

import com.example.combatguard.data.Exemptions;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.stream.GuardEvent;
import com.example.combatguard.world.BlockEnv;
import com.example.combatguard.world.WorldQuery;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/** One position packet from the stream, with what is needed to compare it against vanilla movement. */
public final class MoveContext {
    public final Player player;
    public final World world;
    public final PlayerData data;
    public final GuardEvent.Move packet;
    public final Vec3 from;
    public final Vec3 to;
    public final double dx;
    public final double dy;
    public final double dz;
    public final double horizontal;
    public final boolean onGround;
    public final boolean groundAtStart;
    public final int ticks;
    public final AABB boxFrom;
    public final AABB boxTo;
    public final BlockEnv envFrom;
    public final BlockEnv envTo;
    public final boolean nearEntities;
    public final Vec3 knockback;
    public final boolean knockbackAdditive;
    /** Flying, gliding, riding, spectating, riptide or sleeping: physics the movement checks do not model. */
    public final boolean hardExempt;
    /** {@link #hardExempt} or a grace period (teleport, respawn, world change, join, game mode change). */
    public final boolean exempt;

    public boolean setback;
    public boolean forceAirborne;

    public MoveContext(Player player, PlayerData data, GuardEvent.Move packet, Vec3 from, Vec3 to, int ticks) {
        this.player = player;
        this.world = player.getWorld();
        this.data = data;
        this.packet = packet;
        this.from = from;
        this.to = to;
        this.dx = to.x() - from.x();
        this.dy = to.y() - from.y();
        this.dz = to.z() - from.z();
        this.horizontal = Math.sqrt(dx * dx + dz * dz);
        this.onGround = packet.onGround();
        this.groundAtStart = data.move.lastOnGround;
        this.ticks = Math.max(1, ticks);
        double width = player.getBoundingBox().getWidthX();
        double height = player.getBoundingBox().getHeight();
        this.boxFrom = AABB.ofEntity(from, width, height);
        this.boxTo = AABB.ofEntity(to, width, height);
        this.envFrom = BlockEnv.scan(world, boxFrom);
        this.envTo = BlockEnv.scan(world, boxTo);
        this.nearEntities = WorldQuery.pushingEntities(world, player, boxTo);
        this.knockback = data.move.knockback;
        this.knockbackAdditive = data.move.knockbackAdditive;
        this.hardExempt = player.getGameMode() == GameMode.SPECTATOR
            || player.isFlying()
            || player.isGliding()
            || player.isInsideVehicle()
            || player.isRiptiding()
            || player.isSleeping();
        this.exempt = hardExempt || data.move.skip > 0 || Exemptions.movement(player, data);
    }

    public boolean specialBlocks() {
        return envFrom.special() || envTo.special() || data.move.lastSpecial;
    }

    public boolean onLadder() {
        return isLadder(from) || isLadder(to);
    }

    private boolean isLadder(Vec3 pos) {
        Material type = world.getType((int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()));
        return Tag.CLIMBABLE.isTagged(type) && type != Material.SCAFFOLDING;
    }

    public double slipperiness() {
        return WorldQuery.slipperiness(world, from);
    }

    private static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    /** Movement speed, assuming the player may be sprinting even if the server has not seen it yet. */
    public double movementSpeed() {
        double value = attribute(player, Attribute.MOVEMENT_SPEED, 0.1);
        if (!player.isSprinting()) {
            value *= 1.3;
        }
        return data.maxRecentSpeed(value);
    }

    public double gravity() {
        return attribute(player, Attribute.GRAVITY, 0.08);
    }

    public double stepHeight() {
        return attribute(player, Attribute.STEP_HEIGHT, 0.6);
    }

    /** Largest horizontal acceleration the client can add in one tick. */
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

    /** Jump strength times the block factor plus jump boost, what a vanilla jump uses. */
    public static double jumpVelocity(Player player, Vec3 position) {
        double strength = attribute(player, Attribute.JUMP_STRENGTH, 0.42);
        var boost = player.getPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST);
        double extra = boost == null ? 0.0 : 0.1 * (boost.getAmplifier() + 1);
        return strength * WorldQuery.jumpFactor(player.getWorld(), position) + extra;
    }
}
