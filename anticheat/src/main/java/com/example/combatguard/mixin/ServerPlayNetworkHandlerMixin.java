package com.example.combatguard.mixin;

import com.example.combatguard.GuardEvents;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet handlers run twice: first on the network thread, where vanilla queues the packet for the server
 * thread and returns, then on the server thread. Checks run in the second call, which is in packet order;
 * only timing-sensitive code (timer, blink) uses the first.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Shadow
    private @Nullable Vec3d requestedTeleportPos;

    @Unique
    private boolean combatguard$hadTotem;

    @Unique
    private boolean combatguard$onServerThread() {
        return this.player.getEntityWorld().getServer().getPacketApplyBatcher().isOnThread();
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void combatguard$onPlayerMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onMove(this.player, packet, this.requestedTeleportPos != null, ci);
        }
    }

    @Inject(method = "onVehicleMove", at = @At("HEAD"))
    private void combatguard$onVehicleMove(VehicleMoveC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread() && this.requestedTeleportPos == null) {
            GuardEvents.onVehicleMove(this.player, packet);
        }
    }

    @Inject(method = "onTeleportConfirm", at = @At("HEAD"))
    private void combatguard$onTeleportConfirm(TeleportConfirmC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onTeleportConfirm(this.player);
        }
    }

    @Inject(method = "onPlayerInput", at = @At("HEAD"))
    private void combatguard$onPlayerInput(PlayerInputC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onInput(this.player, packet);
        }
    }

    @Inject(method = "onClientTickEnd", at = @At("HEAD"))
    private void combatguard$onClientTickEnd(ClientTickEndC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onTickEnd(this.player);
        } else {
            GuardEvents.onTickEndNetty(this.player);
        }
    }

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void combatguard$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onSwing(this.player);
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void combatguard$onPlayerInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onInteractEntity(this.player, packet, ci);
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("RETURN"))
    private void combatguard$afterPlayerInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.afterInteractEntity(this.player);
        }
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"))
    private void combatguard$onPlayerAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            this.combatguard$hadTotem = GuardEvents.hasOffhandTotem(this.player);
            GuardEvents.onPlayerAction(this.player, packet);
        }
    }

    @Inject(method = "onPlayerAction", at = @At("RETURN"))
    private void combatguard$afterPlayerAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread() && packet.getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            GuardEvents.afterInventoryChange(this.player, this.combatguard$hadTotem, "the swap key");
        }
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"))
    private void combatguard$onPlayerInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onInteractBlock(this.player, packet);
        }
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"))
    private void combatguard$onPlayerInteractItem(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onInteractItem(this.player);
        }
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void combatguard$onUpdateSelectedSlot(UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.onSelectedSlot(this.player);
        }
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"))
    private void combatguard$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            this.combatguard$hadTotem = GuardEvents.hasOffhandTotem(this.player);
            GuardEvents.onClickSlot(this.player, packet);
        }
    }

    @Inject(method = "onClickSlot", at = @At("RETURN"))
    private void combatguard$afterClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (combatguard$onServerThread()) {
            GuardEvents.afterInventoryChange(this.player, this.combatguard$hadTotem, "an inventory click");
        }
    }
}
