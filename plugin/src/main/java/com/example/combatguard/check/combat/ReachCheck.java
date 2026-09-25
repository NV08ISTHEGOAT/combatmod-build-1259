package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.Vec3;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.List;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Attack distance, measured from the attacker's eye to the closest position the target had during the attacker's
 * latency window. Vanilla only rejects hits 3 blocks past the range, so 3 to 6 block reach works without this.
 */
public final class ReachCheck extends Check<ReachCheck.State> {
    public static final class State {
        double buffer;
    }

    public ReachCheck() {
        super("Reach", Category.COMBAT, "Hitting entities from further than the attack range (Reach)", true, 2.0, 20.0, 0.1);
        option("tolerance", 0.1);
        option("buffer", 1.0);
        option("cancelMargin", 0.3);
    }

    @Override
    public State newState() {
        return new State();
    }

    public static double distance(List<Vec3> eyes, List<AABB> boxes) {
        double distance = Double.MAX_VALUE;
        for (Vec3 eye : eyes) {
            for (AABB box : boxes) {
                distance = Math.min(distance, box.distanceTo(eye));
            }
        }
        return distance;
    }

    /** Network thread: should this attack be dropped before the server handles it? */
    public boolean shouldCancel(List<Vec3> eyes, List<AABB> boxes, double range) {
        return enabled() && mitigate() && !boxes.isEmpty() && distance(eyes, boxes) > range + opt("cancelMargin");
    }

    public void onAttack(Player player, PlayerData data, List<Vec3> eyes, List<AABB> boxes, double range, int ping) {
        if (!enabled() || boxes.isEmpty() || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        State st = state(data);
        double distance = distance(eyes, boxes);
        if (distance > range + opt("tolerance")) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(player, data, "distance", "reach=" + fmt(distance) + " max=" + fmt(range) + " ping=" + ping,
                    1.0 + Math.min((distance - range) * 2.0, 4.0));
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.2);
        }
    }
}
