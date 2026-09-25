package com.example.combatguard.data;

import com.example.combatguard.check.Check;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Everything CombatGuard knows about one connected player. Unless noted, only touched on the server thread. */
public final class PlayerData {
    public final UUID uuid;
    public final String name;
    public final long joinedAtMs = System.currentTimeMillis();
    /** Server ticks since join. */
    public int serverTicks;
    public boolean alerts = true;
    public boolean verbose;
    /** CombatGuard client mod version, when the player runs it. */
    public String clientVersion = "vanilla";

    private final Map<String, Double> violations = new HashMap<>();
    private final Map<String, Long> lastAlertMs = new HashMap<>();
    private final Map<String, Boolean> punished = new HashMap<>();
    /** Per-check state. Concurrent because the timer check reads its state on the network thread. */
    private final Map<Check<?>, Object> states = new ConcurrentHashMap<>();
    private final Deque<Evidence> evidence = new ArrayDeque<>();

    // ---- Exemptions (server tick of the last event, see Exemptions) ----
    public int lastTeleportTick = -1000;
    public int lastRespawnTick = -1000;
    public int lastWorldChangeTick = -1000;
    public int lastGameModeChangeTick = -1000;
    public int lastVelocityTick = -1000;
    /** Set by /cg exempt. */
    public long exemptUntilMs;
    public Object lastGameMode;
    public Object lastScreenHandler;
    public long screenOpenedAtMs;

    // ---- Client tick tracking ----
    /** Number of ClientTickEnd packets received, i.e. the client's own tick counter. */
    public int clientTick;
    /** Move packets since the last ClientTickEnd, used to spot clients that never send tick ends. */
    public int movesSinceTickEnd;
    public boolean legacyClient;
    /** Set once the first ClientTickEnd arrives (network thread). Blink detection only applies to such clients. */
    public volatile boolean sawTickEnd;
    public boolean expectTeleportMove;
    public boolean digging;
    public int lastInputChangeTick;
    public boolean sprintingBeforeAttack;
    public final TickWindow tick = new TickWindow();
    public final MoveState move = new MoveState();
    public final PositionHistory history = new PositionHistory(40);

    /** Recent attribute values, so an effect that just ran out on the server is still allowed for a moment. */
    private final double[] recentSpeed = new double[40];
    private final double[] recentJump = new double[40];
    private int recentIndex;

    // ---- Transactions: a ping sent after a packet, whose pong proves the client processed that packet ----
    private final Int2ObjectLinkedOpenHashMap<Transaction> transactions = new Int2ObjectLinkedOpenHashMap<>();
    /** Round trip time measured with transactions, smoothed. More current than the vanilla keep-alive latency. */
    public volatile int transactionRttMs;
    /** Client tick at which the client confirmed seeing its own totem pop. */
    public int totemSeenClientTick = -1000;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    @SuppressWarnings("unchecked")
    public <S> S state(Check<S> check) {
        return (S) states.computeIfAbsent(check, c -> c.newState());
    }

    // ---- Violations ----

    public double addViolation(String check, double amount, double max) {
        return violations.merge(check, Math.min(amount, max), (a, b) -> Math.min(max, a + b));
    }

    public double violation(String check) {
        return violations.getOrDefault(check, 0.0);
    }

    public Map<String, Double> violations() {
        return violations;
    }

    public double totalViolations() {
        double total = 0.0;
        for (double v : violations.values()) {
            total += v;
        }
        return total;
    }

    public void decay(String check, double amount) {
        violations.computeIfPresent(check, (k, v) -> v - amount <= 0 ? null : v - amount);
    }

    public void resetViolations() {
        violations.clear();
        punished.clear();
        evidence.clear();
    }

    public boolean alertCooldownPassed(String key, long nowMs, long cooldownMs) {
        Long last = lastAlertMs.get(key);
        if (last != null && nowMs - last < cooldownMs) {
            return false;
        }
        lastAlertMs.put(key, nowMs);
        return true;
    }

    public boolean markPunished(String check) {
        return punished.put(check, Boolean.TRUE) == null;
    }

    public void addEvidence(Evidence entry, int max) {
        evidence.addLast(entry);
        while (evidence.size() > Math.max(1, max)) {
            evidence.removeFirst();
        }
    }

    public List<Evidence> evidence() {
        return new ArrayList<>(evidence);
    }

    /** One flag with the context staff need to judge it. */
    public record Evidence(long timeMs, String check, String type, String details, double vl, int ping, double tps,
                           String world, double x, double y, double z) {
    }

    // ---- Attributes ----

    public void recordAttributes(double speed, double jump) {
        recentSpeed[recentIndex] = speed;
        recentJump[recentIndex] = jump;
        recentIndex = (recentIndex + 1) % recentSpeed.length;
    }

    public double maxRecentSpeed(double current) {
        double max = current;
        for (double v : recentSpeed) {
            max = Math.max(max, v);
        }
        return max;
    }

    public double maxRecentJump(double current) {
        double max = current;
        for (double v : recentJump) {
            max = Math.max(max, v);
        }
        return max;
    }

    public double minRecentJump(double current) {
        double min = current;
        for (double v : recentJump) {
            if (v > 0.0) {
                min = Math.min(min, v);
            }
        }
        return min;
    }

    // ---- Transactions ----

    public synchronized void addTransaction(int id, Transaction transaction) {
        transactions.put(id, transaction);
        while (transactions.size() > 128) {
            transactions.removeFirst();
        }
    }

    public synchronized Transaction takeTransaction(int id) {
        return transactions.remove(id);
    }

    public synchronized Transaction oldestTransaction() {
        return transactions.isEmpty() ? null : transactions.get(transactions.firstIntKey());
    }

    public synchronized void clearTransactions() {
        transactions.clear();
    }

    /**
     * @param kind    what the ping follows ("velocity", "totem", "rtt")
     * @param onReply runs on the server thread, in packet order, when the client answers
     */
    public record Transaction(String kind, long sentNanos, int sentServerTick, Consumer<PlayerData> onReply) {
    }

    /** Packets seen between two ClientTickEnd packets, i.e. during one client tick. */
    public static final class TickWindow {
        public final IntArrayList attackedIds = new IntArrayList();
        public int swings;
        /** Movement packets this tick, not counting the reply to a server teleport. Vanilla sends at most one. */
        public int movePackets;
        public boolean moved;
        public String interactionAfterMove;
        public boolean usedOrDug;
        public final LongOpenHashSet startDigPositions = new LongOpenHashSet();
        public int quickMoveClicks;
        public int slotChanges;
        public final List<PendingAim> aims = new ArrayList<>();
        public final List<PendingPlace> places = new ArrayList<>();

        public void reset() {
            attackedIds.clear();
            swings = 0;
            movePackets = 0;
            moved = false;
            interactionAfterMove = null;
            usedOrDug = false;
            startDigPositions.clear();
            quickMoveClicks = 0;
            slotChanges = 0;
            aims.clear();
            places.clear();
        }
    }

    /** An attack whose aim is checked at the end of the tick, once the rotation used for it has arrived. */
    public record PendingAim(int targetId, List<Vec3d> eyes, List<Box> boxes, double range) {
    }

    /** A block placement whose aim is checked at the end of the tick. */
    public record PendingPlace(List<Vec3d> eyes, BlockPos pos, Direction side) {
    }

    /** Last known movement of the player, used to predict the next one with vanilla's friction and gravity. */
    public static final class MoveState {
        /** Whether the fields below describe the previous client tick. */
        public boolean valid;
        public double lastDx;
        public double lastDz;
        public double lastHorizontal;
        public double lastFriction = 0.91;
        public double lastDy;
        public boolean lastOnGround = true;
        public boolean lastHorizontalCollision;
        public boolean lastSingleTick;
        /** The previous move touched liquids, ladders, cobwebs, slime or pistons, so it did not follow plain physics. */
        public boolean lastSpecial;
        /** Client ticks since the last packet that carried a position. */
        public int ticksSincePosition;
        /** Moves to skip, for example right after a teleport. */
        public int skip = 5;
        /** Knockback the client acknowledged (via ping/pong) and that applies to its next move. */
        public Vec3d knockback;
        public boolean knockbackAdditive;
        /** The server applied the sprint-hit slowdown this tick, so the client should have too. */
        public boolean sprintHitSlowdown;
        public int lastLowJumpTick = -1000;

        public void invalidate(int skipMoves) {
            valid = false;
            knockback = null;
            sprintHitSlowdown = false;
            skip = Math.max(skip, skipMoves);
        }
    }
}
