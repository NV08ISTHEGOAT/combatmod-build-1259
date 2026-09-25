package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.Exemptions;
import net.minecraft.entity.projectile.FireworkRocketEntity;

import java.util.Locale;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Elytra flight. Vanilla gliding can only trade height for speed; rockets are the only thing that adds energy.
 * <ul>
 *     <li>speed: faster than a vanilla dive can reach;</li>
 *     <li>hover: staying in the air at walking pace without losing height, which stalls a real elytra;</li>
 *     <li>energy: gaining speed and height together over a second without a rocket.</li>
 * </ul>
 */
public final class ElytraCheck extends Check<ElytraCheck.State> {
    public static final class State {
        int hoverTicks;
        int windowTicks;
        double windowStartEnergy = Double.NaN;
        int lastRocketTick = -1000;
        double speedBuffer;
    }

    public ElytraCheck() {
        super("Elytra", Category.MOVEMENT, "Elytra speed, hover and energy that vanilla gliding cannot produce (ElytraFly)", false, 1.0, 0.0, 0.2);
        option("maxSpeed", 4.2);
        option("hoverTicks", 30.0);
        option("energyWindow", 20.0);
        option("maxEnergyGain", 0.5);
    }

    @Override
    public State newState() {
        return new State();
    }

    private boolean rocketActive(MoveContext c, State st) {
        if (c.data.serverTicks - st.lastRocketTick < 40) {
            return true;
        }
        boolean active = !c.world.getEntitiesByClass(FireworkRocketEntity.class, c.boxTo.expand(4.0),
            rocket -> rocket.getOwner() == c.player).isEmpty();
        if (active) {
            st.lastRocketTick = c.data.serverTicks;
        }
        return active;
    }

    public void process(MoveContext c) {
        if (!enabled()) {
            return;
        }
        State st = state(c.data);
        if (!c.player.isGliding() || c.player.hasVehicle() || c.player.isInTeleportationState() || c.knockback != null
            || Exemptions.movement(c.player, c.data) || c.player.isUsingRiptide() || c.specialBlocks() || c.ticks != 1) {
            st.hoverTicks = 0;
            st.windowTicks = 0;
            st.windowStartEnergy = Double.NaN;
            return;
        }
        double speed = Math.sqrt(c.dx * c.dx + c.dy * c.dy + c.dz * c.dz);

        if (speed > opt("maxSpeed")) {
            st.speedBuffer += 1.0;
            if (st.speedBuffer > 3.0) {
                flag(c.player, c.data, "speed", "glide speed " + fmt(speed) + " max=" + fmt(opt("maxSpeed")), 1.0);
                if (mitigate()) {
                    c.setback = true;
                }
            }
        } else {
            st.speedBuffer = Math.max(0.0, st.speedBuffer - 0.25);
        }

        if (c.horizontal < 0.35 && c.dy >= -0.02 && !rocketActive(c, st)) {
            if (++st.hoverTicks > opt("hoverTicks")) {
                flag(c.player, c.data, "hover", "gliding in place for " + st.hoverTicks + " ticks", 1.0);
                st.hoverTicks = 0;
            }
        } else {
            st.hoverTicks = 0;
        }

        // Kinetic plus potential energy per unit mass, in vanilla units (blocks and ticks, gravity 0.08).
        double energy = 0.5 * speed * speed + c.player.getFinalGravity() * c.to.y;
        if (Double.isNaN(st.windowStartEnergy)) {
            st.windowStartEnergy = energy;
            st.windowTicks = 0;
        } else if (++st.windowTicks >= opt("energyWindow")) {
            double gain = energy - st.windowStartEnergy;
            if (gain > opt("maxEnergyGain") && !rocketActive(c, st)) {
                flag(c.player, c.data, "energy", String.format(Locale.ROOT, "gained %.2f energy in %d ticks without a rocket", gain, st.windowTicks), 1.0);
            }
            st.windowStartEnergy = energy;
            st.windowTicks = 0;
        }
    }
}
