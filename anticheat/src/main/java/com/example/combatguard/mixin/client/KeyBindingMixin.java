package com.example.combatguard.mixin.client;

import com.example.combatguard.client.CallOriginGuard;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Autoclickers inside the game often fake key presses instead of calling attack code directly. */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Inject(method = "onKeyPressed", at = @At("HEAD"))
    private static void combatguard$onKeyPressed(InputUtil.Key key, CallbackInfo ci) {
        CallOriginGuard.verify(CallOriginGuard.Sensitive.KEY_PRESSED);
    }
}
