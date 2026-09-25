package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.AABB;
import com.example.combatguard.world.WorldQuery;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Boats, minecarts and horses move where the client says. A gravity-affected vehicle that stays in the air without
 * descending for longer than any jump lasts is flying (BoatFly, VehicleFly).
 */
public final class VehicleCheck extends Check<VehicleCheck.State> {
    public static final class State {
        int floatingTicks;
    }

    public VehicleCheck() {
        super("Vehicle", Category.MOVEMENT, "Vehicles that float or climb in the air (BoatFly, VehicleFly)", true, 1.0, 0.0, 0.2);
        option("maxFloatingTicks", 20.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    private static boolean supportsCheck(Entity vehicle) {
        if (!vehicle.hasGravity()) {
            return false;
        }
        if (vehicle instanceof LivingEntity living && (living.hasPotionEffect(PotionEffectType.LEVITATION)
            || living.hasPotionEffect(PotionEffectType.SLOW_FALLING))) {
            return false;
        }
        return vehicle instanceof Boat || vehicle instanceof Minecart || vehicle instanceof AbstractHorse;
    }

    public void onVehicleMove(Player player, PlayerData data, Entity vehicle, double x, double y, double z) {
        if (!enabled()) {
            return;
        }
        State st = state(data);
        World world = vehicle.getWorld();
        Material below = world.getType((int) Math.floor(x), (int) Math.floor(y - 0.2), (int) Math.floor(z));
        if (!supportsCheck(vehicle) || vehicle.isInWater() || below == Material.WATER || below == Material.LAVA) {
            st.floatingTicks = 0;
            return;
        }
        double dy = y - vehicle.getY();
        AABB box = WorldQuery.aabb(vehicle.getBoundingBox()).offset(x - vehicle.getX(), dy, z - vehicle.getZ());
        AABB below2 = box.stretch(0.0, -0.2, 0.0);
        boolean supported = WorldQuery.hasBlockCollision(world, below2) || WorldQuery.hasPlatformEntity(world, below2, vehicle);
        if (supported || dy < -0.03) {
            st.floatingTicks = 0;
            return;
        }
        if (++st.floatingTicks > opt("maxFloatingTicks")) {
            flag(player, data, "fly", "vehicle airborne without falling for " + st.floatingTicks + " ticks", 1.0);
            st.floatingTicks = 0;
        }
    }
}
