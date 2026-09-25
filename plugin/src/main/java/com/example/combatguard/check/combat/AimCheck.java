package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.GuardMath;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Rotation analysis, one sample per client tick. It never judges how good someone's aim is, only rotations a mouse
 * does not produce: pattern (the same yaw step many ticks in a row) and snap (a large turn for a hit that is undone
 * right after, typical of silent aim).
 */
public final class AimCheck extends Check<AimCheck.State> {
    public static final class State {
        boolean initialised;
        float lastYaw;
        double lastDelta;
        int repeatRun;
        double snapDelta;
        int snapTick = -1000;
        double snapBuffer;
    }

    public AimCheck() {
        super("Aim", Category.COMBAT, "Rotations a mouse does not produce (constant aim steps, silent-aim snaps)", false, 1.0, 0.0, 0.05);
        option("repeatRun", 12.0);
        option("minStep", 1.0);
        option("snapAngle", 45.0);
        option("snapBuffer", 2.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onTickEnd(Player player, PlayerData data, boolean attacked) {
        if (!enabled() || player.isInsideVehicle()) {
            return;
        }
        State st = state(data);
        float yaw = data.yaw;
        if (!st.initialised) {
            st.initialised = true;
            st.lastYaw = yaw;
            return;
        }
        double delta = GuardMath.wrapDegrees(yaw - st.lastYaw);
        st.lastYaw = yaw;
        if (Math.abs(delta) >= opt("minStep") && Math.abs(delta - st.lastDelta) < 1.0E-4) {
            if (++st.repeatRun >= opt("repeatRun")) {
                flag(player, data, "pattern", String.format(Locale.ROOT, "yaw moved exactly %.3f° for %d ticks", delta, st.repeatRun), 1.0);
                st.repeatRun = 0;
            }
        } else {
            st.repeatRun = 0;
        }
        st.lastDelta = delta;

        double snap = opt("snapAngle");
        if (data.clientTick - st.snapTick <= 2 && Math.abs(delta) >= snap && Math.signum(delta) != Math.signum(st.snapDelta)) {
            st.snapTick = -1000;
            st.snapBuffer += 1.0;
            if (st.snapBuffer > opt("snapBuffer")) {
                flag(player, data, "snap", String.format(Locale.ROOT, "turned %.0f° to hit and %.0f° back", st.snapDelta, delta), 1.0);
            }
        } else if (attacked && Math.abs(delta) >= snap) {
            st.snapTick = data.clientTick;
            st.snapDelta = delta;
        } else if (attacked) {
            st.snapBuffer = Math.max(0.0, st.snapBuffer - 0.1);
        }
    }
}
