package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/**
 * Break speed. Vanilla finishes a block when progress reaches 1.0, but the server accepts it at 0.7, so FastBreak
 * cheats get up to 30% for free. Time is measured in client ticks (ClientTickEnd packets), so latency does not
 * matter, and the break speed is the fastest the server saw at start or finish (effects, tool, ground state).
 */
public final class FastBreakCheck extends Check<FastBreakCheck.State> {
    public static final class State {
        long pos = Long.MIN_VALUE;
        int startTick;
        float startDelta;
        double buffer;
    }

    public FastBreakCheck() {
        super("FastBreak", Category.WORLD, "Breaking blocks faster than the tool and effects allow", false, 1.0, 0.0, 0.1);
        option("tolerance", 0.1);
        option("buffer", 1.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onStart(ServerPlayerEntity player, PlayerData data, BlockPos pos, BlockState state) {
        State st = state(data);
        st.pos = pos.asLong();
        st.startTick = data.clientTick;
        st.startDelta = state.calcBlockBreakingDelta(player, player.getEntityWorld(), pos);
    }

    public void onAbort(PlayerData data) {
        state(data).pos = Long.MIN_VALUE;
    }

    public void onFinish(ServerPlayerEntity player, PlayerData data, BlockPos pos, BlockState state) {
        State st = state(data);
        if (st.pos != pos.asLong()) {
            return;
        }
        st.pos = Long.MIN_VALUE;
        if (!enabled() || player.isCreative() || state.isAir()) {
            return;
        }
        float delta = Math.max(st.startDelta, state.calcBlockBreakingDelta(player, player.getEntityWorld(), pos));
        if (delta <= 0.0F || delta >= 1.0F) {
            return;
        }
        // Progress is added in the start tick too, so vanilla finishes ceil(1/delta) - 1 ticks after starting.
        int required = (int) Math.ceil(1.0 / delta) - 1;
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
