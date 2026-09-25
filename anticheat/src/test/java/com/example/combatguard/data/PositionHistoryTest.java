package com.example.combatguard.data;

import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionHistoryTest {
    @Test
    void keepsOnlyCapacityAndAddsMidpoints() {
        PositionHistory history = new PositionHistory(3);
        for (int i = 0; i < 5; i++) {
            history.add(i * 50L, new Box(i, 0, 0, i + 1, 1, 1));
        }
        // Entries at 100, 150, 200 remain; asking since 150 gives 150, midpoint(150,200), 200 and the midpoint
        // between 100 and 150.
        List<Box> boxes = history.since(150L);
        assertEquals(4, boxes.size());
        assertEquals(2.5, boxes.get(0).minX, 1e-9);
        assertEquals(3.0, boxes.get(1).minX, 1e-9);
        assertEquals(3.5, boxes.get(2).minX, 1e-9);
        assertEquals(4.0, boxes.get(3).minX, 1e-9);
    }

    @Test
    void clearEmpties() {
        PositionHistory history = new PositionHistory(4);
        history.add(0L, new Box(0, 0, 0, 1, 1, 1));
        history.clear();
        assertEquals(0, history.since(0L).size());
    }
}
