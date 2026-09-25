package com.example.combatguard.math;

import java.util.Collection;
import java.util.Locale;

/** Vanilla formulas and statistics shared by the checks. No Bukkit types, so it is unit tested directly. */
public final class GuardMath {
    private GuardMath() {
    }

    /** Same as Minecraft's Entity#getRotationVector(pitch, yaw). */
    public static Vec3 lookVector(float pitch, float yaw) {
        double f = Math.toRadians(pitch);
        double g = Math.toRadians(-yaw);
        double cosYaw = Math.cos(g);
        double sinYaw = Math.sin(g);
        double cosPitch = Math.cos(f);
        return new Vec3(sinYaw * cosPitch, -Math.sin(f), cosYaw * cosPitch);
    }

    public static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** Angle in degrees between a look direction and the direction to a point. */
    public static double angleTo(Vec3 origin, Vec3 direction, Vec3 point) {
        Vec3 to = point.subtract(origin);
        double len = to.length();
        if (len < 1.0E-6) {
            return 0.0;
        }
        double cos = Math.max(-1.0, Math.min(1.0, to.dot(direction) / len));
        return Math.toDegrees(Math.acos(cos));
    }

    /** Face of a block, as the six directions Minecraft uses. */
    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

    /** A block face can only be clicked or seen from the outer side of its plane. */
    public static boolean canSeeFace(Vec3 eye, int x, int y, int z, Face face, double tolerance) {
        return switch (face) {
            case UP -> eye.y() >= y + 1 - tolerance;
            case DOWN -> eye.y() <= y + tolerance;
            case NORTH -> eye.z() <= z + tolerance;
            case SOUTH -> eye.z() >= z + 1 - tolerance;
            case WEST -> eye.x() <= x + tolerance;
            case EAST -> eye.x() >= x + 1 - tolerance;
        };
    }

    /** For a full cube the vanilla client's hit position lies exactly on the clicked face plane. */
    public static double hitVectorError(Vec3 hit, int x, int y, int z, Face face) {
        return switch (face) {
            case UP -> Math.abs(hit.y() - (y + 1));
            case DOWN -> Math.abs(hit.y() - y);
            case NORTH -> Math.abs(hit.z() - z);
            case SOUTH -> Math.abs(hit.z() - (z + 1));
            case WEST -> Math.abs(hit.x() - x);
            case EAST -> Math.abs(hit.x() - (x + 1));
        };
    }

    /**
     * Vanilla airborne vertical motion: {@code vy = (vy - gravity) * 0.98}, tiny values zeroed, slow falling caps
     * gravity at 0.01 while falling. Returns {total movement over the ticks, velocity of the last tick}.
     */
    public static double[] predictFall(double lastDy, double gravity, boolean slowFalling, int ticks) {
        double v = lastDy;
        double total = 0.0;
        for (int i = 0; i < ticks; i++) {
            double g = slowFalling && v <= 0.0 ? Math.min(gravity, 0.01) : gravity;
            v = (v - g) * 0.98;
            if (Math.abs(v) < 0.003) {
                v = 0.0;
            }
            total += v;
        }
        return new double[]{total, v};
    }

    /**
     * Timer balance: each tick packet earns 50 ms, real time spends it. {@code drift} allows clocks that run a
     * little fast, the lower bound stops a player from saving up time by lagging on purpose.
     */
    public static double advanceTimerBalance(double balanceMs, double elapsedMs, double drift, double minBalanceMs) {
        return Math.max(balanceMs + 50.0 - elapsedMs - 50.0 * drift, minBalanceMs);
    }

    public static double mean(Collection<? extends Number> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Number n : values) {
            sum += n.doubleValue();
        }
        return sum / values.size();
    }

    public static double standardDeviation(Collection<? extends Number> values) {
        if (values.size() < 2) {
            return 0.0;
        }
        double mean = mean(values);
        double sum = 0.0;
        for (Number n : values) {
            double d = n.doubleValue() - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    public static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
