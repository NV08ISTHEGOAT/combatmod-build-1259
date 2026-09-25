package com.example.combatguard.check.movement;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.GuardMath;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * The client sends one ClientTickEnd per tick, so a vanilla client cannot average more than one every 50 ms.
 * Packets are timestamped on the network thread; a balance absorbs jitter (a delayed burst only pays back the
 * time lost waiting).
 */
public final class TimerCheck extends Check<TimerCheck.State> {
    public static final class State {
        long lastNanos;
        double balanceMs;
        int warmup;
    }

    public TimerCheck() {
        super("Timer", Category.MOVEMENT, "Client ticking faster than 20 ticks per second (Timer)", false, 1.0, 25.0, 0.05);
        option("maxBalanceMs", 150.0);
        option("minBalanceMs", -1500.0);
        option("drift", 0.002);
    }

    @Override
    public State newState() {
        return new State();
    }

    /** Network thread. */
    public void onTickEndNetty(PlayerData data, long nanos) {
        if (!enabled()) {
            return;
        }
        State st = state(data);
        double excess;
        synchronized (st) {
            if (st.lastNanos == 0L) {
                st.lastNanos = nanos;
                return;
            }
            double elapsed = (nanos - st.lastNanos) / 1_000_000.0;
            st.lastNanos = nanos;
            st.balanceMs = GuardMath.advanceTimerBalance(st.balanceMs, elapsed, opt("drift"), opt("minBalanceMs"));
            if (st.warmup < 100) {
                st.warmup++;
                st.balanceMs = Math.min(st.balanceMs, 0.0);
                return;
            }
            if (st.balanceMs <= opt("maxBalanceMs")) {
                return;
            }
            excess = st.balanceMs;
            st.balanceMs = 0.0;
        }
        Bukkit.getScheduler().runTask(CombatGuardPlugin.instance(), () -> {
            Player player = Bukkit.getPlayer(data.uuid);
            if (player != null) {
                flag(player, data, "balance", String.format(Locale.ROOT, "+%.0fms ahead", excess), 1.0);
            }
        });
    }
}
