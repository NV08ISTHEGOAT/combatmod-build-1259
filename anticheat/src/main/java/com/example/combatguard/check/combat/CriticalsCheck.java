package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Criticals cheats fake a fall with tiny hops (or extra position packets) right before hitting. The jump check
 * records those hops; hitting while in one is flagged and, when mitigating, the fall distance is cleared so the
 * hit is not critical.
 */
public final class CriticalsCheck extends Check<Void> {
    public CriticalsCheck() {
        super("Criticals", Category.COMBAT, "Faked falls to force critical hits", false, 1.0, 0.0, 0.1);
        option("windowTicks", 10.0);
    }

    public void onAttack(ServerPlayerEntity player, PlayerData data) {
        if (!enabled() || player.isOnGround() || player.fallDistance <= 0.0) {
            return;
        }
        boolean lowHop = data.clientTick - data.move.lastLowJumpTick <= opt("windowTicks");
        // Vanilla attacks before it moves in a tick, so position packets ahead of the hit are fake.
        boolean extraMoves = data.tick.movePackets > 0;
        if (lowHop || extraMoves) {
            flag(player, data, lowHop ? "hop" : "packet", "critical hit after a fake fall", 1.0);
            if (mitigate()) {
                player.fallDistance = 0.0;
            }
        }
    }
}
