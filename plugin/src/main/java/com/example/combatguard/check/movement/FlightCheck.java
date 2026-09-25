package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.GuardMath;
import org.bukkit.potion.PotionEffectType;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Airborne vertical movement must follow {@code vy = (vy - gravity) * 0.98}. Catches fly, glide, hover, spider,
 * air jump and jetpack cheats. Jumps are handled by the jump check, landings are skipped.
 */
public final class FlightCheck extends Check<FlightCheck.State> {
    public static final class State {
        double buffer;
    }

    public FlightCheck() {
        super("Flight", Category.MOVEMENT, "Airborne vertical movement that ignores gravity (Fly, Glide, Spider)", false, 2.0, 0.0, 0.25);
        option("tolerance", 0.005);
        option("buffer", 2.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.specialBlocks() || c.player.getAllowFlight()) {
            return;
        }
        PlayerData.MoveState m = c.data.move;
        if (!m.valid || c.knockback != null || c.onGround || c.jumped()
            || c.player.hasPotionEffect(PotionEffectType.LEVITATION) || c.nearEntities) {
            return;
        }
        State st = state(c.data);
        double lastDy = c.groundAtStart ? 0.0 : m.lastDy;
        double[] predicted = GuardMath.predictFall(lastDy, c.gravity(), c.player.hasPotionEffect(PotionEffectType.SLOW_FALLING), c.ticks);
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
