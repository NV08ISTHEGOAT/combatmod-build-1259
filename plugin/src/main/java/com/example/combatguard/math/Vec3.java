package com.example.combatguard.math;

/** Immutable 3D vector. Plain Java so the check math can run on any thread and in unit tests. */
public record Vec3(double x, double y, double z) {
    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 add(Vec3 other) {
        return add(other.x, other.y, other.z);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 multiply(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }
}
