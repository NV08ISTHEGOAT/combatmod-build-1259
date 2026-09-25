package com.example.combatguard.stream;

import com.example.combatguard.data.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Pings sent right after a packet. Pings and pongs travel on the same ordered connection as everything else, so
 * the pong proves the client has processed the packet before it (knockback, totem pop) and marks exactly where in
 * the client's packet stream that happened.
 */
public final class Transactions {
    /** Our ping ids start with "CG" so they never collide with pings other plugins send. */
    private static final int PREFIX = 0x43470000;
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private Transactions() {
    }

    public static boolean isOurs(int id) {
        return (id & 0xFFFF0000) == PREFIX;
    }

    /** Any thread. {@code onReply} runs on the server thread, in packet order. */
    public static void send(Object player, PlayerData data, String kind, Consumer<PlayerData> onReply) {
        int id = PREFIX | (COUNTER.incrementAndGet() & 0xFFFF);
        data.addTransaction(id, new PlayerData.Transaction(kind, System.nanoTime(), data.serverTicks, onReply));
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, new WrapperPlayServerPing(id));
    }
}
