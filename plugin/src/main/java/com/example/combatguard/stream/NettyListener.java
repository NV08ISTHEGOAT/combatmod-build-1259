package com.example.combatguard.stream;

import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.GuardMath;
import com.example.combatguard.math.Vec3;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs on each player's network thread. It turns packets into {@link GuardEvent}s in arrival order and does the
 * few things that cannot wait for the server thread: timer and blink timestamps, dropping impossible attacks,
 * following knockback / totem packets with a ping, and rewriting spoofed ground packets.
 */
public final class NettyListener implements PacketListener {
    private static final int MAX_QUEUED = 4000;

    private static void enqueue(PlayerData data, GuardEvent event) {
        if (data.queued.incrementAndGet() > MAX_QUEUED) {
            // The server thread is far behind (extreme lag). Drop instead of growing without bound.
            data.queued.decrementAndGet();
            return;
        }
        data.queue.add(event);
    }

    private static GuardMath.Face face(BlockFace face) {
        return switch (face) {
            case DOWN -> GuardMath.Face.DOWN;
            case NORTH -> GuardMath.Face.NORTH;
            case SOUTH -> GuardMath.Face.SOUTH;
            case WEST -> GuardMath.Face.WEST;
            case EAST -> GuardMath.Face.EAST;
            default -> GuardMath.Face.UP;
        };
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        PlayerData data = PlayerDataManager.get(event.getUser().getUUID());
        if (data == null) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        long now = System.nanoTime();
        if (type == PacketType.Play.Client.PLAYER_FLYING || type == PacketType.Play.Client.PLAYER_POSITION
            || type == PacketType.Play.Client.PLAYER_ROTATION || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
            Location l = w.getLocation();
            boolean onGround = w.isOnGround();
            if (w.hasPositionChanged()) {
                data.nettyPosition = new Vec3(l.getX(), l.getY(), l.getZ());
            }
            enqueue(data, new GuardEvent.Move(now, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(),
                w.hasPositionChanged(), w.hasRotationChanged(), onGround, w.isHorizontalCollision()));
            if (data.forceAirborne && onGround) {
                w.setOnGround(false);
                event.markForReEncode(true);
            }
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            data.sawTickEnd = true;
            CheckRegistry.TIMER.onTickEndNetty(data, now);
            CheckRegistry.BAD_PACKETS.onTickEndNetty(data);
            data.nettyTickTargets.clear();
            enqueue(data, new GuardEvent.TickEnd(now));
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            WrapperPlayClientPlayerInput w = new WrapperPlayClientPlayerInput(event);
            data.nettySneaking = w.isShift();
            enqueue(data, new GuardEvent.Input(now, w.isForward(), w.isBackward(), w.isLeft(), w.isRight(), w.isJump(), w.isShift(), w.isSprint()));
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                boolean cancel = shouldCancelAttack(data, w.getEntityId(), now);
                if (cancel) {
                    event.setCancelled(true);
                }
                enqueue(data, new GuardEvent.Attack(now, w.getEntityId(), cancel));
            } else {
                enqueue(data, new GuardEvent.Interact(now, w.getEntityId()));
            }
        } else if (type == PacketType.Play.Client.ANIMATION) {
            enqueue(data, new GuardEvent.Swing(now));
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(event);
            Vector3i p = w.getBlockPosition();
            GuardEvent.DigAction action = switch (w.getAction()) {
                case START_DIGGING -> GuardEvent.DigAction.START;
                case CANCELLED_DIGGING -> GuardEvent.DigAction.ABORT;
                case FINISHED_DIGGING -> GuardEvent.DigAction.FINISH;
                case STAB -> GuardEvent.DigAction.STAB;
                case DROP_ITEM, DROP_ITEM_STACK -> GuardEvent.DigAction.DROP;
                case SWAP_ITEM_WITH_OFFHAND -> GuardEvent.DigAction.SWAP_HANDS;
                case RELEASE_USE_ITEM -> GuardEvent.DigAction.RELEASE_USE;
                default -> GuardEvent.DigAction.OTHER;
            };
            enqueue(data, new GuardEvent.Dig(now, action, p.getX(), p.getY(), p.getZ(), face(w.getBlockFace())));
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement w = new WrapperPlayClientPlayerBlockPlacement(event);
            Vector3i p = w.getBlockPosition();
            Vector3f c = w.getCursorPosition();
            enqueue(data, new GuardEvent.Place(now, p.getX(), p.getY(), p.getZ(), face(w.getFace()), c.getX(), c.getY(), c.getZ(),
                w.getHand() == InteractionHand.OFF_HAND, w.getInsideBlock().orElse(false)));
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem w = new WrapperPlayClientUseItem(event);
            enqueue(data, new GuardEvent.UseItem(now, w.getHand() == InteractionHand.OFF_HAND));
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow w = new WrapperPlayClientClickWindow(event);
            GuardEvent.ClickType clickType = switch (w.getWindowClickType()) {
                case PICKUP -> GuardEvent.ClickType.PICKUP;
                case QUICK_MOVE -> GuardEvent.ClickType.QUICK_MOVE;
                case SWAP -> GuardEvent.ClickType.SWAP;
                case CLONE -> GuardEvent.ClickType.CLONE;
                case THROW -> GuardEvent.ClickType.THROW;
                case QUICK_CRAFT -> GuardEvent.ClickType.QUICK_CRAFT;
                case PICKUP_ALL -> GuardEvent.ClickType.PICKUP_ALL;
                default -> GuardEvent.ClickType.OTHER;
            };
            enqueue(data, new GuardEvent.Click(now, w.getWindowId(), w.getSlot(), w.getButton(), clickType));
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            enqueue(data, new GuardEvent.CloseWindow(now));
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            enqueue(data, new GuardEvent.SlotChange(now, new WrapperPlayClientHeldItemChange(event).getSlot()));
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
            GuardEvent.SprintAction action = switch (w.getAction()) {
                case START_SPRINTING -> GuardEvent.SprintAction.START_SPRINT;
                case STOP_SPRINTING -> GuardEvent.SprintAction.STOP_SPRINT;
                case START_FLYING_WITH_ELYTRA -> GuardEvent.SprintAction.START_GLIDE;
                default -> GuardEvent.SprintAction.OTHER;
            };
            enqueue(data, new GuardEvent.EntityAction(now, action));
        } else if (type == PacketType.Play.Client.VEHICLE_MOVE) {
            Vector3d p = new WrapperPlayClientVehicleMove(event).getPosition();
            enqueue(data, new GuardEvent.VehicleMove(now, p.getX(), p.getY(), p.getZ()));
        } else if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            enqueue(data, new GuardEvent.TeleportConfirm(now, new WrapperPlayClientTeleportConfirm(event).getTeleportId()));
        } else if (type == PacketType.Play.Client.PONG) {
            int id = new WrapperPlayClientPong(event).getId();
            if (Transactions.isOurs(id)) {
                CheckRegistry.BAD_PACKETS.onPongNetty(data, now);
                enqueue(data, new GuardEvent.Pong(now, id));
                event.setCancelled(true);
            }
        }
    }

    /** Multi-target attacks in one tick and hits far beyond reach are dropped before the server applies them. */
    private static boolean shouldCancelAttack(PlayerData data, int targetId, long nanos) {
        boolean otherTarget = false;
        for (int id : data.nettyTickTargets) {
            if (id != targetId) {
                otherTarget = true;
                break;
            }
        }
        data.nettyTickTargets.add(targetId);
        if (otherTarget && CheckRegistry.KILL_AURA.enabled() && CheckRegistry.KILL_AURA.settings().mitigate) {
            return true;
        }
        PlayerData target = PlayerDataManager.byEntityId(targetId);
        Vec3 position = data.nettyPosition;
        if (target == null || position == null) {
            return false;
        }
        long nowMs = System.currentTimeMillis() - (System.nanoTime() - nanos) / 1_000_000L;
        List<AABB> boxes = target.history.since(nowMs - (long) (data.transactionRttMs * 1.5) - 300L);
        List<Vec3> eyes = new ArrayList<>(2);
        eyes.add(position.add(0.0, 1.62, 0.0));
        eyes.add(position.add(0.0, data.nettySneaking ? 1.27 : 1.62, 0.0));
        eyes.add(position.add(0.0, 0.4, 0.0));
        return CheckRegistry.REACH.shouldCancel(eyes, boxes, data.cachedAttackRange);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getUser() == null || event.getUser().getUUID() == null) {
            return;
        }
        PlayerData data = PlayerDataManager.get(event.getUser().getUUID());
        if (data == null) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        Object player = event.getPlayer();
        if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity w = new WrapperPlayServerEntityVelocity(event);
            if (w.getEntityId() == data.entityId) {
                Vector3d v = w.getVelocity();
                Vec3 velocity = new Vec3(v.getX(), v.getY(), v.getZ());
                event.getTasksAfterSend().add(() -> Transactions.send(player, data, "velocity", d -> Processor.applyKnockback(d, velocity, false)));
            }
        } else if (type == PacketType.Play.Server.EXPLOSION) {
            WrapperPlayServerExplosion w = new WrapperPlayServerExplosion(event);
            Vector3d v = w.getKnockback();
            if (v != null && (v.getX() != 0.0 || v.getY() != 0.0 || v.getZ() != 0.0)) {
                Vec3 velocity = new Vec3(v.getX(), v.getY(), v.getZ());
                event.getTasksAfterSend().add(() -> Transactions.send(player, data, "velocity", d -> Processor.applyKnockback(d, velocity, true)));
            }
        } else if (type == PacketType.Play.Server.ENTITY_STATUS) {
            WrapperPlayServerEntityStatus w = new WrapperPlayServerEntityStatus(event);
            if (w.getStatus() == 35 && w.getEntityId() == data.entityId) {
                event.getTasksAfterSend().add(() -> Transactions.send(player, data, "totem", d -> d.totemSeenClientTick = d.clientTick));
            }
        } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            enqueue(data, new GuardEvent.TeleportSent(System.nanoTime(), new WrapperPlayServerPlayerPositionAndLook(event).getTeleportId()));
        }
    }
}
