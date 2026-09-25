package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import static com.example.combatguard.util.GuardMath.fmt;

/**
 * Moving through solid blocks. Vanilla rejects horizontal moves into walls, but it ignores vertical errors, so
 * a "VClip" straight down (or up) through a floor into empty space is accepted. The check traces the path the
 * player's body took; a solid collision box on that path means the move went through a block.
 * Only moves longer than {@code minDistance} are traced, which keeps it cheap: normal walking never gets there.
 */
public final class PhaseCheck extends Check<Void> {
    public PhaseCheck() {
        super("Phase", Category.MOVEMENT, "Moving through solid blocks (Phase, VClip, NoClip)", false, 1.0, 0.0, 0.2);
        option("minDistance", 0.5);
    }

    public void process(MoveContext c) {
        if (!enabled() || c.exempt || c.envFrom.pistons || c.envTo.pistons || c.envFrom.unloaded || c.envTo.unloaded) {
            return;
        }
        double distance = Math.sqrt(c.dx * c.dx + c.dy * c.dy + c.dz * c.dz);
        if (distance < opt("minDistance")) {
            return;
        }
        // Already stuck inside something (for example after a block was placed on the player): nothing to judge.
        if (!c.world.isSpaceEmpty(c.player, c.boxFrom.contract(0.01))) {
            return;
        }
        double half = (c.boxFrom.maxY - c.boxFrom.minY) / 2.0;
        Vec3d start = c.from.add(0.0, half, 0.0);
        Vec3d end = c.to.add(0.0, half, 0.0);
        HitResult hit = c.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, c.player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            flag(c.player, c.data, c.dy * c.dy > c.dx * c.dx + c.dz * c.dz ? "vertical" : "horizontal",
                "moved " + fmt(distance) + " blocks through a solid block", 2.0);
            if (mitigate()) {
                c.setback = true;
            }
        }
    }
}
