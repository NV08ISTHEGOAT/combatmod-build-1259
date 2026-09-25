package com.example.combatguard.mixin.client;

import com.example.combatguard.client.CallOriginGuard;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"))
    private void combatguard$doAttack(CallbackInfoReturnable<Boolean> cir) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.DO_ATTACK);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"))
    private void combatguard$doItemUse(CallbackInfo ci) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.DO_ITEM_USE);
    }
}
