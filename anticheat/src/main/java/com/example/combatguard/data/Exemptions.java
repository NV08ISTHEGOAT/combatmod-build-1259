package com.example.combatguard.data;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Central place for "something legitimate just happened to this player" so checks do not each re-implement
 * teleport, respawn, world change, join and game mode grace periods.
 */
public final class Exemptions {
    /** Grace periods in server ticks. */
    public static final int JOIN = 60;
    public static final int RESPAWN = 40;
    public static final int WORLD_CHANGE = 60;
    public static final int GAME_MODE = 40;
    public static final int TELEPORT = 10;

    private Exemptions() {
    }

    /** Manual /cg exempt. Silences every check. */
    public static boolean manual(PlayerData data) {
        return data.exemptUntilMs > System.currentTimeMillis();
    }

    /** Movement physics cannot be trusted right now. */
    public static boolean movement(ServerPlayerEntity player, PlayerData data) {
        int now = data.serverTicks;
        return now < JOIN
            || now - data.lastRespawnTick < RESPAWN
            || now - data.lastWorldChangeTick < WORLD_CHANGE
            || now - data.lastGameModeChangeTick < GAME_MODE
            || now - data.lastTeleportTick < TELEPORT
            || player.isDead();
    }

    public static boolean combat(PlayerData data) {
        int now = data.serverTicks;
        return now < 20 || now - data.lastRespawnTick < 20 || now - data.lastWorldChangeTick < 20;
    }
}
