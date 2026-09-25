package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.GuardMath;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Clicks are handled once per client tick, so they are measured in client ticks (ClientTickEnd packets), which
 * removes network jitter. Swings from mining or using blocks are ignored. High CPS alone is not enough for the
 * consistency type; it needs many samples with almost no variation.
 */
public final class AutoClickerCheck extends Check<AutoClickerCheck.State> {
    public static final class State {
        int lastClickTick = -1;
        final ArrayDeque<Integer> clickTicks = new ArrayDeque<>();
        final ArrayDeque<Integer> delays = new ArrayDeque<>();
        double cpsBuffer;
    }

    public AutoClickerCheck() {
        super("AutoClicker", Category.COMBAT, "Clicking faster or more evenly than a human can (AutoClicker)", false, 1.0, 0.0, 0.05);
        option("maxCps", 22.0);
        option("samples", 40.0);
        option("minCps", 8.0);
        option("minDeviation", 0.25);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onTickEnd(Player player, PlayerData data) {
        if (!enabled()) {
            return;
        }
        State st = state(data);
        int tick = data.clientTick;
        if (data.tick.usedOrDug || data.digging) {
            st.lastClickTick = -1;
            return;
        }
        int swings = data.tick.swings;
        if (swings == 0) {
            return;
        }
        for (int i = 0; i < swings; i++) {
            st.clickTicks.addLast(tick);
            if (st.lastClickTick >= 0) {
                int delay = tick - st.lastClickTick;
                if (delay <= 8) {
                    st.delays.addLast(delay);
                }
            }
            st.lastClickTick = tick;
        }
        while (!st.clickTicks.isEmpty() && st.clickTicks.peekFirst() <= tick - 20) {
            st.clickTicks.removeFirst();
        }
        int cps = st.clickTicks.size();
        if (cps > opt("maxCps")) {
            st.cpsBuffer += 1.0;
            if (st.cpsBuffer > 2.0) {
                flag(player, data, "cps", cps + " clicks per second", 1.0);
            }
        } else {
            st.cpsBuffer = Math.max(0.0, st.cpsBuffer - 0.05);
        }
        if (st.delays.size() >= opt("samples")) {
            double mean = GuardMath.mean(st.delays);
            double deviation = GuardMath.standardDeviation(st.delays);
            double averageCps = mean > 0 ? 20.0 / mean : 20.0;
            if (averageCps >= opt("minCps") && deviation < opt("minDeviation")) {
                flag(player, data, "consistency", String.format(Locale.ROOT, "deviation=%.3f ticks at %.1f cps", deviation, averageCps), 2.0);
            }
            st.delays.clear();
        }
    }
}
