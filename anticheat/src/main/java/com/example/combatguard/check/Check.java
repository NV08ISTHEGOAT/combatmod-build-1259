package com.example.combatguard.check;

import com.example.combatguard.CombatGuard;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for every check. {@code S} is the per-player state the check keeps (use {@link Void} for none).
 * A check has one id and several flag types, e.g. Reach/distance. The id is unique across the registry.
 */
public abstract class Check<S> {
    public enum Category { COMBAT, MOVEMENT, PACKET, INVENTORY, WORLD, CLIENT }

    private final String id;
    private final Category category;
    private final String description;
    private final boolean lagSensitive;
    private final GuardConfig.CheckSettings defaults;
    private final Map<String, Double> defaultOptions = new LinkedHashMap<>();

    /**
     * @param lagSensitive flags are dropped while the server runs below the configured TPS, because the check
     *                     relies on server timing (lag compensation, server-side world state)
     */
    protected Check(String id, Category category, String description, boolean lagSensitive,
                    double alertVl, double kickVl, double decayPerSecond) {
        this.id = id;
        this.category = category;
        this.description = description;
        this.lagSensitive = lagSensitive;
        this.defaults = new GuardConfig.CheckSettings(alertVl, kickVl, decayPerSecond);
    }

    /** Declares a tunable threshold. Call from the constructor. */
    protected final void option(String key, double defaultValue) {
        defaultOptions.put(key, defaultValue);
    }

    public final String id() {
        return id;
    }

    public final Category category() {
        return category;
    }

    public final String description() {
        return description;
    }

    public final boolean lagSensitive() {
        return lagSensitive;
    }

    public GuardConfig.CheckSettings defaults() {
        return defaults;
    }

    public Map<String, Double> defaultOptions() {
        return defaultOptions;
    }

    public S newState() {
        return null;
    }

    protected final S state(PlayerData data) {
        return data.state(this);
    }

    public final GuardConfig.CheckSettings settings() {
        GuardConfig.CheckSettings settings = CombatGuard.config().check(id);
        return settings != null ? settings : defaults;
    }

    public final boolean enabled() {
        return settings().enabled;
    }

    protected final boolean mitigate() {
        return settings().mitigate;
    }

    protected final double opt(String key) {
        Double value = settings().options.get(key);
        if (value == null) {
            value = defaultOptions.get(key);
        }
        return value == null ? 0.0 : value;
    }

    protected final void flag(ServerPlayerEntity player, PlayerData data, String type, String info, double weight) {
        ViolationManager.flag(player, data, this, type, info, weight);
    }
}
