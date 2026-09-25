package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.math.GuardMath;
import org.bukkit.potion.PotionEffectType;

import static com.example.combatguard.math.GuardMath.fmt;

/** Ladders and vines: vanilla climbs 0.1176 blocks per tick (still-rising jumps may briefly be faster). */
public final class ClimbCheck extends Check<ClimbCheck.State> {
    public static final class State {
        double buffer;
    }

    private static final double CLIMB_SPEED = (0.2 - 0.08) * 0.98;

    public ClimbCheck() {
        super("FastClimb", Category.MOVEMENT, "Climbing ladders and vines faster than vanilla (FastLadder)", false, 1.0, 0.0, 0.2);
        option("tolerance", 0.01);
        option("buffer", 2.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.ticks != 1 || !c.data.move.valid || c.knockback != null || !c.onLadder()
            || c.groundAtStart || c.envFrom.liquid || c.envFrom.bouncy || c.player.hasPotionEffect(PotionEffectType.LEVITATION)
            || c.player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            return;
        }
        State st = state(c.data);
        double ballistic = GuardMath.predictFall(c.data.move.lastDy, c.gravity(), false, 1)[0];
        double allowed = Math.max(CLIMB_SPEED, ballistic) + opt("tolerance");
        if (c.dy > allowed) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(c.player, c.data, "speed", "climbed " + fmt(c.dy) + " max=" + fmt(allowed), 1.0);
                if (mitigate()) {
                    c.setback = true;
                }
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.25);
        }
    }
}
