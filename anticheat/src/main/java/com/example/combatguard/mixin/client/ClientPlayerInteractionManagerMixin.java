package com.example.combatguard.mixin.client;

import com.example.combatguard.client.CallOriginGuard;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void combatguard$attackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.ATTACK_ENTITY);
    }

    @Inject(method = "attackBlock", at = @At("HEAD"))
    private void combatguard$attackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.ATTACK_BLOCK);
    }

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void combatguard$interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.INTERACT_BLOCK);
    }

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void combatguard$interactItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.INTERACT_ITEM);
    }

    @Inject(method = "interactEntity", at = @At("HEAD"))
    private void combatguard$interactEntity(PlayerEntity player, Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.INTERACT_ENTITY);
    }
}
