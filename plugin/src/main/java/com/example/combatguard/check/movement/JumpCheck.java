package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.potion.PotionEffectType;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Leaving the ground: a vanilla jump rises exactly jumpVelocity on its first tick (less only under a ceiling) and a
 * step never climbs more than the step height. Types: high, step, low (tiny hops used by packet criticals).
 */
public final class JumpCheck extends Check<JumpCheck.State> {
    public static final class State {
        double lowBuffer;
    }

    public JumpCheck() {
        super("Jump", Category.MOVEMENT, "Jumps and steps that do not match vanilla jump velocity or step height (HighJump, Step)", false, 1.0, 0.0, 0.2);
        option("tolerance", 0.01);
        option("lowBuffer", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c, double maxJump, double minJump) {
        if (!enabled() || c.exempt || c.specialBlocks() || c.ticks != 1 || c.player.getAllowFlight()) {
            return;
        }
        PlayerData.MoveState m = c.data.move;
        if (!m.valid || c.knockback != null || !c.groundAtStart || c.nearEntities
            || c.player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            return;
        }
        State st = state(c.data);
        double tolerance = opt("tolerance");
        double step = c.stepHeight();
        if (c.dy > Math.max(maxJump, step) + tolerance) {
            flag(c.player, c.data, c.onGround ? "step" : "high", "dy=" + fmt(c.dy) + " max=" + fmt(Math.max(maxJump, step)), 1.0);
            if (mitigate()) {
                c.setback = true;
            }
            return;
        }
        if (!c.onGround && c.dy > 0.0 && c.dy < minJump - tolerance) {
            boolean ceiling = com.example.combatguard.world.WorldQuery.hasCollision(c.world, c.boxTo.stretch(0.0, minJump - c.dy + 0.02, 0.0));
            if (ceiling) {
                return;
            }
            c.data.move.lastLowJumpTick = c.data.clientTick;
            st.lowBuffer += 1.0;
            if (st.lowBuffer >= opt("lowBuffer")) {
                flag(c.player, c.data, "low", "dy=" + fmt(c.dy) + " expected=" + fmt(minJump), 0.5);
            }
        } else if (c.jumped()) {
            st.lowBuffer = Math.max(0.0, st.lowBuffer - 0.5);
        }
    }
}
