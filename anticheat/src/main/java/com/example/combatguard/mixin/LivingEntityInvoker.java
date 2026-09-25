package com.example.combatguard.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {
    /** Jump strength attribute times the block's jump factor plus jump boost, exactly what a jump uses. */
    @Invoker("getJumpVelocity")
    float combatguard$getJumpVelocity();
}
