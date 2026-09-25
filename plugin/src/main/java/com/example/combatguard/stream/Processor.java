package com.example.combatguard.stream;

import com.example.combatguard.check.Check;
import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.check.ViolationManager;
import com.example.combatguard.check.movement.MoveContext;
import com.example.combatguard.data.Exemptions;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.world.WorldQuery;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.example.combatguard.check.CheckRegistry.*;

/**
 * Server thread. At the start of every tick each player's event queue is processed in packet order; at the end of
 * the tick hitboxes are recorded for lag compensation and violation levels decay.
 */
public final class Processor {
    private static final int MAX_EVENTS_PER_TICK = 2000;

    private Processor() {
    }

    // ------------------------------------------------------------------ ticks

    public static void onTickStart() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = PlayerDataManager.get(player);
            if (data == null) {
                continue;
            }
            GuardEvent event;
            int processed = 0;
            while (processed++ < MAX_EVENTS_PER_TICK && (event = data.queue.poll()) != null) {
                data.queued.decrementAndGet();
                handle(player, data, event);
                if (!player.isOnline()) {
                    break;
                }
            }
        }
    }

    public static void onTickEnd() {
        long now = System.currentTimeMillis();
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            PlayerData data = PlayerDataManager.get(player);
            if (data == null) {
                continue;
            }
            data.serverTicks++;
            data.history.add(now, WorldQuery.aabb(player.getBoundingBox()));
            double speed = attribute(player, Attribute.MOVEMENT_SPEED, 0.1) * (player.isSprinting() ? 1.0 : 1.3);
            Location location = player.getLocation();
            data.recordAttributes(speed, MoveContext.jumpVelocity(player, new Vec3(location.getX(), location.getY(), location.getZ())));
            data.cachedAttackRange = attackRange(player);

            GameMode mode = player.getGameMode();
            if (mode != data.lastGameMode) {
                data.lastGameMode = mode;
                data.lastGameModeChangeTick = data.serverTicks;
                data.move.invalidate(5);
            }
            if (data.awaitingSetback && data.serverTicks - data.lastTeleportTick > 60) {
                // The setback teleport never happened (another plugin cancelled it).
                data.awaitingSetback = false;
            }
            for (Check<?> check : CheckRegistry.all()) {
                double decay = check.settings().decayPerSecond;
                if (decay > 0) {
                    data.decay(check.id(), decay / 20.0);
                }
            }
            PlayerData.Transaction oldest = data.oldestTransaction();
            if (oldest != null && data.serverTicks - oldest.sentServerTick() > 200) {
                data.clearTransactions();
                if ("velocity".equals(oldest.kind())) {
                    VELOCITY.transactionTimeout(player, data);
                } else {
                    BAD_PACKETS.transactionTimeout(player, data, oldest.kind());
                }
            }
            if (data.serverTicks % 20 == 0) {
                Transactions.send(player, data, "rtt", d -> {
                });
            }
            if (data.movesSinceTickEnd > 40 && !data.legacyClient) {
                data.legacyClient = true;
                BAD_PACKETS.missingTickEnd(player, data);
            }
            if (data.pendingOffhandTick >= 0) {
                AUTO_TOTEM.onOffhandAction(player, data, data.pendingOffhandTick);
                data.pendingOffhandTick = -1;
            }
            CLIENT.tick(player, data);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    /** Attack range of the held item (spears) or the entity interaction range, plus the item's hitbox margin. */
    public static double attackRange(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        AttackRange range = held.isEmpty() ? null : held.getData(DataComponentTypes.ATTACK_RANGE);
        if (range != null) {
            float max = player.getGameMode() == GameMode.CREATIVE ? range.maxCreativeReach() : range.maxReach();
            return max + range.hitboxMargin();
        }
        return attribute(player, Attribute.ENTITY_INTERACTION_RANGE, 3.0);
    }

    public static void applyKnockback(PlayerData data, Vec3 velocity, boolean additive) {
        PlayerData.MoveState move = data.move;
        if (additive && move.knockback != null) {
            move.knockback = move.knockback.add(velocity);
        } else {
            move.knockback = velocity;
            move.knockbackAdditive = additive;
        }
    }

    /** Eye positions the client may have used: standing, sneaking and the current pose. */
    private static List<Vec3> eyes(Player player, PlayerData data) {
        Vec3 pos = data.position != null ? data.position : new Vec3(player.getX(), player.getY(), player.getZ());
        List<Vec3> eyes = new ArrayList<>(3);
        eyes.add(pos.add(0.0, 1.62, 0.0));
        eyes.add(pos.add(0.0, 1.27, 0.0));
        eyes.add(pos.add(0.0, player.getEyeHeight(), 0.0));
        return eyes;
    }

    private static void post(PlayerData data, String what) {
        if (data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = what;
        }
    }

    /** Where the target could have been on the attacker's screen. */
    private static List<AABB> targetBoxes(Player attacker, PlayerData data, int targetId, long eventNanos) {
        long eventMs = System.currentTimeMillis() - (System.nanoTime() - eventNanos) / 1_000_000L;
        int ping = ViolationManager.ping(attacker, data);
        List<AABB> boxes = new ArrayList<>();
        PlayerData target = PlayerDataManager.byEntityId(targetId);
        if (target != null) {
            boxes.addAll(target.history.since(eventMs - (long) (ping * 1.5) - 300L));
            Player targetPlayer = Bukkit.getPlayer(target.uuid);
            if (targetPlayer != null) {
                boxes.add(WorldQuery.aabb(targetPlayer.getBoundingBox()));
            }
            return boxes;
        }
        for (Entity entity : attacker.getWorld().getNearbyEntities(attacker.getLocation(), 10, 10, 10, e -> e.getEntityId() == targetId)) {
            // Mobs are not recorded: widen their box by how far they can move during the latency window.
            double slack = Math.min(1.0, 0.1 + entity.getVelocity().clone().setY(0).length() * (ping / 50.0 + 4.0));
            boxes.add(WorldQuery.aabb(entity.getBoundingBox()).expand(slack));
        }
        return boxes;
    }

    // ------------------------------------------------------------------ dispatch

    private static void handle(Player player, PlayerData data, GuardEvent event) {
        switch (event) {
            case GuardEvent.Move move -> onMove(player, data, move);
            case GuardEvent.TickEnd tickEnd -> onTickEnd(player, data);
            case GuardEvent.Input input -> {
                if (!input.equals(data.input)) {
                    data.lastInputChangeTick = data.clientTick;
                }
                data.input = input;
                data.sneaking = input.sneak();
            }
            case GuardEvent.Attack attack -> onAttack(player, data, attack);
            case GuardEvent.Interact interact -> {
                data.tick.usedOrDug = true;
                post(data, "entity interaction");
            }
            case GuardEvent.Swing swing -> {
                data.tick.swings++;
                post(data, "arm swing");
            }
            case GuardEvent.Dig dig -> onDig(player, data, dig);
            case GuardEvent.Place place -> onPlace(player, data, place);
            case GuardEvent.UseItem use -> {
                data.tick.usedOrDug = true;
                data.usingItem = true;
                data.useItemTick = data.clientTick;
                post(data, "item use");
            }
            case GuardEvent.Click click -> {
                if (!ViolationManager.isExempt(player, data)) {
                    INVENTORY.onClick(player, data, click);
                }
                boolean offhand = (click.windowId() == 0 && click.slot() == 45)
                    || (click.type() == GuardEvent.ClickType.SWAP && click.button() == 40);
                if (offhand) {
                    data.pendingOffhandTick = data.clientTick;
                }
            }
            case GuardEvent.CloseWindow close -> {
            }
            case GuardEvent.SlotChange slot -> {
                data.tick.slotChanges++;
                data.usingItem = false;
                post(data, "hotbar change");
            }
            case GuardEvent.EntityAction action -> {
                if (action.action() == GuardEvent.SprintAction.START_SPRINT) {
                    data.sprinting = true;
                } else if (action.action() == GuardEvent.SprintAction.STOP_SPRINT) {
                    data.sprinting = false;
                }
            }
            case GuardEvent.VehicleMove vehicle -> {
                Entity ridden = player.getVehicle();
                if (ridden != null && data.pendingTeleports == 0 && !ViolationManager.isExempt(player, data)
                    && !Exemptions.movement(player, data)) {
                    VEHICLE.onVehicleMove(player, data, ridden, vehicle.x(), vehicle.y(), vehicle.z());
                }
            }
            case GuardEvent.TeleportSent sent -> data.pendingTeleports++;
            case GuardEvent.TeleportConfirm confirm -> {
                if (data.pendingTeleports > 0) {
                    data.pendingTeleports--;
                }
                if (data.pendingTeleports == 0) {
                    data.awaitingSetback = false;
                }
                data.expectTeleportMove = true;
                data.lastTeleportTick = data.serverTicks;
                data.move.invalidate(3);
            }
            case GuardEvent.Pong pong -> {
                PlayerData.Transaction transaction = data.takeTransaction(pong.id());
                if (transaction != null) {
                    int rtt = (int) ((pong.nanos() - transaction.sentNanos()) / 1_000_000L);
                    data.transactionRttMs = data.transactionRttMs == 0 ? rtt : (data.transactionRttMs * 3 + rtt) / 4;
                    transaction.onReply().accept(data);
                }
            }
        }
    }

    private static void onTickEnd(Player player, PlayerData data) {
        data.movesSinceTickEnd = 0;
        data.legacyClient = false;
        boolean attacked = !data.tick.attackedIds.isEmpty();
        if (!ViolationManager.isExempt(player, data)) {
            KILL_AURA.onTickEnd(player, data);
            HITBOX.onTickEnd(player, data);
            AIM.onTickEnd(player, data, attacked);
            AUTO_CLICKER.onTickEnd(player, data);
            SCAFFOLD.onTickEnd(player, data);
            NUKER.onTickEnd(player, data);
            BAD_PACKETS.onTickEnd(player, data);
            INVENTORY.onTickEnd(player, data);
        }
        data.tick.reset();
        data.clientTick++;
        data.move.ticksSincePosition++;
    }

    private static void onMove(Player player, PlayerData data, GuardEvent.Move event) {
        boolean teleportReply = data.expectTeleportMove;
        data.expectTeleportMove = false;
        if (!teleportReply) {
            data.tick.moved = true;
            data.tick.movePackets++;
            data.movesSinceTickEnd++;
        }
        if (event.hasRotation()) {
            if (Float.isFinite(event.pitch()) && Math.abs(event.pitch()) > 90.0001F) {
                BAD_PACKETS.invalidPitch(player, data, event.pitch());
            }
            data.yaw = event.yaw();
            data.pitch = event.pitch();
        }
        PlayerData.MoveState move = data.move;
        if (data.pendingTeleports > 0 || data.awaitingSetback) {
            // The server ignores movement until its teleport is confirmed.
            move.invalidate(2);
            move.lastOnGround = event.onGround();
            return;
        }
        if (!event.hasPosition()) {
            move.lastOnGround = event.onGround();
            return;
        }
        Vec3 to = new Vec3(event.x(), event.y(), event.z());
        if (!Double.isFinite(to.x()) || !Double.isFinite(to.y()) || !Double.isFinite(to.z())) {
            return;
        }
        Vec3 from = data.position;
        data.position = to;
        if (teleportReply || from == null) {
            move.invalidate(2);
            move.lastOnGround = event.onGround();
            move.ticksSincePosition = 0;
            return;
        }
        int ticks = data.legacyClient ? 1 : move.ticksSincePosition;
        move.ticksSincePosition = 0;

        MoveContext c = new MoveContext(player, data, event, from, to, ticks);
        if (!ViolationManager.isExempt(player, data) && !(data.legacyClient && BAD_PACKETS.allowLegacyClients())) {
            double jump = MoveContext.jumpVelocity(player, from);
            SPEED.process(c);
            FLIGHT.process(c);
            JUMP.process(c, data.maxRecentJump(jump), data.minRecentJump(jump));
            GROUND_SPOOF.process(c, ViolationManager.ping(player, data));
            CLIMB.process(c);
            PHASE.process(c);
            SPRINT.process(c);
            ELYTRA.process(c);
            VELOCITY.process(c);
        }
        if (c.setback) {
            player.teleport(new Location(player.getWorld(), from.x(), from.y(), from.z(), data.yaw, data.pitch));
            data.position = from;
            data.awaitingSetback = true;
            data.lastTeleportTick = data.serverTicks;
            move.invalidate(3);
            return;
        }
        move.valid = true;
        move.lastSingleTick = c.ticks == 1;
        move.lastDx = c.dx;
        move.lastDz = c.dz;
        move.lastHorizontal = c.horizontal;
        move.lastFriction = c.ticks == 1 ? c.friction() : 0.91;
        boolean grounded = c.onGround && !c.forceAirborne;
        move.lastDy = grounded ? 0.0 : (c.ticks == 1 ? c.dy : c.dy / c.ticks);
        move.lastOnGround = grounded;
        move.lastHorizontalCollision = event.horizontalCollision();
        move.lastSpecial = c.envFrom.special() || c.envTo.special();
        move.knockback = null;
        if (c.hardExempt) {
            move.skip = Math.max(move.skip, 2);
        } else if (move.skip > 0) {
            move.skip--;
        }
    }

    private static void onAttack(Player player, PlayerData data, GuardEvent.Attack attack) {
        post(data, "attack");
        if (ViolationManager.isExempt(player, data) || Exemptions.combat(data)) {
            data.tick.attackedIds.add(attack.entityId());
            return;
        }
        int ping = ViolationManager.ping(player, data);
        KILL_AURA.onAttack(player, data, attack.entityId(), ping, attack.cancelled());
        data.tick.attackedIds.add(attack.entityId());
        List<Vec3> eyes = eyes(player, data);
        double range = attackRange(player);
        List<AABB> boxes = targetBoxes(player, data, attack.entityId(), attack.nanos());
        REACH.onAttack(player, data, eyes, boxes, range, ping);
        HITBOX.onAttack(player, data, eyes.get(player.isSneaking() ? 1 : 0), boxes);
        data.tick.aims.add(new PlayerData.PendingAim(attack.entityId(), eyes, boxes, range));
        CRITICALS.onAttack(player, data);
    }

    private static void onDig(Player player, PlayerData data, GuardEvent.Dig dig) {
        boolean exempt = ViolationManager.isExempt(player, data);
        switch (dig.action()) {
            case START -> {
                post(data, "block breaking");
                data.tick.usedOrDug = true;
                data.digging = true;
                if (!exempt && player.getWorld().isChunkLoaded(dig.x() >> 4, dig.z() >> 4)) {
                    NUKER.onStartBreaking(player, data, dig.x(), dig.y(), dig.z(), dig.face(), eyes(player, data));
                    FAST_BREAK.onStart(player, data, player.getWorld().getBlockAt(dig.x(), dig.y(), dig.z()));
                }
            }
            case FINISH -> {
                post(data, "block breaking");
                data.tick.usedOrDug = true;
                data.digging = false;
                if (!exempt && player.getWorld().isChunkLoaded(dig.x() >> 4, dig.z() >> 4)) {
                    FAST_BREAK.onFinish(player, data, player.getWorld().getBlockAt(dig.x(), dig.y(), dig.z()));
                }
            }
            case ABORT -> {
                post(data, "block breaking");
                data.tick.usedOrDug = true;
                data.digging = false;
                FAST_BREAK.onAbort(data);
            }
            case STAB -> post(data, "spear attack");
            case DROP -> post(data, "item drop");
            case SWAP_HANDS -> {
                post(data, "hand swap");
                data.pendingOffhandTick = data.clientTick;
            }
            case RELEASE_USE -> data.usingItem = false;
            default -> {
            }
        }
    }

    private static void onPlace(Player player, PlayerData data, GuardEvent.Place place) {
        post(data, "block placement");
        data.tick.usedOrDug = true;
        if (ViolationManager.isExempt(player, data)) {
            return;
        }
        ItemStack item = player.getInventory().getItem(place.offhand() ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
        if (item.getType().isBlock() && !item.getType().isAir()) {
            Vec3 hit = new Vec3(place.x() + place.cursorX(), place.y() + place.cursorY(), place.z() + place.cursorZ());
            SCAFFOLD.onPlace(player, data, place.x(), place.y(), place.z(), place.face(), hit, place.insideBlock(), eyes(player, data));
        }
    }

    /** XRay and digging state from actual block breaks. */
    public static void onBlockBroken(Player player, Block block) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        data.digging = false;
        if (!ViolationManager.isExempt(player, data)) {
            XRAY.onBlockBroken(player, data, block, block.getType());
        }
    }
}
