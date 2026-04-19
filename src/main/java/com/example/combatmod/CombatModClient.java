package com.example.combatmod;
import com.example.combatmod.gui.CombatMenuScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
public class CombatModClient implements ClientModInitializer {
    public static final String MOD_ID = "combatmod";
    public static final Identifier REACH_MODIFIER_ID = Identifier.of(MOD_ID, "reach_modifier");
    private static KeyBinding menuKey;
    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.combatmod.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.combatmod.general"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new CombatMenuScreen());
            }
            if (client.player != null && client.world != null) applyReachModifier(client.player);
        });
    }
    public static void applyReachModifier(PlayerEntity player) {
        EntityAttributeInstance blockRange = player.getAttributeInstance(EntityAttributes.BLOCK_INTERACTION_RANGE);
        EntityAttributeInstance entityRange = player.getAttributeInstance(EntityAttributes.ENTITY_INTERACTION_RANGE);
        if (blockRange == null || entityRange == null) return;
        blockRange.removeModifier(REACH_MODIFIER_ID);
        entityRange.removeModifier(REACH_MODIFIER_ID);
        if (ModConfig.reachEnabled) {
            blockRange.addTemporaryModifier(new EntityAttributeModifier(REACH_MODIFIER_ID,
                ModConfig.reachDistance - blockRange.getBaseValue(), EntityAttributeModifier.Operation.ADD_VALUE));
            entityRange.addTemporaryModifier(new EntityAttributeModifier(REACH_MODIFIER_ID,
                ModConfig.reachDistance - entityRange.getBaseValue(), EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }
}
