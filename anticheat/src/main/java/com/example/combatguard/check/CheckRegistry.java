package com.example.combatguard.check;

import com.example.combatguard.check.combat.AimCheck;
import com.example.combatguard.check.combat.AutoClickerCheck;
import com.example.combatguard.check.combat.CriticalsCheck;
import com.example.combatguard.check.combat.HitboxCheck;
import com.example.combatguard.check.combat.KillAuraCheck;
import com.example.combatguard.check.combat.ReachCheck;
import com.example.combatguard.check.combat.VelocityCheck;
import com.example.combatguard.check.client.ClientIntegrityCheck;
import com.example.combatguard.check.movement.ClimbCheck;
import com.example.combatguard.check.movement.ElytraCheck;
import com.example.combatguard.check.movement.FlightCheck;
import com.example.combatguard.check.movement.GroundSpoofCheck;
import com.example.combatguard.check.movement.JumpCheck;
import com.example.combatguard.check.movement.PhaseCheck;
import com.example.combatguard.check.movement.SpeedCheck;
import com.example.combatguard.check.movement.SprintCheck;
import com.example.combatguard.check.movement.TimerCheck;
import com.example.combatguard.check.movement.VehicleCheck;
import com.example.combatguard.check.packet.AutoTotemCheck;
import com.example.combatguard.check.packet.BadPacketsCheck;
import com.example.combatguard.check.packet.InventoryCheck;
import com.example.combatguard.check.world.FastBreakCheck;
import com.example.combatguard.check.world.NukerCheck;
import com.example.combatguard.check.world.ScaffoldCheck;
import com.example.combatguard.check.world.XRayCheck;
import com.example.combatguard.config.GuardConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every check exists exactly once, here. Registering a second check with the same id fails at startup. */
public final class CheckRegistry {
    private static final Map<String, Check<?>> BY_ID = new LinkedHashMap<>();

    // Combat
    public static final ReachCheck REACH = register(new ReachCheck());
    public static final HitboxCheck HITBOX = register(new HitboxCheck());
    public static final KillAuraCheck KILL_AURA = register(new KillAuraCheck());
    public static final AimCheck AIM = register(new AimCheck());
    public static final AutoClickerCheck AUTO_CLICKER = register(new AutoClickerCheck());
    public static final CriticalsCheck CRITICALS = register(new CriticalsCheck());
    public static final VelocityCheck VELOCITY = register(new VelocityCheck());
    // Movement
    public static final SpeedCheck SPEED = register(new SpeedCheck());
    public static final FlightCheck FLIGHT = register(new FlightCheck());
    public static final JumpCheck JUMP = register(new JumpCheck());
    public static final GroundSpoofCheck GROUND_SPOOF = register(new GroundSpoofCheck());
    public static final ClimbCheck CLIMB = register(new ClimbCheck());
    public static final PhaseCheck PHASE = register(new PhaseCheck());
    public static final SprintCheck SPRINT = register(new SprintCheck());
    public static final TimerCheck TIMER = register(new TimerCheck());
    public static final ElytraCheck ELYTRA = register(new ElytraCheck());
    public static final VehicleCheck VEHICLE = register(new VehicleCheck());
    // World
    public static final ScaffoldCheck SCAFFOLD = register(new ScaffoldCheck());
    public static final NukerCheck NUKER = register(new NukerCheck());
    public static final FastBreakCheck FAST_BREAK = register(new FastBreakCheck());
    public static final XRayCheck XRAY = register(new XRayCheck());
    // Packets and inventory
    public static final BadPacketsCheck BAD_PACKETS = register(new BadPacketsCheck());
    public static final InventoryCheck INVENTORY = register(new InventoryCheck());
    public static final AutoTotemCheck AUTO_TOTEM = register(new AutoTotemCheck());
    // Client integrity
    public static final ClientIntegrityCheck CLIENT = register(new ClientIntegrityCheck());

    private CheckRegistry() {
    }

    private static <T extends Check<?>> T register(T check) {
        String key = check.id().toLowerCase(Locale.ROOT);
        if (BY_ID.containsKey(key)) {
            throw new IllegalStateException("Duplicate check id " + check.id());
        }
        BY_ID.put(key, check);
        return check;
    }

    public static List<Check<?>> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    public static Check<?> byId(String id) {
        return BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    /** Adds missing check sections and options to the config. */
    public static void applyDefaults(GuardConfig config) {
        for (Check<?> check : BY_ID.values()) {
            GuardConfig.CheckSettings defaults = new GuardConfig.CheckSettings(
                check.defaults().alertVl, check.defaults().kickVl, check.defaults().decayPerSecond);
            config.registerDefaults(check.id(), defaults, check.defaultOptions());
        }
    }
}
