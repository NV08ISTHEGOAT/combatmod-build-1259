package com.example.combatguard;

import com.example.combatguard.check.Check;
import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.check.ViolationManager;
import com.example.combatguard.check.movement.MoveContext;
import com.example.combatguard.data.Exemptions;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import com.example.combatguard.mixin.EntityStatusS2CPacketAccessor;
import com.example.combatguard.mixin.LivingEntityInvoker;
import com.example.combatguard.mixin.PlayerMoveC2SPacketAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.combatguard.check.CheckRegistry.*;

/**
 * Entry point for everything the mixins observe. Unless a method says otherwise it runs on the server thread,
 * in the order the client sent the packets.
 */
public final class GuardEvents {
    /** Our ping ids all start with "CG" so they never collide with pings other mods send. */
    private static final int PING_PREFIX = 0x43470000;
    private static final AtomicInteger PING_COUNTER = new AtomicInteger();

    private GuardEvents() {
    }

    public static boolean isOurPing(int id) {
        return (id & 0xFFFF0000) == PING_PREFIX;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void onJoin(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.create(player);
        data.lastGameMode = player.interactionManager.getGameMode();
        data.lastScreenHandler = player.currentScreenHandler;
        CLIENT.onJoin(player, data);
    }

    public static void onLeave(ServerPlayerEntity player) {
        PlayerDataManager.remove(player);
    }

    public static void onRespawnOrWorldChange(ServerPlayerEntity player, boolean worldChange) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        if (worldChange) {
            data.lastWorldChangeTick = data.serverTicks;
        } else {
            data.lastRespawnTick = data.serverTicks;
        }
        data.move.invalidate(10);
        data.history.clear();
        data.digging = false;
    }

    public static void onServerTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            PlayerData data = PlayerDataManager.get(player);
            if (data == null) {
                continue;
            }
            data.serverTicks++;
            data.history.add(now, player.getBoundingBox());

            double speed = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED) * (player.isSprinting() ? 1.0 : 1.3);
            data.recordAttributes(speed, ((LivingEntityInvoker) player).combatguard$getJumpVelocity());

            Object gameMode = player.interactionManager.getGameMode();
            if (gameMode != data.lastGameMode) {
                data.lastGameMode = gameMode;
                data.lastGameModeChangeTick = data.serverTicks;
                data.move.invalidate(5);
            }
            if (player.currentScreenHandler != data.lastScreenHandler) {
                data.lastScreenHandler = player.currentScreenHandler;
                data.screenOpenedAtMs = now;
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
                sendTransaction(player.networkHandler, data, "rtt", d -> {
                });
            }

            if (data.movesSinceTickEnd > 40 && !data.legacyClient) {
                data.legacyClient = true;
                BAD_PACKETS.missingTickEnd(player, data);
            }
            CLIENT.tick(player, data);
        }
    }

    // ------------------------------------------------------------------ transactions

    private static void sendTransaction(ServerPlayNetworkHandler handler, PlayerData data, String kind,
                                        java.util.function.Consumer<PlayerData> onReply) {
        int id = PING_PREFIX | (PING_COUNTER.incrementAndGet() & 0xFFFF);
        data.addTransaction(id, new PlayerData.Transaction(kind, System.nanoTime(), data.serverTicks, onReply));
        handler.sendPacket(new CommonPingS2CPacket(id));
    }

    /** Any thread: a packet was just sent to this player. */
    public static void onPacketSent(ServerPlayNetworkHandler handler, Packet<?> packet) {
        ServerPlayerEntity player = handler.player;
        PlayerData data = player == null ? null : PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> inner : bundle.getPackets()) {
                onPacketSent(handler, inner);
            }
            return;
        }
        if (packet instanceof EntityVelocityUpdateS2CPacket velocity && velocity.getEntityId() == player.getId()) {
            Vec3d v = velocity.getVelocity();
            sendTransaction(handler, data, "velocity", d -> applyKnockback(d, v, false));
        } else if (packet instanceof ExplosionS2CPacket explosion && explosion.playerKnockback().isPresent()) {
            Vec3d v = explosion.playerKnockback().get();
            sendTransaction(handler, data, "velocity", d -> applyKnockback(d, v, true));
        } else if (packet instanceof EntityStatusS2CPacket status && status.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING
            && ((EntityStatusS2CPacketAccessor) status).combatguard$getEntityId() == player.getId()) {
            sendTransaction(handler, data, "totem", d -> d.totemSeenClientTick = d.clientTick);
        }
    }

    private static void applyKnockback(PlayerData data, Vec3d velocity, boolean additive) {
        PlayerData.MoveState move = data.move;
        if (additive && move.knockback != null) {
            move.knockback = move.knockback.add(velocity);
        } else {
            move.knockback = velocity;
            move.knockbackAdditive = additive;
        }
        data.lastVelocityTick = data.serverTicks;
    }

    /** Network thread, before the pong is queued for the server thread. */
    public static void onPongNetty(ServerPlayNetworkHandler handler) {
        BAD_PACKETS.onPongNetty(handler.player.getEntityWorld().getServer(), handler.player.getUuid());
    }

    public static void onPong(ServerPlayerEntity player, int id) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        PlayerData.Transaction transaction = data.takeTransaction(id);
        if (transaction == null) {
            return;
        }
        int rtt = (int) ((System.nanoTime() - transaction.sentNanos()) / 1_000_000L);
        data.transactionRttMs = data.transactionRttMs == 0 ? rtt : (data.transactionRttMs * 3 + rtt) / 4;
        transaction.onReply().accept(data);
    }

    // ------------------------------------------------------------------ client ticks

    /** Network thread. */
    public static void onTickEndNetty(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        data.sawTickEnd = true;
        TIMER.onTickEndNetty(player.getEntityWorld().getServer(), player.getUuid());
        BAD_PACKETS.onTickEndNetty(data);
    }

    public static void onTickEnd(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
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

    // ------------------------------------------------------------------ movement

    public static void onMove(ServerPlayerEntity player, PlayerMoveC2SPacket packet, boolean teleportPending, CallbackInfo ci) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        boolean teleportReply = data.expectTeleportMove;
        data.expectTeleportMove = false;
        if (!teleportReply) {
            data.tick.moved = true;
            data.tick.movePackets++;
            data.movesSinceTickEnd++;
        }
        if (packet.changesLook()) {
            float pitch = packet.getPitch(0.0F);
            if (Float.isFinite(pitch) && Math.abs(pitch) > 90.0001F) {
                BAD_PACKETS.invalidPitch(player, data, pitch);
            }
        }

        PlayerData.MoveState move = data.move;
        if (teleportPending || teleportReply || !player.networkHandler.canInteractWithGame()) {
            // Vanilla ignores this position or it is the answer to a teleport.
            move.invalidate(2);
            move.lastOnGround = packet.isOnGround();
            return;
        }
        if (!packet.changesPosition()) {
            // Moved less than the client's 0.03 threshold; the next position packet covers several ticks.
            move.lastOnGround = packet.isOnGround();
            return;
        }
        Vec3d from = player.getEntityPos();
        Vec3d to = new Vec3d(packet.getX(from.x), packet.getY(from.y), packet.getZ(from.z));
        if (!Double.isFinite(to.x) || !Double.isFinite(to.y) || !Double.isFinite(to.z)) {
            return;
        }
        int ticks = data.legacyClient ? 1 : move.ticksSincePosition;
        move.ticksSincePosition = 0;

        MoveContext c = new MoveContext(player, data, packet, from, to, ticks);
        if (!ViolationManager.isExempt(player, data) && !(data.legacyClient && BAD_PACKETS.allowLegacyClients())) {
            double jumpVelocity = ((LivingEntityInvoker) player).combatguard$getJumpVelocity();
            SPEED.process(c);
            FLIGHT.process(c);
            JUMP.process(c, data.maxRecentJump(jumpVelocity), data.minRecentJump(jumpVelocity));
            GROUND_SPOOF.process(c);
            CLIMB.process(c);
            PHASE.process(c);
            SPRINT.process(c);
            ELYTRA.process(c);
            VELOCITY.process(c);
        }

        if (c.forceAirborne) {
            ((PlayerMoveC2SPacketAccessor) packet).combatguard$setOnGround(false);
        }
        if (c.setback) {
            player.networkHandler.requestTeleport(from.x, from.y, from.z, player.getYaw(), player.getPitch());
            data.lastTeleportTick = data.serverTicks;
            move.invalidate(3);
            ci.cancel();
            return;
        }

        move.valid = true;
        move.lastSingleTick = c.ticks == 1;
        move.lastDx = c.dx;
        move.lastDz = c.dz;
        move.lastHorizontal = c.horizontal;
        move.lastFriction = c.ticks == 1 ? c.friction() : 0.91;
        move.lastDy = c.onGround && !c.forceAirborne ? 0.0 : (c.ticks == 1 ? c.dy : c.dy / c.ticks);
        move.lastOnGround = c.onGround && !c.forceAirborne;
        move.lastHorizontalCollision = packet.horizontalCollision();
        move.knockback = null;
        move.sprintHitSlowdown = false;
        move.lastSpecial = c.envFrom.special() || c.envTo.special();
        if (c.hardExempt) {
            move.skip = Math.max(move.skip, 2);
        } else if (move.skip > 0) {
            move.skip--;
        }
    }

    public static void onVehicleMove(ServerPlayerEntity player, VehicleMoveC2SPacket packet) {
        PlayerData data = PlayerDataManager.get(player);
        Entity vehicle = player.getRootVehicle();
        if (data == null || vehicle == player || ViolationManager.isExempt(player, data) || Exemptions.movement(player, data)) {
            return;
        }
        Vec3d to = packet.position();
        if (Double.isFinite(to.x) && Double.isFinite(to.y) && Double.isFinite(to.z)) {
            VEHICLE.onVehicleMove(player, data, vehicle, to);
        }
    }

    public static void onTeleportConfirm(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data != null) {
            data.expectTeleportMove = true;
            data.lastTeleportTick = data.serverTicks;
            data.move.invalidate(3);
        }
    }

    public static void onInput(ServerPlayerEntity player, PlayerInputC2SPacket packet) {
        PlayerData data = PlayerDataManager.get(player);
        if (data != null && !packet.input().equals(player.getPlayerInput())) {
            data.lastInputChangeTick = data.clientTick;
        }
    }

    // ------------------------------------------------------------------ combat

    /** Eye positions the client may have used: current pose, standing and sneaking. */
    private static List<Vec3d> eyes(ServerPlayerEntity player) {
        Vec3d pos = player.getEntityPos();
        List<Vec3d> eyes = new ArrayList<>(3);
        eyes.add(player.getEyePos());
        eyes.add(pos.add(0.0, player.getEyeHeight(EntityPose.STANDING), 0.0));
        eyes.add(pos.add(0.0, player.getEyeHeight(EntityPose.CROUCHING), 0.0));
        return eyes;
    }

    /** Where the attacker could have seen the target, given the attacker's latency. */
    private static List<Box> targetBoxes(ServerPlayerEntity attacker, PlayerData attackerData, Entity target) {
        int ping = ViolationManager.ping(attacker, attackerData);
        List<Box> boxes = new ArrayList<>();
        double margin = target.getTargetingMargin();
        if (target instanceof ServerPlayerEntity targetPlayer && PlayerDataManager.get(targetPlayer) != null) {
            long since = System.currentTimeMillis() - (long) (ping * 1.5) - 300L;
            boxes.addAll(PlayerDataManager.get(targetPlayer).history.since(since));
        } else {
            // Mobs are not recorded; widen their box by how far they can have moved during the latency window.
            margin += Math.min(1.0, 0.1 + target.getVelocity().horizontalLength() * (ping / 50.0 + 4.0));
        }
        Box current = target.getBoundingBox();
        boxes.add(current);
        boxes.add(current.offset(target.lastX - target.getX(), target.lastY - target.getY(), target.lastZ - target.getZ()));
        if (margin > 0.0) {
            List<Box> expanded = new ArrayList<>(boxes.size());
            for (Box box : boxes) {
                expanded.add(box.expand(margin));
            }
            return expanded;
        }
        return boxes;
    }

    public static void onInteractEntity(ServerPlayerEntity player, PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null || !player.networkHandler.canInteractWithGame()) {
            return;
        }
        boolean[] attack = {false};
        packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
            @Override
            public void interact(Hand hand) {
            }

            @Override
            public void interactAt(Hand hand, Vec3d pos) {
            }

            @Override
            public void attack() {
                attack[0] = true;
            }
        });
        if (data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = attack[0] ? "attack" : "entity interaction";
        }
        if (!attack[0]) {
            data.tick.usedOrDug = true;
            return;
        }
        Entity target = packet.getEntity(player.getEntityWorld());
        if (target == null || ViolationManager.isExempt(player, data) || Exemptions.combat(data)) {
            if (target != null) {
                data.tick.attackedIds.add(target.getId());
            }
            return;
        }
        int ping = ViolationManager.ping(player, data);
        boolean cancel = KILL_AURA.onAttack(player, data, target.getId(), ping);
        data.tick.attackedIds.add(target.getId());
        data.sprintingBeforeAttack = player.isSprinting();

        List<Vec3d> eyes = eyes(player);
        AttackRangeComponent range = player.getAttackRange();
        double limit = range.getEffectiveMaxRange(player) + range.hitboxMargin();
        List<Box> boxes = targetBoxes(player, data, target);
        cancel |= REACH.onAttack(player, data, eyes, boxes, limit);
        HITBOX.onAttack(player, data, player.getEyePos(), boxes);
        data.tick.aims.add(new PlayerData.PendingAim(target.getId(), eyes, boxes, limit));
        CRITICALS.onAttack(player, data);
        if (cancel) {
            ci.cancel();
        }
    }

    public static void afterInteractEntity(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data != null && data.sprintingBeforeAttack && !player.isSprinting()) {
            data.move.sprintHitSlowdown = true;
        }
        if (data != null) {
            data.sprintingBeforeAttack = false;
        }
    }

    public static void onSwing(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        data.tick.swings++;
        if (data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = "arm swing";
        }
    }

    // ------------------------------------------------------------------ world

    public static void onPlayerAction(ServerPlayerEntity player, PlayerActionC2SPacket packet) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        String post = null;
        ServerWorld world = player.getEntityWorld();
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> {
                post = "block breaking";
                data.tick.usedOrDug = true;
                data.digging = true;
                BlockPos pos = packet.getPos();
                if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) && !ViolationManager.isExempt(player, data)) {
                    NUKER.onStartBreaking(player, data, pos, packet.getDirection(), eyes(player));
                    FAST_BREAK.onStart(player, data, pos, world.getBlockState(pos));
                }
            }
            case STOP_DESTROY_BLOCK -> {
                post = "block breaking";
                data.tick.usedOrDug = true;
                data.digging = false;
                BlockPos pos = packet.getPos();
                if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) && !ViolationManager.isExempt(player, data)) {
                    FAST_BREAK.onFinish(player, data, pos, world.getBlockState(pos));
                }
            }
            case ABORT_DESTROY_BLOCK -> {
                post = "block breaking";
                data.tick.usedOrDug = true;
                data.digging = false;
                FAST_BREAK.onAbort(data);
            }
            case STAB -> post = "spear attack";
            case DROP_ITEM, DROP_ALL_ITEMS -> post = "item drop";
            case SWAP_ITEM_WITH_OFFHAND -> post = "hand swap";
            default -> {
            }
        }
        if (post != null && data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = post;
        }
    }

    public static void onInteractBlock(ServerPlayerEntity player, PlayerInteractBlockC2SPacket packet) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null || !player.networkHandler.canInteractWithGame()) {
            return;
        }
        data.tick.usedOrDug = true;
        if (data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = "block placement";
        }
        if (player.getStackInHand(packet.getHand()).getItem() instanceof BlockItem && !ViolationManager.isExempt(player, data)) {
            SCAFFOLD.onPlace(player, data, packet.getBlockHitResult(), eyes(player));
        }
    }

    public static void onInteractItem(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        data.tick.usedOrDug = true;
        if (data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = "item use";
        }
    }

    public static void onBlockBroken(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        data.digging = false;
        if (!ViolationManager.isExempt(player, data)) {
            XRAY.onBlockBroken(player, data, pos, state);
        }
    }

    // ------------------------------------------------------------------ inventory

    public static void onSelectedSlot(ServerPlayerEntity player) {
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return;
        }
        data.tick.slotChanges++;
        if (data.tick.moved && data.tick.interactionAfterMove == null) {
            data.tick.interactionAfterMove = "hotbar change";
        }
    }

    public static void onClickSlot(ServerPlayerEntity player, ClickSlotC2SPacket packet) {
        PlayerData data = PlayerDataManager.get(player);
        if (data != null && !ViolationManager.isExempt(player, data)) {
            INVENTORY.onClickSlot(player, data, packet);
        }
    }

    public static boolean hasOffhandTotem(ServerPlayerEntity player) {
        return player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    /** After a click or hand swap: did it just put a totem in the offhand? */
    public static void afterInventoryChange(ServerPlayerEntity player, boolean hadTotem, String how) {
        PlayerData data = PlayerDataManager.get(player);
        if (data != null && !hadTotem && hasOffhandTotem(player) && !ViolationManager.isExempt(player, data)) {
            AUTO_TOTEM.onTotemEquipped(player, data, how);
        }
    }

    public static void onReport(ServerPlayerEntity player, String json) {
        PlayerData data = PlayerDataManager.get(player);
        if (data != null) {
            CLIENT.onReport(player, data, json);
        }
    }
}
