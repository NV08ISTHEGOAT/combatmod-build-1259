package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseEffects;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Horizontal speed with vanilla's friction model: each tick keeps {@code lastMove * friction} and adds at most
 * {@code movementSpeed * 0.216 / slipperiness^3} on the ground (0.026 in the air), plus 0.2 on a sprint jump.
 * Types: friction (Speed, BHop, LongJump), strafe (turning mid-air faster than air acceleration), noslow.
 */
public final class SpeedCheck extends Check<SpeedCheck.State> {
    public static final class State {
        double buffer;
        double noSlowBuffer;
        double strafeBuffer;
    }

    public SpeedCheck() {
        super("Speed", Category.MOVEMENT, "Moving faster than vanilla friction allows (Speed, BHop, Strafe, NoSlow)", false, 2.0, 0.0, 0.25);
        option("tolerance", 0.01);
        option("buffer", 2.0);
        option("noSlowBuffer", 4.0);
        option("strafeTolerance", 0.005);
        option("strafeBuffer", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.specialBlocks() || c.player.getGameMode() == GameMode.CREATIVE) {
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
            double kb = c.knockback.horizontalLength();
            base = c.knockbackAdditive ? base + kb : Math.max(base, kb);
        }
        double jump = c.jumped() ? 0.2 : 0.0;
        double tolerance = opt("tolerance") + (c.nearEntities ? 0.1 : 0.0);

        double allowed;
        if (c.ticks == 1) {
            allowed = base + accel + jump + tolerance;
        } else {
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

        if (!c.groundAtStart && m.valid && m.lastSingleTick && !m.lastHorizontalCollision && !c.packet.horizontalCollision()
            && c.data.tick.attackedIds.isEmpty() && !c.nearEntities) {
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

        ItemStack active = c.player.getActiveItem();
        if (c.player.isHandRaised() && !active.isEmpty() && c.player.getActiveItemUsedTime() >= 3
            && c.player.getActiveItemRemainingTime() >= 3) {
            UseEffects effects = active.getData(DataComponentTypes.USE_EFFECTS);
            float multiplier = effects == null ? 0.2F : effects.speedMultiplier();
            if (multiplier < 1.0F) {
                double slowJump = c.jumped() && c.data.sprinting ? 0.2 : 0.0;
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
    }
}
