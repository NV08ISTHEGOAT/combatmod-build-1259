package com.example.combatguard.check.packet;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;

/**
 * Inventory use. Opening any screen releases every key on the vanilla client and it tells the server so, so
 * clicking slots while still holding movement keys means the inventory was used without a screen (InvMove,
 * AutoArmor, AutoTotem). Chest stealers move items at a perfectly regular pace, one or two ticks apart.
 */
public final class InventoryCheck extends Check<InventoryCheck.State> {
    public static final class State {
        double moveBuffer;
        int lastQuickMoveTick = -1;
        int lastInterval = -1;
        int sameIntervalRun;
        int burstTicks;
    }

    public InventoryCheck() {
        super("Inventory", Category.INVENTORY, "Using the inventory while moving or at machine speed (InvMove, ChestStealer)", false, 1.0, 0.0, 0.1);
        option("moveBuffer", 2.0);
        option("constantRun", 8.0);
        option("burstTicks", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onClickSlot(ServerPlayerEntity player, PlayerData data, ClickSlotC2SPacket packet) {
        if (!enabled() || player.isSpectator() || player.isCreative()) {
            return;
        }
        State st = state(data);
        PlayerInput input = player.getPlayerInput();
        boolean moving = input.forward() || input.backward() || input.left() || input.right() || input.jump();
        boolean settled = data.clientTick - data.lastInputChangeTick >= 3;
        if (moving && settled && !player.hasVehicle()) {
            st.moveBuffer += 1.0;
            if (st.moveBuffer > opt("moveBuffer")) {
                flag(player, data, "move", "clicked a slot while holding movement keys", 1.0);
            }
        } else {
            st.moveBuffer = Math.max(0.0, st.moveBuffer - 0.25);
        }

        if (packet.actionType() != SlotActionType.QUICK_MOVE || packet.syncId() == 0) {
            return;
        }
        data.tick.quickMoveClicks++;
        int tick = data.clientTick;
        if (st.lastQuickMoveTick >= 0) {
            int interval = tick - st.lastQuickMoveTick;
            if (interval >= 1 && interval <= 2 && interval == st.lastInterval) {
                st.sameIntervalRun++;
            } else if (interval > 0) {
                st.sameIntervalRun = 0;
            }
            if (interval > 0) {
                st.lastInterval = interval;
            }
        }
        st.lastQuickMoveTick = tick;
        if (st.sameIntervalRun >= opt("constantRun")) {
            flag(player, data, "stealer", "items moved exactly every " + st.lastInterval + " tick(s)", 1.0);
            st.sameIntervalRun = 0;
        }
    }

    public void onTickEnd(ServerPlayerEntity player, PlayerData data) {
        if (!enabled()) {
            return;
        }
        State st = state(data);
        if (data.tick.quickMoveClicks >= 2) {
            if (++st.burstTicks >= opt("burstTicks")) {
                flag(player, data, "stealer", "several items moved every tick", 1.0);
                st.burstTicks = 0;
            }
        } else {
            st.burstTicks = 0;
        }
    }
}
