package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;

/**
 * NoFall, Jesus and AntiHunger cheats claim to be on the ground while nothing is under the player. The server
 * checks the claim against its own collision boxes (blocks and collidable entities such as boats).
 */
public final class GroundSpoofCheck extends Check<GroundSpoofCheck.State> {
    public static final class State {
        int unsupported;
    }

    public GroundSpoofCheck() {
        super("GroundSpoof", Category.MOVEMENT, "Claims to stand on the ground with nothing below (NoFall, Jesus)", true, 1.0, 0.0, 0.2);
        option("depth", 0.1);
        option("buffer", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        if (!enabled()) {
            return;
        }
        State st = state(c.data);
        if (!c.onGround || c.exempt || c.envTo.unloaded || c.envTo.pistons || c.player.getAbilities().flying) {
            st.unsupported = 0;
            return;
        }
        boolean supported = !c.world.isSpaceEmpty(c.player, c.boxTo.stretch(0.0, -opt("depth"), 0.0));
        if (supported) {
            st.unsupported = 0;
            return;
        }
        st.unsupported++;
        // Blocks broken under the player take a round trip to reach the client, so allow for latency.
        int threshold = (int) opt("buffer") + c.player.networkHandler.getLatency() / 50;
        if (st.unsupported >= 2 && mitigate()) {
            c.forceAirborne = true;
        }
        if (st.unsupported >= threshold) {
            flag(c.player, c.data, "ground", "onGround with no block below, fall=" + String.format(java.util.Locale.ROOT, "%.1f", c.player.fallDistance), 1.0);
        }
    }
}
