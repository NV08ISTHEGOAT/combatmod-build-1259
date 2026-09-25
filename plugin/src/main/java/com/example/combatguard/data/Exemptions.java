package com.example.combatguard.data;

import org.bukkit.entity.Player;

/** Central grace periods, so checks do not each re-implement teleport, respawn, world change and join handling. */
public final class Exemptions {
    public static final int JOIN = 60;
    public static final int RESPAWN = 40;
    public static final int WORLD_CHANGE = 60;
    public static final int GAME_MODE = 40;
    public static final int TELEPORT = 10;

    private Exemptions() {
    }

    public static boolean manual(PlayerData data) {
        return data.exemptUntilMs > System.currentTimeMillis();
    }

    public static boolean movement(Player player, PlayerData data) {
        int now = data.serverTicks;
        return now < JOIN
            || now - data.lastRespawnTick < RESPAWN
            || now - data.lastWorldChangeTick < WORLD_CHANGE
            || now - data.lastGameModeChangeTick < GAME_MODE
            || now - data.lastTeleportTick < TELEPORT
            || data.pendingTeleports > 0
            || player.isDead();
    }

    public static boolean combat(PlayerData data) {
        int now = data.serverTicks;
        return now < 20 || now - data.lastRespawnTick < 20 || now - data.lastWorldChangeTick < 20;
    }
}
