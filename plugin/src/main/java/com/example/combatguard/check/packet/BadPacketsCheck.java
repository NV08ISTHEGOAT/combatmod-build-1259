package com.example.combatguard.check.packet;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Packets or packet orders a vanilla client never produces: pitch (outside -90..90), moves (several movement
 * packets in a tick), post (input sent after the tick's movement packet, typical of silent rotations), slot (three
 * or more hotbar changes in a tick), blink (answering pings while holding back tick packets), transaction (pings
 * left unanswered), ticks (no ClientTickEnd at all, which every 1.21.2+ client sends).
 */
public final class BadPacketsCheck extends Check<BadPacketsCheck.State> {
    public static final class State {
        long lastPongNanos;
        boolean tickSincePong = true;
    }

    public BadPacketsCheck() {
        super("BadPackets", Category.PACKET, "Packets or packet orders the vanilla client never produces (Blink, silent rotations)", false, 1.0, 15.0, 0.05);
        option("allowLegacyClients", 0.0);
        option("blinkGapMs", 500.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public boolean allowLegacyClients() {
        return opt("allowLegacyClients") > 0.0;
    }

    public void invalidPitch(Player player, PlayerData data, float pitch) {
        flag(player, data, "pitch", "pitch=" + pitch, 5.0);
    }

    public void onTickEnd(Player player, PlayerData data) {
        if (!enabled()) {
            return;
        }
        if (data.tick.movePackets > 1) {
            flag(player, data, "moves", data.tick.movePackets + " movement packets in one tick", 1.0);
        }
        if (data.tick.interactionAfterMove != null) {
            flag(player, data, "post", data.tick.interactionAfterMove + " sent after the movement packet", 1.0);
        }
        if (data.tick.slotChanges >= 3) {
            flag(player, data, "slot", data.tick.slotChanges + " hotbar changes in one tick", 1.0);
        }
    }

    public void missingTickEnd(Player player, PlayerData data) {
        if (enabled() && !allowLegacyClients()) {
            flag(player, data, "ticks", "no ClientTickEnd packets", 1.0);
        }
    }

    public void transactionTimeout(Player player, PlayerData data, String kind) {
        flag(player, data, "transaction", "no reply to the " + kind + " ping for 10s", 2.0);
    }

    /** Network thread. */
    public void onTickEndNetty(PlayerData data) {
        State st = state(data);
        synchronized (st) {
            st.tickSincePong = true;
        }
    }

    /** Network thread: one of our pongs arrived. */
    public void onPongNetty(PlayerData data, long nanos) {
        if (!enabled() || !data.sawTickEnd || System.currentTimeMillis() - data.joinedAtMs < 10_000L) {
            return;
        }
        State st = state(data);
        long gapMs;
        synchronized (st) {
            gapMs = (nanos - st.lastPongNanos) / 1_000_000L;
            boolean held = !st.tickSincePong && st.lastPongNanos != 0L && gapMs > opt("blinkGapMs");
            st.lastPongNanos = nanos;
            st.tickSincePong = false;
            if (!held) {
                return;
            }
        }
        Bukkit.getScheduler().runTask(CombatGuardPlugin.instance(), () -> {
            Player player = Bukkit.getPlayer(data.uuid);
            if (player != null) {
                flag(player, data, "blink", "answered pings " + gapMs + "ms apart without sending ticks", 1.0);
            }
        });
    }
}
