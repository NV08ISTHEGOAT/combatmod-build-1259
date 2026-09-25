package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Vanilla finishes a block when progress reaches 1.0, but the server accepts 0.7, so FastBreak gets up to 30% for
 * free. Time is counted in client ticks, and the break speed is the fastest seen at start or finish.
 */
public final class FastBreakCheck extends Check<FastBreakCheck.State> {
    public static final class State {
        long key = Long.MIN_VALUE;
        int startTick;
        float startSpeed;
        double buffer;
    }

    public FastBreakCheck() {
        super("FastBreak", Category.WORLD, "Breaking blocks faster than the tool and effects allow (FastBreak)", false, 1.0, 0.0, 0.1);
        option("tolerance", 0.1);
        option("buffer", 1.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public void onStart(Player player, PlayerData data, Block block) {
        State st = state(data);
        st.key = key(block.getX(), block.getY(), block.getZ());
        st.startTick = data.clientTick;
        st.startSpeed = block.getBreakSpeed(player);
    }

    public void onAbort(PlayerData data) {
        state(data).key = Long.MIN_VALUE;
    }

    public void onFinish(Player player, PlayerData data, Block block) {
        State st = state(data);
        if (st.key != key(block.getX(), block.getY(), block.getZ())) {
            return;
        }
        st.key = Long.MIN_VALUE;
        if (!enabled() || player.getGameMode() == GameMode.CREATIVE || block.getType().isAir()) {
            return;
        }
        float speed = Math.max(st.startSpeed, block.getBreakSpeed(player));
        if (speed <= 0.0F || speed >= 1.0F) {
            return;
        }
        int required = (int) Math.ceil(1.0 / speed) - 1;
        int taken = data.clientTick - st.startTick;
        int allowed = (int) Math.floor(required * (1.0 - opt("tolerance"))) - 1;
        if (taken < allowed) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(player, data, "speed", String.format(Locale.ROOT, "broke in %d ticks, needs %d", taken, required), 1.0);
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.5);
        }
    }
}
