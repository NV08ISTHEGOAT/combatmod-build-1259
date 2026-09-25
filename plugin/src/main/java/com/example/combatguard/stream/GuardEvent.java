package com.example.combatguard.stream;

import com.example.combatguard.math.GuardMath;

/**
 * Packets reduced to what the checks need. The network thread appends them to the player's queue in the exact
 * order the client sent them; the server thread processes the queue at the start of every tick.
 * {@code nanos} is when the packet arrived.
 */
public sealed interface GuardEvent {
    long nanos();

    record Move(long nanos, double x, double y, double z, float yaw, float pitch,
                boolean hasPosition, boolean hasRotation, boolean onGround, boolean horizontalCollision) implements GuardEvent {
    }

    record TickEnd(long nanos) implements GuardEvent {
    }

    record Input(long nanos, boolean forward, boolean backward, boolean left, boolean right, boolean jump,
                 boolean sneak, boolean sprint) implements GuardEvent {
    }

    /** {@code cancelled}: the network thread already dropped the packet (reach far past the limit, multi-aura). */
    record Attack(long nanos, int entityId, boolean cancelled) implements GuardEvent {
    }

    record Interact(long nanos, int entityId) implements GuardEvent {
    }

    record Swing(long nanos) implements GuardEvent {
    }

    enum DigAction { START, ABORT, FINISH, STAB, DROP, SWAP_HANDS, RELEASE_USE, OTHER }

    record Dig(long nanos, DigAction action, int x, int y, int z, GuardMath.Face face) implements GuardEvent {
    }

    record Place(long nanos, int x, int y, int z, GuardMath.Face face, float cursorX, float cursorY, float cursorZ,
                 boolean offhand, boolean insideBlock) implements GuardEvent {
    }

    record UseItem(long nanos, boolean offhand) implements GuardEvent {
    }

    enum ClickType { PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL, OTHER }

    record Click(long nanos, int windowId, int slot, int button, ClickType type) implements GuardEvent {
    }

    record CloseWindow(long nanos) implements GuardEvent {
    }

    record SlotChange(long nanos, int slot) implements GuardEvent {
    }

    enum SprintAction { START_SPRINT, STOP_SPRINT, START_GLIDE, OTHER }

    record EntityAction(long nanos, SprintAction action) implements GuardEvent {
    }

    record VehicleMove(long nanos, double x, double y, double z) implements GuardEvent {
    }

    record TeleportConfirm(long nanos, int id) implements GuardEvent {
    }

    /** Server to client: a teleport the client has to confirm before its movement counts again. */
    record TeleportSent(long nanos, int id) implements GuardEvent {
    }

    record Pong(long nanos, int id) implements GuardEvent {
    }
}
