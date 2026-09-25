package com.example.combatguard.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;

public final class GuardMath {
    private GuardMath() {
    }

    /** Same formula as {@code Entity#getRotationVector(float, float)}. */
    public static Vec3d lookVector(float pitch, float yaw) {
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    /** Distance from a point to the closest point of a box, 0 when inside. */
    public static double distanceToBox(Vec3d point, Box box) {
        return Math.sqrt(box.squaredMagnitude(point));
    }

    /** Whether a ray of the given length starting at {@code origin} enters the box. */
    public static boolean rayHits(Vec3d origin, Vec3d direction, double length, Box box) {
        if (box.contains(origin)) {
            return true;
        }
        return box.raycast(origin, origin.add(direction.multiply(length))).isPresent();
    }

    /** Angle in degrees between the look direction and the direction to the box centre. */
    public static double angleTo(Vec3d origin, Vec3d direction, Box box) {
        Vec3d to = box.getCenter().subtract(origin);
        double len = to.length();
        if (len < 1.0E-6) {
            return 0.0;
        }
        double cos = MathHelper.clamp(to.dotProduct(direction) / len, -1.0, 1.0);
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * A block face can only be clicked (or seen) from the outer side of its plane. This is geometry, so it
     * does not depend on rotations and cannot be faked by a scaffold that only sends a matching face id.
     */
    public static boolean canSeeFace(Vec3d eye, BlockPos pos, Direction side, double tolerance) {
        return switch (side) {
            case UP -> eye.y >= pos.getY() + 1 - tolerance;
            case DOWN -> eye.y <= pos.getY() + tolerance;
            case NORTH -> eye.z <= pos.getZ() + tolerance;
            case SOUTH -> eye.z >= pos.getZ() + 1 - tolerance;
            case WEST -> eye.x <= pos.getX() + tolerance;
            case EAST -> eye.x >= pos.getX() + 1 - tolerance;
        };
    }

    /** For a full cube, the vanilla client always reports a hit position that lies on the clicked face plane. */
    public static double hitVectorError(Vec3d hit, BlockPos pos, Direction side) {
        return switch (side) {
            case UP -> Math.abs(hit.y - (pos.getY() + 1));
            case DOWN -> Math.abs(hit.y - pos.getY());
            case NORTH -> Math.abs(hit.z - pos.getZ());
            case SOUTH -> Math.abs(hit.z - (pos.getZ() + 1));
            case WEST -> Math.abs(hit.x - pos.getX());
            case EAST -> Math.abs(hit.x - (pos.getX() + 1));
        };
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
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
