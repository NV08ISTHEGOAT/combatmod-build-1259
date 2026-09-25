package com.example.combatguard.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardMathTest {
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @Test
    void topFaceOnlyVisibleFromAbove() {
        assertTrue(GuardMath.canSeeFace(new Vec3d(0.5, 66.62, 0.5), POS, Direction.UP, 0.05));
        assertFalse(GuardMath.canSeeFace(new Vec3d(0.5, 64.5, 3.0), POS, Direction.UP, 0.05));
    }

    @Test
    void sideFaceNeedsEyeOnItsSide() {
        // Bridging backwards off the north edge: the eye is past the edge, so the north face is visible.
        assertTrue(GuardMath.canSeeFace(new Vec3d(0.5, 66.2, -0.25), POS, Direction.NORTH, 0.05));
        // Standing on top of the block, the north face is behind the block's own plane.
        assertFalse(GuardMath.canSeeFace(new Vec3d(0.5, 66.2, 0.5), POS, Direction.NORTH, 0.05));
        assertTrue(GuardMath.canSeeFace(new Vec3d(2.0, 65.0, 0.5), POS, Direction.EAST, 0.05));
        assertFalse(GuardMath.canSeeFace(new Vec3d(-1.0, 65.0, 0.5), POS, Direction.EAST, 0.05));
    }

    @Test
    void hitVectorOnFacePlane() {
        assertEquals(0.0, GuardMath.hitVectorError(new Vec3d(0.3, 65.0, 0.7), POS, Direction.UP), 1e-9);
        assertEquals(0.5, GuardMath.hitVectorError(new Vec3d(0.5, 64.5, 0.5), POS, Direction.UP), 1e-9);
    }

    @Test
    void lookVectorMatchesVanillaConventions() {
        Vec3d south = GuardMath.lookVector(0.0F, 0.0F);
        assertEquals(1.0, south.z, 1e-6);
        Vec3d down = GuardMath.lookVector(90.0F, 0.0F);
        assertEquals(-1.0, down.y, 1e-6);
        Vec3d west = GuardMath.lookVector(0.0F, 90.0F);
        assertEquals(-1.0, west.x, 1e-6);
    }

    @Test
    void rayHitsBoxInFront() {
        Box box = new Box(-0.3, 64.0, 2.7, 0.3, 65.8, 3.3);
        Vec3d eye = new Vec3d(0.0, 65.62, 0.0);
        assertTrue(GuardMath.rayHits(eye, GuardMath.lookVector(0.0F, 0.0F), 4.0, box));
        assertFalse(GuardMath.rayHits(eye, GuardMath.lookVector(0.0F, 180.0F), 4.0, box));
        assertFalse(GuardMath.rayHits(eye, GuardMath.lookVector(0.0F, 0.0F), 2.0, box));
    }

    @Test
    void distanceToBox() {
        Box box = new Box(2.0, 0.0, 0.0, 3.0, 1.0, 1.0);
        assertEquals(2.0, GuardMath.distanceToBox(new Vec3d(0.0, 0.5, 0.5), box), 1e-9);
        assertEquals(0.0, GuardMath.distanceToBox(new Vec3d(2.5, 0.5, 0.5), box), 1e-9);
    }

    @Test
    void statistics() {
        List<Integer> constant = List.of(2, 2, 2, 2, 2, 2);
        assertEquals(2.0, GuardMath.mean(constant), 1e-9);
        assertEquals(0.0, GuardMath.standardDeviation(constant), 1e-9);
        List<Integer> human = List.of(1, 2, 3, 2, 1, 3, 2, 2);
        assertTrue(GuardMath.standardDeviation(human) > 0.5);
    }
}
