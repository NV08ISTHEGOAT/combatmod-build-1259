package com.example.combatguard.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {
    private static final Map<UUID, PlayerData> BY_UUID = new ConcurrentHashMap<>();
    private static final Map<Integer, PlayerData> BY_ENTITY = new ConcurrentHashMap<>();

    private PlayerDataManager() {
    }

    public static PlayerData get(Player player) {
        return player == null ? null : BY_UUID.get(player.getUniqueId());
    }

    public static PlayerData get(UUID uuid) {
        return uuid == null ? null : BY_UUID.get(uuid);
    }

    public static PlayerData byEntityId(int entityId) {
        return BY_ENTITY.get(entityId);
    }

    public static PlayerData create(Player player) {
        PlayerData data = new PlayerData(player.getUniqueId(), player.getName(), player.getEntityId());
        BY_UUID.put(data.uuid, data);
        BY_ENTITY.put(data.entityId, data);
        return data;
    }

    public static void remove(Player player) {
        PlayerData data = BY_UUID.remove(player.getUniqueId());
        if (data != null) {
            BY_ENTITY.remove(data.entityId);
        }
    }

    public static Collection<PlayerData> all() {
        return BY_UUID.values();
    }

    public static void clear() {
        BY_UUID.clear();
        BY_ENTITY.clear();
    }
}
