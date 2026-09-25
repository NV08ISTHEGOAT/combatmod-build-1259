package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

/**
 * Rules the vanilla client always follows when attacking:
 * multi (one crosshair target per tick), noswing (every attack is followed by a swing in the same tick),
 * autoblock (attack clicks are discarded while an item is in use), container (no attacks with a container open).
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

    public void onAttack(Player player, PlayerData data, int targetId, int ping, boolean cancelledMulti) {
        if (!enabled()) {
            return;
        }
        for (int id : data.tick.attackedIds) {
            if (id != targetId) {
                flag(player, data, "multi", "hit " + (data.tick.attackedIds.size() + 1) + " targets in one tick", 2.0);
                return;
            }
        }
        if (cancelledMulti) {
            return;
        }
        // Use started by the stream at least 3 client ticks ago, not released, and the server agrees it is in use.
        if (data.usingItem && data.clientTick - data.useItemTick >= 3 && player.isHandRaised()
            && player.getActiveItemUsedTime() >= 2 && player.getActiveItemRemainingTime() > 2) {
            flag(player, data, "autoblock", "attacked while using " + player.getActiveItem().getType().getKey().getKey(), 1.0);
            return;
        }
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        if (top != InventoryType.CRAFTING && top != InventoryType.CREATIVE
            && System.currentTimeMillis() - data.containerOpenedAtMs > ping + 250L) {
            flag(player, data, "container", "attacked with a container open", 1.0);
        }
    }

    public void onTickEnd(Player player, PlayerData data) {
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
