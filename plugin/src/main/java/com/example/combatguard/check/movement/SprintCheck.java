package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.stream.GuardEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Since 1.21.2 the client sends its movement keys. Vanilla stops sprinting when forward is not held, when food
 * drops to 6 or below, and when blinded. OmniSprint keeps sprinting.
 */
public final class SprintCheck extends Check<SprintCheck.State> {
    public static final class State {
        double directionBuffer;
        int conditionTicks;
    }

    public SprintCheck() {
        super("Sprint", Category.MOVEMENT, "Sprinting without forward input, while starving or while blind (OmniSprint)", false, 2.0, 0.0, 0.2);
        option("buffer", 4.0);
        option("conditionTicks", 40.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.envFrom.liquid || c.envTo.liquid || c.player.isSwimming() || c.ticks != 1) {
            return;
        }
        State st = state(c.data);
        if (!c.data.sprinting) {
            st.directionBuffer = Math.max(0.0, st.directionBuffer - 0.5);
            st.conditionTicks = 0;
            return;
        }
        GuardEvent.Input input = c.data.input;
        boolean forward = input.forward() && !input.backward();
        boolean settled = c.data.clientTick - c.data.lastInputChangeTick > 2;
        if (!forward && settled && c.horizontal > 0.1) {
            st.directionBuffer += 1.0;
            if (st.directionBuffer > opt("buffer")) {
                flag(c.player, c.data, "direction", "sprinting without forward input", 1.0);
            }
        } else {
            st.directionBuffer = Math.max(0.0, st.directionBuffer - 0.25);
        }
        boolean mayKeepSprinting = (c.player.getFoodLevel() > 6 || c.player.getAllowFlight())
            && !c.player.hasPotionEffect(PotionEffectType.BLINDNESS);
        if (!mayKeepSprinting) {
            if (++st.conditionTicks > opt("conditionTicks")) {
                flag(c.player, c.data, "condition", "sprinting while starving or blind", 1.0);
                st.conditionTicks = 0;
            }
        } else {
            st.conditionTicks = 0;
        }
    }
}
