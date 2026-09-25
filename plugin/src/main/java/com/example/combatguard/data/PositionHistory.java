package com.example.combatguard.data;

import com.example.combatguard.math.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of a player's bounding boxes, one per server tick. Reach and aim checks use it to reconstruct where
 * this player was on the attacker's screen. Synchronized because the network thread reads it too.
 */
public final class PositionHistory {
    private final long[] times;
    private final AABB[] boxes;
    private int next;
    private int size;

    public PositionHistory(int capacity) {
        this.times = new long[capacity];
        this.boxes = new AABB[capacity];
    }

    public synchronized void add(long timeMs, AABB box) {
        times[next] = timeMs;
        boxes[next] = box;
        next = (next + 1) % boxes.length;
        size = Math.min(size + 1, boxes.length);
    }

    public synchronized void clear() {
        size = 0;
        next = 0;
    }

    /** Boxes recorded at or after {@code sinceMs}, plus midpoints between entries (clients interpolate). */
    public synchronized List<AABB> since(long sinceMs) {
        List<AABB> out = new ArrayList<>();
        AABB previous = null;
        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(next - size + i, boxes.length);
            if (times[idx] < sinceMs) {
                previous = boxes[idx];
                continue;
            }
            AABB box = boxes[idx];
            if (previous != null) {
                out.add(AABB.lerp(previous, box));
            }
            out.add(box);
            previous = box;
        }
        return out;
    }
}
