package com.example.combatguard.util;

/** Measures server TPS from tick start times, averaged over the last 100 ticks. */
public final class ServerLag {
    private static final long[] INTERVALS = new long[100];
    private static int index;
    private static int count;
    private static long lastTickNanos;
    private static volatile double tps = 20.0;

    private ServerLag() {
    }

    public static void onTickStart() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            INTERVALS[index] = now - lastTickNanos;
            index = (index + 1) % INTERVALS.length;
            count = Math.min(count + 1, INTERVALS.length);
            long sum = 0L;
            for (int i = 0; i < count; i++) {
                sum += INTERVALS[i];
            }
            double averageMs = sum / (double) count / 1_000_000.0;
            tps = Math.min(20.0, 1000.0 / Math.max(averageMs, 1.0));
        }
        lastTickNanos = now;
    }

    public static double tps() {
        return tps;
    }

    public static void reset() {
        index = 0;
        count = 0;
        lastTickNanos = 0L;
        tps = 20.0;
    }
}
