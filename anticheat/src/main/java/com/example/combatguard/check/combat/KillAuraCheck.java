package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Rules the vanilla client always follows when attacking:
 * <ul>
 *     <li>multi: the crosshair is on one entity per tick, so two targets in one tick is impossible;</li>
 *     <li>noswing: every attack is followed by an arm swing in the same tick;</li>
 *     <li>autoblock: while an item is in use (shield, food, bow) attack clicks are thrown away;</li>
 *     <li>container: no attacks while a container screen is open.</li>
 * </ul>
 */
public final class KillAuraCheck extends Check<KillAuraCheck.State> {
    public static final class State {
        double noSwingBuffer;
    }

    public KillAuraCheck() {
        super("KillAura", Category.COMBAT, "Attacks vanilla cannot send: several targets per tick, no swing, while blocking or in a container", false, 1.0, 15.0, 0.05);
        option("noSwingBuffer", 1.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    /** @return true when the attack should be cancelled */
    public boolean onAttack(ServerPlayerEntity player, PlayerData data, int targetId, int pingMs) {
        if (!enabled()) {
            return false;
        }
        if (player.isUsingItem() && player.getItemUseTime() >= 2 && player.getItemUseTimeLeft() > 2) {
            flag(player, data, "autoblock", "attacked while using " + player.getActiveItem().getItem(), 1.0);
            return mitigate();
        }
        if (player.currentScreenHandler != player.playerScreenHandler
            && System.currentTimeMillis() - data.screenOpenedAtMs > pingMs + 250L) {
            flag(player, data, "container", "attacked with a container open", 1.0);
            return mitigate();
        }
        var attacked = data.tick.attackedIds;
        for (int i = 0; i < attacked.size(); i++) {
            if (attacked.getInt(i) != targetId) {
                flag(player, data, "multi", "hit " + (attacked.size() + 1) + " targets in one tick", 2.0);
                return mitigate();
            }
        }
        return false;
    }

    public void onTickEnd(ServerPlayerEntity player, PlayerData data) {
        if (!enabled()) {
            return;
        }
        State st = state(data);
        int attacks = data.tick.attackedIds.size();
        if (attacks == 0) {
            return;
        }
        if (data.tick.swings < attacks) {
            st.noSwingBuffer += 1.0;
            if (st.noSwingBuffer > opt("noSwingBuffer")) {
                flag(player, data, "noswing", attacks + " attacks, " + data.tick.swings + " swings", 1.0);
            }
        } else {
            st.noSwingBuffer = Math.max(0.0, st.noSwingBuffer - 0.1);
        }
    }
}
