package com.example.combatguard.mixin;

import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStatusS2CPacket.class)
public interface EntityStatusS2CPacketAccessor {
    @Accessor("entityId")
    int combatguard$getEntityId();
}
