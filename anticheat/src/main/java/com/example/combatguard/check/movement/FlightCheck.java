package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.entity.effect.StatusEffects;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Vertical movement in the air. Vanilla applies {@code vy = (vy - gravity) * 0.98} every tick, so a player who
 * is off the ground can never rise faster or fall slower than that. Catches fly, glide, hover, spider,
 * air jump and jetpack style cheats.
 */
public final class FlightCheck extends Check<FlightCheck.State> {
    public static final class State {
        double buffer;
    }

    public FlightCheck() {
        super("Flight", Category.MOVEMENT, "Airborne vertical movement that ignores gravity", false, 2.0, 0.0, 0.25);
        option("tolerance", 0.005);
        option("buffer", 2.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    /** Predicts the total vertical movement over {@code ticks} ticks starting from {@code lastDy}. */
    public static double[] predict(double lastDy, double gravity, boolean slowFalling, int ticks) {
        double v = lastDy;
        double total = 0.0;
        for (int i = 0; i < ticks; i++) {
            double g = slowFalling && v <= 0.0 ? Math.min(gravity, 0.01) : gravity;
            v = (v - g) * 0.98;
            if (Math.abs(v) < 0.003) {
                v = 0.0;
            }
            total += v;
        }
        return new double[]{total, v};
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.specialBlocks() || c.player.getAbilities().allowFlying) {
            return;
        }
        PlayerData.MoveState m = c.data.move;
        if (!m.valid || c.knockback != null || c.onGround || c.jumped()
            || c.player.hasStatusEffect(StatusEffects.LEVITATION) || c.nearEntities) {
            return;
        }
        State st = state(c.data);
        double lastDy = c.groundAtStart ? 0.0 : m.lastDy;
        double[] predicted = predict(lastDy, c.player.getFinalGravity(), c.player.hasStatusEffect(StatusEffects.SLOW_FALLING), c.ticks);
        double diff = c.dy - predicted[0];
        if (diff > opt("tolerance") * c.ticks) {
            st.buffer += 1.0;
            if (st.buffer >= opt("buffer")) {
                flag(c.player, c.data, "gravity", "dy=" + fmt(c.dy) + " expected=" + fmt(predicted[0]), 1.0 + Math.min(diff * 10.0, 3.0));
                if (mitigate()) {
                    c.setback = true;
                }
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.1);
        }
    }
}
