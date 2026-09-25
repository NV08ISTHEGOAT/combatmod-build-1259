package com.example.combatguard.data;

import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of a player's bounding boxes, one entry per server tick. Other players' reach and aim checks
 * use it to reconstruct where this player was on the attacker's screen (lag compensation).
 */
public final class PositionHistory {
    private final long[] times;
    private final Box[] boxes;
    private int next;
    private int size;

    public PositionHistory(int capacity) {
        this.times = new long[capacity];
        this.boxes = new Box[capacity];
    }

    public synchronized void add(long timeMs, Box box) {
        times[next] = timeMs;
        boxes[next] = box;
        next = (next + 1) % boxes.length;
        size = Math.min(size + 1, boxes.length);
    }

    public synchronized void clear() {
        size = 0;
        next = 0;
    }

    /**
     * Boxes recorded at or after {@code sinceMs}, plus the halfway points between consecutive entries, because
     * the client interpolates entity positions between the updates it receives.
     */
    public synchronized List<Box> since(long sinceMs) {
        List<Box> out = new ArrayList<>();
        Box previous = null;
        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(next - size + i, boxes.length);
            if (times[idx] < sinceMs) {
                previous = boxes[idx];
                continue;
            }
            Box box = boxes[idx];
            if (previous != null) {
                out.add(lerp(previous, box));
            }
            out.add(box);
            previous = box;
        }
        return out;
    }

    private static Box lerp(Box a, Box b) {
        return new Box(
            (a.minX + b.minX) / 2, (a.minY + b.minY) / 2, (a.minZ + b.minZ) / 2,
            (a.maxX + b.maxX) / 2, (a.maxY + b.maxY) / 2, (a.maxZ + b.maxZ) / 2
        );
    }
}
