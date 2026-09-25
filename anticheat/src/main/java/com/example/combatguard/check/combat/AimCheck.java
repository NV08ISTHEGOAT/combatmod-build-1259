package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/**
 * Rotation analysis, one sample per client tick. It does not judge how good someone's aim is, only rotations a
 * mouse does not produce:
 * <ul>
 *     <li>pattern: the same non-trivial yaw step many ticks in a row (scripted, perfectly smooth aim);</li>
 *     <li>snap: a large turn in an attack tick that is undone right after (silent aim that sends a fake
 *     rotation for the hit and then the real camera rotation).</li>
 * </ul>
 */
public final class AimCheck extends Check<AimCheck.State> {
    public static final class State {
        boolean initialised;
        float lastYaw;
        float lastDeltaYaw;
        int repeatRun;
        float snapDelta;
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

    public void onTickEnd(ServerPlayerEntity player, PlayerData data, boolean attacked) {
        if (!enabled() || player.hasVehicle()) {
            return;
        }
        State st = state(data);
        float yaw = player.getYaw();
        if (!st.initialised) {
            st.initialised = true;
            st.lastYaw = yaw;
            return;
        }
        float delta = MathHelper.wrapDegrees(yaw - st.lastYaw);
        st.lastYaw = yaw;

        if (Math.abs(delta) >= opt("minStep") && Math.abs(delta - st.lastDeltaYaw) < 1.0E-4F) {
            if (++st.repeatRun >= opt("repeatRun")) {
                flag(player, data, "pattern", String.format(Locale.ROOT, "yaw moved exactly %.3f° for %d ticks", delta, st.repeatRun), 1.0);
                st.repeatRun = 0;
            }
        } else {
            st.repeatRun = 0;
        }
        st.lastDeltaYaw = delta;

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
