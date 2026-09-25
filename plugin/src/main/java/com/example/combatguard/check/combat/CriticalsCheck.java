package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Criticals cheats fake a fall with tiny hops or extra position packets right before hitting. Vanilla attacks
 * before it moves in a tick, so a position packet ahead of the hit in the same tick is already a fake.
 */
public final class CriticalsCheck extends Check<Void> {
    public CriticalsCheck() {
        super("Criticals", Category.COMBAT, "Faked falls to force critical hits (Criticals)", false, 1.0, 0.0, 0.1);
        option("windowTicks", 10.0);
    }

    public void onAttack(Player player, PlayerData data) {
        if (!enabled() || data.move.lastOnGround) {
            return;
        }
        boolean lowHop = data.clientTick - data.move.lastLowJumpTick <= opt("windowTicks");
        boolean extraMoves = data.tick.movePackets > 0;
        if (lowHop || extraMoves) {
            flag(player, data, lowHop ? "hop" : "packet", "critical hit after a fake fall", 1.0);
        }
    }
}
