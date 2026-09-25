package com.example.combatguard.check.packet;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Replacing a used totem. When a totem pops, a ping is sent right after the pop packet; its pong marks the client
 * tick at which the client saw the pop. Moving a new totem to the offhand within a tick of that is faster than a
 * human can react (about 150 ms at best), whether through the inventory or the swap key.
 */
public final class AutoTotemCheck extends Check<AutoTotemCheck.State> {
    public static final class State {
        double buffer;
    }

    public AutoTotemCheck() {
        super("AutoTotem", Category.INVENTORY, "Re-equipping a totem faster than a human can react", false, 1.0, 0.0, 0.02);
        option("maxTicks", 1.0);
        option("buffer", 1.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onTotemEquipped(ServerPlayerEntity player, PlayerData data, String how) {
        if (!enabled()) {
            return;
        }
        int ticks = data.clientTick - data.totemSeenClientTick;
        if (ticks < 0 || ticks > 20) {
            return;
        }
        State st = state(data);
        if (ticks <= opt("maxTicks")) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(player, data, "reaction", "new totem " + ticks + " tick(s) after the pop via " + how, 2.0);
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.5);
        }
        data.totemSeenClientTick = -1000;
    }
}
