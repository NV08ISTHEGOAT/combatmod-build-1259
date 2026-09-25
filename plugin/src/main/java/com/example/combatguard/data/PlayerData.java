package com.example.combatguard.data;

import com.example.combatguard.check.Check;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.GuardMath;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.stream.GuardEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Everything CombatGuard knows about one connected player. Fields are only touched on the server thread unless
 * marked volatile, concurrent or synchronized (those are shared with the network thread).
 */
public final class PlayerData {
    public final UUID uuid;
    public final String name;
    public final int entityId;
    public final long joinedAtMs = System.currentTimeMillis();
    /** Server ticks since join. */
    public int serverTicks;
    public boolean alerts = true;
    public boolean verbose;
    public String clientVersion = "vanilla";

    // ---- Event stream from the network thread ----
    public final ConcurrentLinkedQueue<GuardEvent> queue = new ConcurrentLinkedQueue<>();
    public final AtomicInteger queued = new AtomicInteger();

    // ---- Violations and evidence ----
    private final Map<String, Double> violations = new HashMap<>();
    private final Map<String, Long> lastAlertMs = new HashMap<>();
    private final Set<String> punished = new HashSet<>();
    private final Map<Check<?>, Object> states = new ConcurrentHashMap<>();
    private final Deque<Evidence> evidence = new ArrayDeque<>();

    // ---- Exemptions (server tick of the last event) ----
    public int lastTeleportTick = -1000;
    public int lastRespawnTick = -1000;
    public int lastWorldChangeTick = -1000;
    public int lastGameModeChangeTick = -1000;
    public long exemptUntilMs;
    public Object lastGameMode;

    // ---- State rebuilt from the packet stream ----
    public Vec3 position;
    public float yaw;
    public float pitch;
    public boolean sprinting;
    public boolean sneaking;
    public GuardEvent.Input input = new GuardEvent.Input(0L, false, false, false, false, false, false, false);
    public int lastInputChangeTick;
    /** Server teleports sent and not yet confirmed; vanilla ignores movement until they are. */
    public int pendingTeleports;
    public boolean awaitingSetback;
    public boolean expectTeleportMove;
    public boolean digging;
    public int useItemTick = -1000;
    public boolean usingItem;
    public int clientTick;
    public int movesSinceTickEnd;
    public boolean legacyClient;
    public int totemSeenClientTick = -1000;
    /** Client tick of an offhand-changing action, judged at the end of the server tick once it is applied. */
    public int pendingOffhandTick = -1;
    public boolean inventoryOpen;
    public long containerOpenedAtMs;
    public final TickWindow tick = new TickWindow();
    public final MoveState move = new MoveState();
    public final PositionHistory history = new PositionHistory(40);

    /** Recent attribute values, so an effect that just ran out on the server is still allowed for a moment. */
    private final double[] recentSpeed = new double[40];
    private final double[] recentJump = new double[40];
    private int recentIndex;

    // ---- Shared with the network thread ----
    public volatile boolean sawTickEnd;
    /** Last position the client sent, updated on the network thread (for instant reach cancelling). */
    public volatile Vec3 nettyPosition;
    public volatile boolean nettySneaking;
    /** Attack range including the hitbox margin, refreshed every tick from the server thread. */
    public volatile double cachedAttackRange = 3.0;
    /** Set by the ground spoof check: rewrite the next move packets as airborne until the player lands. */
    public volatile boolean forceAirborne;
    public volatile int transactionRttMs;
    /** Entities attacked since the last tick end, network thread only. */
    public final Set<Integer> nettyTickTargets = new HashSet<>();
    private final LinkedHashMap<Integer, Transaction> transactions = new LinkedHashMap<>();

    public PlayerData(UUID uuid, String name, int entityId) {
        this.uuid = uuid;
        this.name = name;
        this.entityId = entityId;
    }

    @SuppressWarnings("unchecked")
    public <S> S state(Check<S> check) {
        return (S) states.computeIfAbsent(check, Check::newState);
    }

    // ---- Violations ----

    public double addViolation(String check, double amount, double max) {
        return violations.merge(check, Math.min(amount, max), (a, b) -> Math.min(max, a + b));
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
        return punished.add(check);
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

    // ---- Transactions (ping after a packet; the pong proves the client processed it) ----

    public synchronized void addTransaction(int id, Transaction transaction) {
        transactions.put(id, transaction);
        while (transactions.size() > 128) {
            transactions.remove(transactions.keySet().iterator().next());
        }
    }

    public synchronized Transaction takeTransaction(int id) {
        return transactions.remove(id);
    }

    public synchronized Transaction oldestTransaction() {
        return transactions.isEmpty() ? null : transactions.values().iterator().next();
    }

    public synchronized void clearTransactions() {
        transactions.clear();
    }

    /** @param onReply runs on the server thread, in packet order, when the client answers */
    public record Transaction(String kind, long sentNanos, int sentServerTick, Consumer<PlayerData> onReply) {
    }

    /** Packets seen during one client tick (between two ClientTickEnd packets). */
    public static final class TickWindow {
        public final List<Integer> attackedIds = new ArrayList<>();
        public int swings;
        public int movePackets;
        public boolean moved;
        public String interactionAfterMove;
        public boolean usedOrDug;
        public final Set<Long> startDigPositions = new HashSet<>();
        public int quickMoveClicks;
        public int slotChanges;
        public boolean offhandAction;
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
            offhandAction = false;
            aims.clear();
            places.clear();
        }
    }

    public record PendingAim(int targetId, List<Vec3> eyes, List<AABB> boxes, double range) {
    }

    public record PendingPlace(List<Vec3> eyes, int x, int y, int z, GuardMath.Face face) {
    }

    /** Last movement, used to predict the next one with vanilla's friction and gravity. */
    public static final class MoveState {
        public boolean valid;
        public double lastDx;
        public double lastDz;
        public double lastHorizontal;
        public double lastFriction = 0.91;
        public double lastDy;
        public boolean lastOnGround = true;
        public boolean lastHorizontalCollision;
        public boolean lastSingleTick;
        public boolean lastSpecial;
        public int ticksSincePosition;
        public int skip = 5;
        public Vec3 knockback;
        public boolean knockbackAdditive;
        public int lastLowJumpTick = -1000;

        public void invalidate(int skipMoves) {
            valid = false;
            knockback = null;
            skip = Math.max(skip, skipMoves);
        }
    }
}
