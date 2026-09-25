package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.GuardMath;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.world.WorldQuery;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * aim: the vanilla client picks its target with the rotation it sends in the same tick, so at the end of the tick
 * the look ray has to hit the target. wall: the crosshair stops at blocks, so some line from the eye to the target
 * must be free of solid blocks.
 */
public final class HitboxCheck extends Check<HitboxCheck.State> {
    public static final class State {
        double buffer;
        double wallBuffer;
    }

    public HitboxCheck() {
        super("Hitbox", Category.COMBAT, "Attacks the crosshair was not on, or through walls (KillAura, Hitboxes)", true, 2.0, 25.0, 0.1);
        option("expand", 0.1);
        option("buffer", 2.0);
        option("wallBuffer", 2.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onTickEnd(Player player, PlayerData data) {
        if (!enabled() || data.tick.aims.isEmpty() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        State st = state(data);
        Vec3 look = GuardMath.lookVector(data.pitch, data.yaw);
        for (PlayerData.PendingAim aim : data.tick.aims) {
            boolean hit = false;
            double bestAngle = 180.0;
            for (Vec3 eye : aim.eyes()) {
                for (AABB box : aim.boxes()) {
                    if (box.expand(opt("expand")).intersectsRay(eye, look, aim.range() + 1.0)) {
                        hit = true;
                        break;
                    }
                    bestAngle = Math.min(bestAngle, GuardMath.angleTo(eye, look, box.center()));
                }
                if (hit) {
                    break;
                }
            }
            if (hit) {
                st.buffer = Math.max(0.0, st.buffer - 0.25);
            } else {
                st.buffer += 1.0;
                if (st.buffer > opt("buffer")) {
                    flag(player, data, "aim", String.format(Locale.ROOT, "missed hitbox by %.1f°", bestAngle), 1.0);
                }
            }
        }
    }

    public void onAttack(Player player, PlayerData data, Vec3 eye, List<AABB> boxes) {
        if (!enabled() || boxes.isEmpty() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        State st = state(data);
        List<AABB> closest = new ArrayList<>(boxes);
        closest.sort(Comparator.comparingDouble(b -> b.distanceTo(eye)));
        for (int i = 0; i < Math.min(3, closest.size()); i++) {
            AABB box = closest.get(i);
            Vec3 center = box.center();
            Vec3[] points = {box.closestPoint(eye), center, new Vec3(center.x(), box.maxY() - 0.1, center.z()),
                new Vec3(center.x(), box.minY() + 0.1, center.z())};
            for (Vec3 point : points) {
                Vec3 towardsEye = eye.subtract(point);
                double length = towardsEye.length();
                Vec3 end = length > 0.1 ? point.add(towardsEye.multiply(0.05 / length)) : point;
                if (!WorldQuery.blocked(player.getWorld(), eye, end)) {
                    st.wallBuffer = Math.max(0.0, st.wallBuffer - 0.25);
                    return;
                }
            }
        }
        st.wallBuffer += 1.0;
        if (st.wallBuffer > opt("wallBuffer")) {
            flag(player, data, "wall", "hit an entity behind solid blocks", 1.5);
        }
    }
}
