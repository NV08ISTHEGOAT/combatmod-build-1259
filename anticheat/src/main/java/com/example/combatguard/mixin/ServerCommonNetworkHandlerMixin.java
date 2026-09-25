package com.example.combatguard.mixin;

import com.example.combatguard.GuardEvents;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.PacketApplyBatcher;
import net.minecraft.network.listener.ServerCommonPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    @Shadow
    @Final
    protected MinecraftServer server;

    /** Follows knockback, explosion and totem packets with a ping (transaction). */
    @Inject(method = "send", at = @At("TAIL"))
    private void combatguard$afterSend(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayNetworkHandler handler) {
            GuardEvents.onPacketSent(handler, packet);
        }
    }

    /**
     * Vanilla handles pongs on the network thread and ignores them. Ours are re-queued on the server thread so
     * they are processed in order with the movement packets around them.
     */
    @Inject(method = "onPong", at = @At("HEAD"), cancellable = true)
    private void combatguard$onPong(CommonPongC2SPacket packet, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayNetworkHandler handler) || !GuardEvents.isOurPing(packet.getParameter())) {
            return;
        }
        PacketApplyBatcher batcher = this.server.getPacketApplyBatcher();
        if (batcher.isOnThread()) {
            GuardEvents.onPong(handler.player, packet.getParameter());
        } else {
            GuardEvents.onPongNetty(handler);
            batcher.add((ServerCommonPacketListener) (Object) this, packet);
        }
        ci.cancel();
    }
}
