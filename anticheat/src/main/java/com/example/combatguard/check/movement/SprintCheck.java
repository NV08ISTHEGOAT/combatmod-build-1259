package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.PlayerInput;

/**
 * Since 1.21.2 the client tells the server which movement keys it holds. Vanilla stops sprinting as soon as
 * forward is not held, when food drops to 6 or below, or when blinded. OmniSprint and similar keep sprinting.
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
        if (!c.player.isSprinting()) {
            st.directionBuffer = Math.max(0.0, st.directionBuffer - 0.5);
            st.conditionTicks = 0;
            return;
        }
        PlayerInput input = c.player.getPlayerInput();
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

        boolean mayKeepSprinting = (c.player.getHungerManager().canSprint() || c.player.getAbilities().allowFlying)
            && !c.player.hasStatusEffect(StatusEffects.BLINDNESS);
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
