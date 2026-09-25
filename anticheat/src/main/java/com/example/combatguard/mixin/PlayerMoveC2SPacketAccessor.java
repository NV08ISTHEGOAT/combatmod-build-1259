package com.example.combatguard.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the ground spoof check treat a faked "on ground" packet as airborne, so fall damage still applies. */
@Mixin(PlayerMoveC2SPacket.class)
public interface PlayerMoveC2SPacketAccessor {
    @Mutable
    @Accessor("onGround")
    void combatguard$setOnGround(boolean onGround);
}
