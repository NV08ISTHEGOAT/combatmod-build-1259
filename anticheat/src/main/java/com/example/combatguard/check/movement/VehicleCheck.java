package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Vehicle movement is decided by the client for boats, horses and similar. Vanilla checks speed and collisions
 * but not whether a boat or horse keeps floating in the air. A gravity-affected vehicle that stays airborne
 * without descending for longer than any jump lasts is flying (BoatFly, VehicleFly, EntityFly).
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
        if (vehicle.hasNoGravity()) {
            return false;
        }
        if (vehicle instanceof LivingEntity living && (living.hasStatusEffect(StatusEffects.LEVITATION)
            || living.hasStatusEffect(StatusEffects.SLOW_FALLING))) {
            return false;
        }
        return vehicle instanceof AbstractBoatEntity || vehicle instanceof AbstractMinecartEntity || vehicle instanceof AbstractHorseEntity;
    }

    public void onVehicleMove(ServerPlayerEntity player, PlayerData data, Entity vehicle, Vec3d to) {
        if (!enabled()) {
            return;
        }
        State st = state(data);
        if (!supportsCheck(vehicle) || vehicle.isTouchingWater() || vehicle.isInLava()) {
            st.floatingTicks = 0;
            return;
        }
        double dy = to.y - vehicle.getY();
        Box box = vehicle.getBoundingBox().offset(to.x - vehicle.getX(), dy, to.z - vehicle.getZ());
        boolean supported = !player.getEntityWorld().isSpaceEmpty(vehicle, box.stretch(0.0, -0.2, 0.0))
            || !player.getEntityWorld().getBlockState(vehicle.getBlockPos().down()).getFluidState().isEmpty();
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
