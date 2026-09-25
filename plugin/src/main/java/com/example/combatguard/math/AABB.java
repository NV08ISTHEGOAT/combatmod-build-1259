package com.example.combatguard.math;

/** Axis aligned box, same conventions as Minecraft's. */
public record AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    /** Box of a {@code width} x {@code height} entity standing at {@code pos}. */
    public static AABB ofEntity(Vec3 pos, double width, double height) {
        double half = width / 2.0;
        return new AABB(pos.x() - half, pos.y(), pos.z() - half, pos.x() + half, pos.y() + height, pos.z() + half);
    }

    public static AABB ofBlock(int x, int y, int z) {
        return new AABB(x, y, z, x + 1, y + 1, z + 1);
    }

    public AABB offset(double dx, double dy, double dz) {
        return new AABB(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public AABB expand(double amount) {
        return expand(amount, amount, amount);
    }

    public AABB expand(double x, double y, double z) {
        return new AABB(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }

    /** Grows the box in the direction of the given offsets only (Minecraft's "stretch"). */
    public AABB stretch(double x, double y, double z) {
        return new AABB(
            x < 0 ? minX + x : minX, y < 0 ? minY + y : minY, z < 0 ? minZ + z : minZ,
            x > 0 ? maxX + x : maxX, y > 0 ? maxY + y : maxY, z > 0 ? maxZ + z : maxZ);
    }

    public double height() {
        return maxY - minY;
    }

    public Vec3 center() {
        return new Vec3((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
    }

    public boolean contains(Vec3 p) {
        return p.x() >= minX && p.x() < maxX && p.y() >= minY && p.y() < maxY && p.z() >= minZ && p.z() < maxZ;
    }

    /** Closest point of the box to {@code p}. */
    public Vec3 closestPoint(Vec3 p) {
        return new Vec3(clamp(p.x(), minX, maxX), clamp(p.y(), minY, maxY), clamp(p.z(), minZ, maxZ));
    }

    public double distanceTo(Vec3 p) {
        return closestPoint(p).subtract(p).length();
    }

    /** Whether the segment from {@code origin} along {@code direction * length} enters the box (slab test). */
    public boolean intersectsRay(Vec3 origin, Vec3 direction, double length) {
        if (contains(origin)) {
            return true;
        }
        double tMin = 0.0;
        double tMax = length;
        double[] o = {origin.x(), origin.y(), origin.z()};
        double[] d = {direction.x(), direction.y(), direction.z()};
        double[] lo = {minX, minY, minZ};
        double[] hi = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0E-12) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) {
                    return false;
                }
                continue;
            }
            double t1 = (lo[axis] - o[axis]) / d[axis];
            double t2 = (hi[axis] - o[axis]) / d[axis];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) {
                return false;
            }
        }
        return true;
    }

    public static AABB lerp(AABB a, AABB b) {
        return new AABB((a.minX + b.minX) / 2, (a.minY + b.minY) / 2, (a.minZ + b.minZ) / 2,
            (a.maxX + b.maxX) / 2, (a.maxY + b.maxY) / 2, (a.maxZ + b.maxZ) / 2);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
