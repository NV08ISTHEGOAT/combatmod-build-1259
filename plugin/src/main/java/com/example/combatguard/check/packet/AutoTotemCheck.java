package com.example.combatguard.check.packet;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * When a totem pops, a ping follows the pop packet; its pong marks the client tick at which the client saw the
 * pop. An offhand action within a tick of that which leaves a new totem in the offhand is faster than a human can
 * react (about 150 ms at best).
 */
public final class AutoTotemCheck extends Check<AutoTotemCheck.State> {
    public static final class State {
        double buffer;
    }

    public AutoTotemCheck() {
        super("AutoTotem", Category.INVENTORY, "Re-equipping a totem faster than a human can react (AutoTotem)", false, 1.0, 0.0, 0.02);
        option("maxTicks", 1.0);
        option("buffer", 1.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    /** Called at the end of the server tick after an offhand-changing action (swap key or inventory click). */
    public void onOffhandAction(Player player, PlayerData data, int actionClientTick) {
        if (!enabled()) {
            return;
        }
        int ticks = actionClientTick - data.totemSeenClientTick;
        if (ticks < 0 || ticks > 20 || player.getInventory().getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) {
            return;
        }
        State st = state(data);
        if (ticks <= opt("maxTicks")) {
            st.buffer += 1.0;
            if (st.buffer > opt("buffer")) {
                flag(player, data, "reaction", "new totem " + ticks + " tick(s) after the pop", 2.0);
            }
        } else {
            st.buffer = Math.max(0.0, st.buffer - 0.5);
        }
        data.totemSeenClientTick = -1000;
    }
}
