package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UseEffectsComponent;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Horizontal speed, NoSlow and KeepSprint. Uses vanilla's movement formula: each tick the client keeps
 * {@code lastMove * friction} and adds at most {@code movementSpeed * 0.216 / slipperiness^3} on the ground
 * (0.026 in the air), plus 0.2 on a sprint jump. Anything above that bound is not reachable in vanilla.
 * In the air the same rule holds per direction, which catches Strafe: the new move can differ from
 * {@code lastMove * 0.91} by at most the air acceleration, so turning mid-air is limited.
 */
public final class SpeedCheck extends Check<SpeedCheck.State> {
    public static final class State {
        double buffer;
        double noSlowBuffer;
        double keepSprintBuffer;
        double strafeBuffer;
    }

    public SpeedCheck() {
        super("Speed", Category.MOVEMENT, "Horizontal movement faster than vanilla friction allows (also NoSlow, KeepSprint)", false, 2.0, 0.0, 0.25);
        option("tolerance", 0.01);
        option("buffer", 2.0);
        option("noSlowBuffer", 4.0);
        option("keepSprintBuffer", 4.0);
        option("strafeTolerance", 0.005);
        option("strafeBuffer", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.specialBlocks() || c.player.isCreative()) {
            return;
        }
        PlayerData.MoveState m = c.data.move;
        if (!m.valid && c.knockback == null) {
            return;
        }
        State st = state(c.data);

        double accel = c.acceleration();
        double base = m.valid ? m.lastHorizontal * m.lastFriction : 0.0;
        if (c.knockback != null) {
            double kb = Math.sqrt(c.knockback.x * c.knockback.x + c.knockback.z * c.knockback.z);
            base = c.knockbackAdditive ? base + kb : Math.max(base, kb);
        }
        double jump = c.jumped() ? 0.2 : 0.0;
        double tolerance = opt("tolerance") + (c.nearEntities ? 0.1 : 0.0);

        double allowed;
        if (c.ticks == 1) {
            allowed = base + accel + jump + tolerance;
        } else {
            // Several ticks in one packet: simulate the best case for each tick.
            double perTick = Math.max(accel, c.movementSpeed());
            double v = base;
            allowed = 0.0;
            for (int i = 0; i < c.ticks; i++) {
                v += perTick + (i == 0 ? jump : 0.0);
                allowed += v;
                v *= 0.91;
            }
            allowed += tolerance * c.ticks;
        }

        if (c.horizontal > allowed) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                double excess = c.horizontal - allowed;
                flag(c.player, c.data, "friction", "speed=" + fmt(c.horizontal) + " max=" + fmt(allowed), 1.0 + Math.min(excess * 5.0, 4.0));
                if (mitigate()) {
                    c.setback = true;
                }
            }
            return;
        }
        st.buffer = Math.max(0.0, st.buffer - 0.05);

        if (c.ticks != 1 || c.knockback != null) {
            return;
        }

        // Strafe: in the air, the move must be last move * friction plus at most the air acceleration, per axis.
        if (!c.groundAtStart && m.valid && m.lastSingleTick && !m.lastHorizontalCollision && !c.packet.horizontalCollision()
            && !m.sprintHitSlowdown && c.data.tick.attackedIds.isEmpty() && !c.nearEntities) {
            double ex = m.lastDx * m.lastFriction;
            double ez = m.lastDz * m.lastFriction;
            double residual = Math.sqrt((c.dx - ex) * (c.dx - ex) + (c.dz - ez) * (c.dz - ez));
            double strafeAllowed = 0.026 + opt("strafeTolerance");
            if (residual > strafeAllowed) {
                st.strafeBuffer += 1.0;
                if (st.strafeBuffer > opt("strafeBuffer")) {
                    flag(c.player, c.data, "strafe", "air direction change " + fmt(residual) + " max=" + fmt(strafeAllowed), 1.0);
                    if (mitigate()) {
                        c.setback = true;
                    }
                }
            } else {
                st.strafeBuffer = Math.max(0.0, st.strafeBuffer - 0.1);
            }
        }

        // NoSlow: using an item multiplies the input by the item's speed multiplier (0.2 by default).
        if (c.player.isUsingItem() && c.player.getItemUseTime() >= 3 && c.player.getItemUseTimeLeft() >= 3) {
            float multiplier = c.player.getActiveItem().getOrDefault(DataComponentTypes.USE_EFFECTS, UseEffectsComponent.DEFAULT).speedMultiplier();
            if (multiplier < 1.0F) {
                double slowJump = c.jumped() && c.player.isSprinting() ? 0.2 : 0.0;
                double slowAllowed = base + accel * multiplier + slowJump + tolerance;
                if (c.horizontal > slowAllowed + 0.01) {
                    st.noSlowBuffer += 1.0;
                    if (st.noSlowBuffer > opt("noSlowBuffer")) {
                        flag(c.player, c.data, "noslow", "speed=" + fmt(c.horizontal) + " max=" + fmt(slowAllowed), 1.0);
                        if (mitigate()) {
                            c.setback = true;
                        }
                    }
                } else {
                    st.noSlowBuffer = Math.max(0.0, st.noSlowBuffer - 0.25);
                }
            }
        }

        // KeepSprint: a sprint hit multiplies the attacker's velocity by 0.6 before it moves.
        if (m.sprintHitSlowdown && m.valid) {
            double keepAllowed = base * 0.6 + accel + jump + tolerance;
            if (c.horizontal > keepAllowed + 0.01) {
                st.keepSprintBuffer += 1.0;
                if (st.keepSprintBuffer > opt("keepSprintBuffer")) {
                    flag(c.player, c.data, "keepsprint", "speed=" + fmt(c.horizontal) + " max=" + fmt(keepAllowed), 0.5);
                }
            } else {
                st.keepSprintBuffer = Math.max(0.0, st.keepSprintBuffer - 0.5);
            }
        }
    }
}
