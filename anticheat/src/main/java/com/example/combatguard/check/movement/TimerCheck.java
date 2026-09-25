package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Locale;
import java.util.UUID;

/**
 * The client sends one ClientTickEnd packet per tick, so a vanilla client can never send more than one every
 * 50 ms on average. Timer cheats speed the game loop up. Packets are timestamped on the network thread, and
 * a balance absorbs network jitter: a delayed burst only pays back time that was lost while waiting.
 */
public final class TimerCheck extends Check<TimerCheck.State> {
    public static final class State {
        long lastNanos;
        double balanceMs;
        int warmup;
    }

    public TimerCheck() {
        super("Timer", Category.MOVEMENT, "Client ticking faster than 20 ticks per second", false, 1.0, 25.0, 0.05);
        option("maxBalanceMs", 150.0);
        option("minBalanceMs", -1500.0);
        option("drift", 0.002);
    }

    @Override
    public State newState() {
        return new State();
    }

    /**
     * Each tick packet earns 50 ms, real time spends it. A small drift allowance covers clocks that run slightly
     * fast, and the lower bound stops a player from saving up time by lagging on purpose.
     */
    static double advance(double balanceMs, double elapsedMs, double drift, double minBalanceMs) {
        return Math.max(balanceMs + 50.0 - elapsedMs - 50.0 * drift, minBalanceMs);
    }

    /** Called on the network thread for every ClientTickEnd packet. */
    public void onTickEndNetty(MinecraftServer server, UUID uuid) {
        PlayerData data = PlayerDataManager.get(uuid);
        if (data == null || !enabled()) {
            return;
        }
        State st = state(data);
        double excess;
        synchronized (st) {
            long now = System.nanoTime();
            if (st.lastNanos == 0L) {
                st.lastNanos = now;
                return;
            }
            double elapsed = (now - st.lastNanos) / 1_000_000.0;
            st.lastNanos = now;
            st.balanceMs = advance(st.balanceMs, elapsed, opt("drift"), opt("minBalanceMs"));
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
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                flag(player, data, "balance", String.format(Locale.ROOT, "+%.0fms ahead", excess), 1.0);
            }
        });
    }

    public void reset(PlayerData data) {
        State st = state(data);
        synchronized (st) {
            st.lastNanos = 0L;
            st.balanceMs = 0.0;
            st.warmup = 0;
        }
    }
}
