package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.util.GuardMath;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Attack distance. Vanilla only rejects hits more than 3 blocks past the attack range, so a 3 -> 6 reach cheat
 * works on an unprotected server. This check measures from the attacker's eye to the closest position the
 * target had during the attacker's latency window, so lag does not cause false flags.
 */
public final class ReachCheck extends Check<ReachCheck.State> {
    public static final class State {
        double buffer;
    }

    public ReachCheck() {
        super("Reach", Category.COMBAT, "Hitting entities from further than the attack range", true, 2.0, 20.0, 0.1);
        option("tolerance", 0.1);
        option("buffer", 1.0);
        option("cancelMargin", 0.3);
    }

    @Override
    public State newState() {
        return new State();
    }

    /** @return true when the attack should be cancelled */
    public boolean onAttack(ServerPlayerEntity player, PlayerData data, List<Vec3d> eyes, List<Box> boxes, double range) {
        if (!enabled() || boxes.isEmpty() || player.isCreative() || player.isSpectator()) {
            return false;
        }
        State st = state(data);
        double distance = Double.MAX_VALUE;
        for (Vec3d eye : eyes) {
            for (Box box : boxes) {
                distance = Math.min(distance, GuardMath.distanceToBox(eye, box));
            }
        }
        if (distance > range + opt("tolerance")) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(player, data, "distance", "reach=" + fmt(distance) + " max=" + fmt(range) + " ping=" + com.example.combatguard.check.ViolationManager.ping(player, data),
                    1.0 + Math.min((distance - range) * 2.0, 4.0));
            }
            return mitigate() && distance > range + opt("cancelMargin");
        }
        st.buffer = Math.max(0.0, st.buffer - 0.2);
        return false;
    }
}
