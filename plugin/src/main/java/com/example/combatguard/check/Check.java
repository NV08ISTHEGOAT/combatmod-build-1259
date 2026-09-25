package com.example.combatguard.check;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Base class for every check. {@code S} is the per-player state (use {@link Void} for none). */
public abstract class Check<S> {
    public enum Category { COMBAT, MOVEMENT, PACKET, INVENTORY, WORLD, CLIENT }

    private final String id;
    private final Category category;
    private final String description;
    private final boolean lagSensitive;
    private final GuardConfig.CheckSettings defaults = new GuardConfig.CheckSettings();
    private final Map<String, Double> defaultOptions = new LinkedHashMap<>();

    /** @param lagSensitive drop flags while the server runs below min-tps (the check relies on server timing) */
    protected Check(String id, Category category, String description, boolean lagSensitive,
                    double alertVl, double kickVl, double decayPerSecond) {
        this.id = id;
        this.category = category;
        this.description = description;
        this.lagSensitive = lagSensitive;
        defaults.alertVl = alertVl;
        defaults.kickVl = kickVl;
        defaults.decayPerSecond = decayPerSecond;
    }

    protected final void option(String key, double value) {
        defaultOptions.put(key, value);
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

    public final GuardConfig.CheckSettings defaults() {
        return defaults;
    }

    public final Map<String, Double> defaultOptions() {
        return defaultOptions;
    }

    public S newState() {
        return null;
    }

    protected final S state(PlayerData data) {
        return data.state(this);
    }

    public final GuardConfig.CheckSettings settings() {
        GuardConfig config = CombatGuardPlugin.config();
        GuardConfig.CheckSettings settings = config == null ? null : config.check(id);
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

    protected final void flag(Player player, PlayerData data, String type, String info, double weight) {
        ViolationManager.flag(player, data, this, type, info, weight);
    }
}
