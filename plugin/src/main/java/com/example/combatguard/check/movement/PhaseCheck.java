package com.example.combatguard.check.movement;

import com.example.combatguard.check.Check;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.world.WorldQuery;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Moves whose path goes through a solid block (Phase, VClip, NoClip). Vanilla ignores vertical errors, so a clip
 * straight through a floor is accepted. Only moves longer than minDistance are traced, which keeps it cheap.
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
        if (distance < opt("minDistance") || WorldQuery.hasCollision(c.world, c.boxFrom.expand(-0.01))) {
            return;
        }
        double half = c.boxFrom.height() / 2.0;
        Vec3 start = c.from.add(0.0, half, 0.0);
        Vec3 end = c.to.add(0.0, half, 0.0);
        if (WorldQuery.blocked(c.world, start, end)) {
            flag(c.player, c.data, c.dy * c.dy > c.dx * c.dx + c.dz * c.dz ? "vertical" : "horizontal",
                "moved " + fmt(distance) + " blocks through a solid block", 2.0);
            if (mitigate()) {
                c.setback = true;
            }
        }
    }
}
