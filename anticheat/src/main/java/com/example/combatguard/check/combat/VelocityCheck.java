package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.check.movement.MoveContext;
import com.example.combatguard.data.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Anti-knockback. Every velocity packet sent to a player is followed by a ping. When the pong comes back the
 * client has applied the velocity, so its next move has to contain it: vertically exactly (a jump can only add
 * to it), horizontally up to the acceleration the player's own input can add or remove in one tick.
 */
public final class VelocityCheck extends Check<VelocityCheck.State> {
    public static final class State {
        double buffer;
    }

    public VelocityCheck() {
        super("Velocity", Category.COMBAT, "Ignoring or reducing knockback (AntiKB, Velocity)", false, 1.0, 25.0, 0.1);
        option("tolerance", 0.03);
        option("buffer", 1.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void process(MoveContext c) {
        Vec3d kb = c.knockback;
        if (!enabled() || kb == null || c.knockbackAdditive || c.exempt || c.specialBlocks() || c.ticks != 1 || c.nearEntities) {
            return;
        }
        State st = state(c.data);
        String failed = null;

        double kbHorizontal = Math.sqrt(kb.x * kb.x + kb.z * kb.z);
        if (kbHorizontal > 0.1 && !c.packet.horizontalCollision()
            && c.world.isSpaceEmpty(c.player, c.boxFrom.offset(kb.x, Math.max(kb.y, 0.0), kb.z))) {
            double allowed = c.acceleration() + (c.jumped() ? 0.2 : 0.0) + opt("tolerance");
            double missing = offset(c.dx, c.dz, kb.x, kb.z);
            if (c.data.move.sprintHitSlowdown) {
                missing = Math.min(missing, offset(c.dx, c.dz, kb.x * 0.6, kb.z * 0.6));
            }
            if (missing > allowed) {
                double taken = (c.dx * kb.x + c.dz * kb.z) / (kbHorizontal * kbHorizontal);
                failed = "horizontal " + Math.round(taken * 100.0) + "% (kb=" + fmt(kbHorizontal) + ")";
            }
        }

        if (failed == null && kb.y > 0.05
            && c.world.isSpaceEmpty(c.player, c.boxFrom.stretch(0.0, kb.y + 0.02, 0.0))
            && c.dy < kb.y - 0.01) {
            failed = "vertical " + Math.round(c.dy / kb.y * 100.0) + "% (kb=" + fmt(kb.y) + ")";
        }

        if (failed != null) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(c.player, c.data, "knockback", failed, 1.5);
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.5);
        }
    }

    /** Distance between the move and the expected knockback, i.e. how much the input would have to cancel. */
    private static double offset(double dx, double dz, double kx, double kz) {
        double x = dx - kx;
        double z = dz - kz;
        return Math.sqrt(x * x + z * z);
    }

    /** A client that stops answering the ping sent after knockback is dodging this check. */
    public void transactionTimeout(ServerPlayerEntity player, PlayerData data) {
        flag(player, data, "transaction", "no reply to the ping sent after knockback", 2.0);
    }
}
