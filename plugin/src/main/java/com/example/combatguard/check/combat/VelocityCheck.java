package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.check.movement.MoveContext;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.world.WorldQuery;
import org.bukkit.entity.Player;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Anti-knockback. Every velocity packet is followed by a ping; once the pong arrives the client has applied the
 * knockback, so its next move has to contain it: vertically exactly (a jump only adds), horizontally up to what
 * the player's own input can change in one tick.
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
        Vec3 kb = c.knockback;
        if (!enabled() || kb == null || c.knockbackAdditive || c.exempt || c.specialBlocks() || c.ticks != 1 || c.nearEntities) {
            return;
        }
        State st = state(c.data);
        String failed = null;
        double kbHorizontal = kb.horizontalLength();
        if (kbHorizontal > 0.1 && !c.packet.horizontalCollision()
            && !WorldQuery.hasBlockCollision(c.world, c.boxFrom.offset(kb.x(), Math.max(kb.y(), 0.0), kb.z()))) {
            double allowed = c.acceleration() + (c.jumped() ? 0.2 : 0.0) + opt("tolerance");
            double x = c.dx - kb.x();
            double z = c.dz - kb.z();
            double missing = Math.sqrt(x * x + z * z);
            // A sprint hit by the victim in the same tick multiplies its own velocity by 0.6.
            if (!c.data.tick.attackedIds.isEmpty()) {
                double x6 = c.dx - kb.x() * 0.6;
                double z6 = c.dz - kb.z() * 0.6;
                missing = Math.min(missing, Math.sqrt(x6 * x6 + z6 * z6));
            }
            if (missing > allowed) {
                double taken = (c.dx * kb.x() + c.dz * kb.z()) / (kbHorizontal * kbHorizontal);
                failed = "horizontal " + Math.round(taken * 100.0) + "% (kb=" + fmt(kbHorizontal) + ")";
            }
        }
        if (failed == null && kb.y() > 0.05 && !WorldQuery.hasBlockCollision(c.world, c.boxFrom.stretch(0.0, kb.y() + 0.02, 0.0))
            && c.dy < kb.y() - 0.01) {
            failed = "vertical " + Math.round(c.dy / kb.y() * 100.0) + "% (kb=" + fmt(kb.y()) + ")";
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

    public void transactionTimeout(Player player, PlayerData data) {
        flag(player, data, "transaction", "no reply to the ping sent after knockback", 2.0);
    }
}
