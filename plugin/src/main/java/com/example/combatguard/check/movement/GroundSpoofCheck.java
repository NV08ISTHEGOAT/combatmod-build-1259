package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.world.WorldQuery;

import java.util.Locale;

/**
 * NoFall, Jesus and AntiHunger claim to be on the ground while nothing is under the player. When mitigating, the
 * next movement packets are rewritten as airborne so fall damage still happens.
 */
public final class GroundSpoofCheck extends Check<GroundSpoofCheck.State> {
    public static final class State {
        int unsupported;
    }

    public GroundSpoofCheck() {
        super("GroundSpoof", Category.MOVEMENT, "Claiming to stand on the ground with nothing below (NoFall, Jesus)", true, 1.0, 0.0, 0.2);
        option("depth", 0.1);
        option("buffer", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c, int ping) {
        if (!enabled()) {
            return;
        }
        State st = state(c.data);
        if (!c.onGround || c.exempt || c.envTo.unloaded || c.envTo.pistons) {
            st.unsupported = 0;
            c.data.forceAirborne = false;
            return;
        }
        boolean supported = WorldQuery.hasCollision(c.world, c.boxTo.stretch(0.0, -opt("depth"), 0.0));
        if (supported) {
            st.unsupported = 0;
            c.data.forceAirborne = false;
            return;
        }
        st.unsupported++;
        int threshold = (int) opt("buffer") + ping / 50;
        if (st.unsupported >= 2 && mitigate()) {
            c.forceAirborne = true;
            c.data.forceAirborne = true;
        }
        if (st.unsupported >= threshold) {
            flag(c.player, c.data, "ground", String.format(Locale.ROOT, "on ground with no block below, fall=%.1f", c.player.getFallDistance()), 1.0);
        }
    }
}
