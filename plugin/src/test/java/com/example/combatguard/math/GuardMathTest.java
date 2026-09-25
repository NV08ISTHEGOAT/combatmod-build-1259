package com.example.combatguard.math;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardMathTest {
    @Test
    void topFaceOnlyVisibleFromAbove() {
        assertTrue(GuardMath.canSeeFace(new Vec3(0.5, 66.62, 0.5), 0, 64, 0, GuardMath.Face.UP, 0.05));
        assertFalse(GuardMath.canSeeFace(new Vec3(0.5, 64.5, 3.0), 0, 64, 0, GuardMath.Face.UP, 0.05));
    }

    @Test
    void sideFaceNeedsEyeOnItsSide() {
        // Bridging backwards off the north edge: the eye is past the edge, so the north face is visible.
        assertTrue(GuardMath.canSeeFace(new Vec3(0.5, 66.2, -0.25), 0, 64, 0, GuardMath.Face.NORTH, 0.05));
        assertFalse(GuardMath.canSeeFace(new Vec3(0.5, 66.2, 0.5), 0, 64, 0, GuardMath.Face.NORTH, 0.05));
        assertTrue(GuardMath.canSeeFace(new Vec3(2.0, 65.0, 0.5), 0, 64, 0, GuardMath.Face.EAST, 0.05));
        assertFalse(GuardMath.canSeeFace(new Vec3(-1.0, 65.0, 0.5), 0, 64, 0, GuardMath.Face.EAST, 0.05));
    }

    @Test
    void hitVectorOnFacePlane() {
        assertEquals(0.0, GuardMath.hitVectorError(new Vec3(0.3, 65.0, 0.7), 0, 64, 0, GuardMath.Face.UP), 1e-9);
        assertEquals(0.5, GuardMath.hitVectorError(new Vec3(0.5, 64.5, 0.5), 0, 64, 0, GuardMath.Face.UP), 1e-9);
    }

    @Test
    void lookVectorMatchesVanilla() {
        assertEquals(1.0, GuardMath.lookVector(0.0F, 0.0F).z(), 1e-6);
        assertEquals(-1.0, GuardMath.lookVector(90.0F, 0.0F).y(), 1e-6);
        assertEquals(-1.0, GuardMath.lookVector(0.0F, 90.0F).x(), 1e-6);
    }

    @Test
    void wrapDegrees() {
        assertEquals(-170.0, GuardMath.wrapDegrees(190.0), 1e-9);
        assertEquals(170.0, GuardMath.wrapDegrees(-190.0), 1e-9);
        assertEquals(10.0, GuardMath.wrapDegrees(370.0), 1e-9);
    }

    @Test
    void rayHitsBoxInFront() {
        AABB box = new AABB(-0.3, 64.0, 2.7, 0.3, 65.8, 3.3);
        Vec3 eye = new Vec3(0.0, 65.62, 0.0);
        assertTrue(box.intersectsRay(eye, GuardMath.lookVector(0.0F, 0.0F), 4.0));
        assertFalse(box.intersectsRay(eye, GuardMath.lookVector(0.0F, 180.0F), 4.0));
        assertFalse(box.intersectsRay(eye, GuardMath.lookVector(0.0F, 0.0F), 2.0));
        assertFalse(box.intersectsRay(eye, GuardMath.lookVector(0.0F, 30.0F), 4.0));
    }

    @Test
    void boxDistanceAndStretch() {
        AABB box = new AABB(2.0, 0.0, 0.0, 3.0, 1.0, 1.0);
        assertEquals(2.0, box.distanceTo(new Vec3(0.0, 0.5, 0.5)), 1e-9);
        assertEquals(0.0, box.distanceTo(new Vec3(2.5, 0.5, 0.5)), 1e-9);
        AABB stretched = box.stretch(0.0, -0.5, 0.0);
        assertEquals(-0.5, stretched.minY(), 1e-9);
        assertEquals(1.0, stretched.maxY(), 1e-9);
    }

    @Test
    void statistics() {
        List<Integer> constant = List.of(2, 2, 2, 2, 2, 2);
        assertEquals(2.0, GuardMath.mean(constant), 1e-9);
        assertEquals(0.0, GuardMath.standardDeviation(constant), 1e-9);
        assertTrue(GuardMath.standardDeviation(List.of(1, 2, 3, 2, 1, 3, 2, 2)) > 0.5);
    }

    @Test
    void jumpArcMatchesVanilla() {
        double[] expected = {0.3332, 0.24813599, 0.16477328, 0.08307782};
        double v = 0.42;
        for (double e : expected) {
            double[] predicted = GuardMath.predictFall(v, 0.08, false, 1);
            assertEquals(e, predicted[0], 1e-6);
            v = predicted[1];
        }
    }

    @Test
    void walkingOffAnEdgeAndTinyVelocity() {
        assertEquals(-0.0784, GuardMath.predictFall(0.0, 0.08, false, 1)[0], 1e-9);
        assertEquals(0.0, GuardMath.predictFall(0.0815, 0.08, false, 1)[0], 1e-9);
        assertEquals((-0.1 - 0.01) * 0.98, GuardMath.predictFall(-0.1, 0.08, true, 1)[0], 1e-9);
    }

    @Test
    void timerBalance() {
        double balance = 0.0;
        for (int i = 0; i < 20; i++) {
            balance = GuardMath.advanceTimerBalance(balance, 50.0, 0.0, -1500.0);
        }
        assertEquals(0.0, balance, 1e-9);
        balance = 0.0;
        for (int i = 0; i < 40; i++) {
            balance = GuardMath.advanceTimerBalance(balance, 50.0 / 1.1, 0.0, -1500.0);
        }
        assertTrue(balance > 150.0);
        assertEquals(-1500.0, GuardMath.advanceTimerBalance(0.0, 3050.0, 0.0, -1500.0), 1e-9);
        balance = GuardMath.advanceTimerBalance(0.0, 250.0, 0.0, -1500.0);
        for (int i = 0; i < 4; i++) {
            balance = GuardMath.advanceTimerBalance(balance, 0.0, 0.0, -1500.0);
        }
        assertEquals(0.0, balance, 1e-9);
    }
}
