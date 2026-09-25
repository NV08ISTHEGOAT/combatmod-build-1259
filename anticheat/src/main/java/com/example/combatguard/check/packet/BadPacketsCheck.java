package com.example.combatguard.check.packet;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Packets or packet orders a vanilla client never produces.
 * <ul>
 *     <li>pitch: outside -90..90 (vanilla clamps it, the server silently accepts it);</li>
 *     <li>moves: more than one movement packet in a tick (packet criticals, some teleport cheats);</li>
 *     <li>post: attacks, placements, digging or swings sent after the tick's movement packet. Vanilla handles
 *     input before it moves, so this order comes from cheats that act after sending a silent rotation;</li>
 *     <li>slot: three or more hotbar changes in one tick, or a change after the movement packet (weapon swap
 *     and AutoTool that switch back after hitting);</li>
 *     <li>blink: the client answers pings but holds back its tick packets. Pongs and tick packets travel on the
 *     same ordered connection, so two pongs well apart with no tick in between means packets were held;</li>
 *     <li>transaction: pings (sent after knockback, totem pops and once a second) left unanswered;</li>
 *     <li>ticks: no ClientTickEnd packets at all, which every 1.21.2+ client sends.</li>
 * </ul>
 */
public final class BadPacketsCheck extends Check<BadPacketsCheck.State> {
    public static final class State {
        long lastPongNanos;
        boolean tickSincePong = true;
    }

    public BadPacketsCheck() {
        super("BadPackets", Category.PACKET, "Packets or packet orders the vanilla client never produces", false, 1.0, 15.0, 0.05);
        option("allowLegacyClients", 0.0);
        option("blinkGapMs", 500.0);
        option("transactionTimeoutTicks", 200.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public boolean allowLegacyClients() {
        return opt("allowLegacyClients") > 0.0;
    }

    public void invalidPitch(ServerPlayerEntity player, PlayerData data, float pitch) {
        flag(player, data, "pitch", "pitch=" + pitch, 5.0);
    }

    public void onTickEnd(ServerPlayerEntity player, PlayerData data) {
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

    public void missingTickEnd(ServerPlayerEntity player, PlayerData data) {
        if (enabled() && !allowLegacyClients()) {
            flag(player, data, "ticks", "no ClientTickEnd packets", 1.0);
        }
    }

    public void transactionTimeout(ServerPlayerEntity player, PlayerData data, String kind) {
        flag(player, data, "transaction", "no reply to the " + kind + " ping for 10s", 2.0);
    }

    /** Network thread: a ClientTickEnd arrived. */
    public void onTickEndNetty(PlayerData data) {
        State st = state(data);
        synchronized (st) {
            st.tickSincePong = true;
        }
    }

    /** Network thread: one of our pongs arrived. */
    public void onPongNetty(MinecraftServer server, UUID uuid) {
        PlayerData data = PlayerDataManager.get(uuid);
        if (data == null || !enabled() || !data.sawTickEnd || System.currentTimeMillis() - data.joinedAtMs < 10_000L) {
            return;
        }
        State st = state(data);
        long now = System.nanoTime();
        long gapMs;
        synchronized (st) {
            gapMs = (now - st.lastPongNanos) / 1_000_000L;
            boolean held = !st.tickSincePong && st.lastPongNanos != 0L && gapMs > opt("blinkGapMs");
            st.lastPongNanos = now;
            st.tickSincePong = false;
            if (!held) {
                return;
            }
        }
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                flag(player, data, "blink", "answered pings " + gapMs + "ms apart without sending ticks", 1.0);
            }
        });
    }
}
