package com.example.combatguard.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsTest {
    private static final double GRAVITY = 0.08;

    @Test
    void jumpArcMatchesVanilla() {
        // Vanilla jump: 0.42, then (v - 0.08) * 0.98 each tick.
        double[] expected = {0.3332, 0.24813599, 0.16477329, 0.08307782, 0.00301604};
        double v = 0.42;
        for (double e : expected) {
            double[] predicted = FlightCheck.predict(v, GRAVITY, false, 1);
            assertEquals(e, predicted[0], 1e-6);
            v = predicted[1];
        }
    }

    @Test
    void walkingOffAnEdgeFallsAtVanillaSpeed() {
        assertEquals(-0.0784, FlightCheck.predict(0.0, GRAVITY, false, 1)[0], 1e-9);
    }

    @Test
    void tinyVelocityIsZeroedLikeVanilla() {
        // (0.08 - 0.08) * 0.98 = 0 and anything under 0.003 is zeroed.
        assertEquals(0.0, FlightCheck.predict(0.0815, GRAVITY, false, 1)[0], 1e-9);
    }

    @Test
    void slowFallingCapsGravityWhenFalling() {
        double normal = FlightCheck.predict(-0.1, GRAVITY, false, 1)[0];
        double slow = FlightCheck.predict(-0.1, GRAVITY, true, 1)[0];
        assertTrue(slow > normal);
        assertEquals((-0.1 - 0.01) * 0.98, slow, 1e-9);
    }

    @Test
    void multiTickPredictionSumsTicks() {
        double[] two = FlightCheck.predict(0.42, GRAVITY, false, 2);
        double first = FlightCheck.predict(0.42, GRAVITY, false, 1)[1];
        double second = FlightCheck.predict(first, GRAVITY, false, 1)[1];
        assertEquals(first + second, two[0], 1e-9);
        assertEquals(second, two[1], 1e-9);
    }

    @Test
    void timerBalance() {
        // 20 ticks per second exactly: balance stays at zero apart from the drift allowance.
        double balance = 0.0;
        for (int i = 0; i < 20; i++) {
            balance = TimerCheck.advance(balance, 50.0, 0.0, -1500.0);
        }
        assertEquals(0.0, balance, 1e-9);
        // A 10% timer gains about 5 ms per tick.
        balance = 0.0;
        for (int i = 0; i < 40; i++) {
            balance = TimerCheck.advance(balance, 50.0 / 1.1, 0.0, -1500.0);
        }
        assertTrue(balance > 150.0);
        // A 3 second freeze cannot be banked beyond the lower bound.
        assertEquals(-1500.0, TimerCheck.advance(0.0, 3050.0, 0.0, -1500.0), 1e-9);
        // A delayed burst only pays back what the delay cost.
        balance = TimerCheck.advance(0.0, 250.0, 0.0, -1500.0);
        for (int i = 0; i < 4; i++) {
            balance = TimerCheck.advance(balance, 0.0, 0.0, -1500.0);
        }
        assertEquals(0.0, balance, 1e-9);
    }
}
