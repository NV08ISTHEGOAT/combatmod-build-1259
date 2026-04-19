package com.example.combatmod.mixin;
import com.example.combatmod.ModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public class EntityInvisibilityMixin {
    @Inject(method="isInvisibleTo", at=@At("HEAD"), cancellable=true)
    private void combatmod$revealInvisible(PlayerEntity observer, CallbackInfoReturnable<Boolean> cir){
        if(ModConfig.invisRevealEnabled) cir.setReturnValue(false);
    }
}
