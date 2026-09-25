package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.entity.effect.StatusEffects;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Leaving the ground. A vanilla jump moves exactly {@code jumpVelocity} up on its first tick (less only when a
 * ceiling is in the way) and a step never climbs more than the step height. Catches high jump, step and the
 * tiny hops used by packet criticals.
 */
public final class JumpCheck extends Check<JumpCheck.State> {
    public static final class State {
        double lowBuffer;
    }

    public JumpCheck() {
        super("Jump", Category.MOVEMENT, "Jumps and steps that do not match vanilla jump velocity or step height", false, 1.0, 0.0, 0.2);
        option("tolerance", 0.01);
        option("lowBuffer", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c, double jumpVelocity, double minRecentJumpVelocity) {
        if (!enabled() || c.exempt || c.specialBlocks() || c.ticks != 1 || c.player.getAbilities().allowFlying) {
            return;
        }
        PlayerData.MoveState m = c.data.move;
        if (!m.valid || c.knockback != null || !c.groundAtStart || c.nearEntities
            || c.player.hasStatusEffect(StatusEffects.LEVITATION)) {
            return;
        }
        State st = state(c.data);
        double tolerance = opt("tolerance");
        double step = c.player.getStepHeight();

        if (c.dy > Math.max(jumpVelocity, step) + tolerance) {
            flag(c.player, c.data, c.onGround ? "step" : "high", "dy=" + fmt(c.dy) + " max=" + fmt(Math.max(jumpVelocity, step)), 1.0);
            if (mitigate()) {
                c.setback = true;
            }
            return;
        }

        if (!c.onGround && c.dy > 0.0 && c.dy < minRecentJumpVelocity - tolerance) {
            // A ceiling can cut a jump short; only a free jump has to reach full height.
            boolean ceiling = !c.world.isSpaceEmpty(c.player, c.boxTo.stretch(0.0, minRecentJumpVelocity - c.dy + 0.02, 0.0));
            if (ceiling) {
                return;
            }
            c.data.move.lastLowJumpTick = c.data.clientTick;
            st.lowBuffer += 1.0;
            if (st.lowBuffer >= opt("lowBuffer")) {
                flag(c.player, c.data, "low", "dy=" + fmt(c.dy) + " expected=" + fmt(minRecentJumpVelocity), 0.5);
            }
        } else if (c.jumped()) {
            st.lowBuffer = Math.max(0.0, st.lowBuffer - 0.5);
        }
    }
}
