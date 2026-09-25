package com.example.combatguard.data;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {
    private static final Map<UUID, PlayerData> DATA = new ConcurrentHashMap<>();

    private PlayerDataManager() {
    }

    public static PlayerData get(ServerPlayerEntity player) {
        return player == null ? null : DATA.get(player.getUuid());
    }

    public static PlayerData get(UUID uuid) {
        return DATA.get(uuid);
    }

    public static PlayerData create(ServerPlayerEntity player) {
        PlayerData data = new PlayerData(player.getUuid(), player.getGameProfile().name());
        DATA.put(player.getUuid(), data);
        return data;
    }

    public static void remove(ServerPlayerEntity player) {
        DATA.remove(player.getUuid());
    }

    public static Collection<PlayerData> all() {
        return DATA.values();
    }

    public static void clear() {
        DATA.clear();
    }
}
