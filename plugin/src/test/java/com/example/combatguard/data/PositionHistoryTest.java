package com.example.combatguard.data;

import com.example.combatguard.math.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionHistoryTest {
    @Test
    void keepsOnlyCapacityAndAddsMidpoints() {
        PositionHistory history = new PositionHistory(3);
        for (int i = 0; i < 5; i++) {
            history.add(i * 50L, new AABB(i, 0, 0, i + 1, 1, 1));
        }
        List<AABB> boxes = history.since(150L);
        assertEquals(4, boxes.size());
        assertEquals(2.5, boxes.get(0).minX(), 1e-9);
        assertEquals(3.0, boxes.get(1).minX(), 1e-9);
        assertEquals(3.5, boxes.get(2).minX(), 1e-9);
        assertEquals(4.0, boxes.get(3).minX(), 1e-9);
    }

    @Test
    void clearEmpties() {
        PositionHistory history = new PositionHistory(4);
        history.add(0L, new AABB(0, 0, 0, 1, 1, 1));
        history.clear();
        assertEquals(0, history.since(0L).size());
    }
}
