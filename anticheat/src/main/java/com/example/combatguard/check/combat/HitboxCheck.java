package com.example.combatguard.check.combat;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.util.GuardMath;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Aim at the moment of the hit. The vanilla client computes the crosshair target with the rotation it then
 * sends in the same tick's movement packet, so at the end of the tick the server knows the exact look
 * direction used for the attack. KillAura without matching rotations, and hitbox expansion, miss the target.
 */
public final class HitboxCheck extends Check<HitboxCheck.State> {
    public static final class State {
        double buffer;
        double wallBuffer;
    }

    public HitboxCheck() {
        super("Hitbox", Category.COMBAT, "Attacking an entity the crosshair was not on, or through walls (KillAura, Hitboxes)", true, 2.0, 25.0, 0.1);
        option("expand", 0.1);
        option("buffer", 2.0);
        option("wallBuffer", 2.0);
    }

    /**
     * Attack through a wall: the vanilla crosshair stops at the first block, so at least one line from the eye to
     * the target has to be free of solid blocks. Tests a few points on the closest lag-compensated boxes and
     * stops at the first clear line, so a normal hit costs one raycast.
     */
    public void onAttack(ServerPlayerEntity player, PlayerData data, Vec3d eye, List<Box> boxes) {
        if (!enabled() || boxes.isEmpty() || player.isSpectator()) {
            return;
        }
        State st = state(data);
        List<Box> closest = new ArrayList<>(boxes);
        closest.sort(Comparator.comparingDouble(b -> b.squaredMagnitude(eye)));
        ServerWorld world = player.getEntityWorld();
        for (int i = 0; i < Math.min(3, closest.size()); i++) {
            Box box = closest.get(i);
            Vec3d nearest = new Vec3d(
                MathHelper.clamp(eye.x, box.minX, box.maxX),
                MathHelper.clamp(eye.y, box.minY, box.maxY),
                MathHelper.clamp(eye.z, box.minZ, box.maxZ));
            Vec3d[] points = {nearest, box.getCenter(), new Vec3d(box.getCenter().x, box.maxY - 0.1, box.getCenter().z),
                new Vec3d(box.getCenter().x, box.minY + 0.1, box.getCenter().z)};
            for (Vec3d point : points) {
                Vec3d towardsEye = eye.subtract(point);
                double length = towardsEye.length();
                Vec3d end = length > 0.1 ? point.add(towardsEye.multiply(0.05 / length)) : point;
                HitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, player));
                if (hit.getType() == HitResult.Type.MISS) {
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

    @Override
    public State newState() {
        return new State();
    }

    public void onTickEnd(ServerPlayerEntity player, PlayerData data) {
        if (!enabled() || data.tick.aims.isEmpty() || player.isSpectator()) {
            return;
        }
        State st = state(data);
        Vec3d look = GuardMath.lookVector(player.getPitch(), player.getYaw());
        for (PlayerData.PendingAim aim : data.tick.aims) {
            boolean hit = false;
            double bestAngle = 180.0;
            for (Vec3d eye : aim.eyes()) {
                for (Box box : aim.boxes()) {
                    Box expanded = box.expand(opt("expand"));
                    if (GuardMath.rayHits(eye, look, aim.range() + 1.0, expanded)) {
                        hit = true;
                        break;
                    }
                    bestAngle = Math.min(bestAngle, GuardMath.angleTo(eye, look, box));
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
}
