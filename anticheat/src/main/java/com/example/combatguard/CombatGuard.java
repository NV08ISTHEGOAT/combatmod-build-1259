package com.example.combatguard;

import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.command.GuardCommand;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerDataManager;
import com.example.combatguard.network.GuardPayloads;
import com.example.combatguard.util.ServerLag;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CombatGuard implements ModInitializer {
    public static final String MOD_ID = "combatguard";
    private static final Logger LOGGER = LoggerFactory.getLogger("CombatGuard");
    private static volatile GuardConfig config = new GuardConfig();

    public static GuardConfig config() {
        return config;
    }

    public static void reloadConfig() {
        GuardConfig loaded = GuardConfig.load();
        CheckRegistry.applyDefaults(loaded);
        loaded.save();
        config = loaded;
    }

    @Override
    public void onInitialize() {
        reloadConfig();
        GuardPayloads.register();

        ServerTickEvents.START_SERVER_TICK.register(server -> ServerLag.onTickStart());
        ServerTickEvents.END_SERVER_TICK.register(GuardEvents::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PlayerDataManager.clear();
            ServerLag.reset();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> GuardEvents.onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GuardEvents.onLeave(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> GuardEvents.onRespawnOrWorldChange(newPlayer, false));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
            GuardEvents.onRespawnOrWorldChange(player, true));
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                GuardEvents.onBlockBroken(serverPlayer, pos, state);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(GuardPayloads.Report.ID,
            (payload, context) -> GuardEvents.onReport(context.player(), payload.json()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> GuardCommand.register(dispatcher));
        LOGGER.info("CombatGuard loaded with {} checks", CheckRegistry.all().size());
    }
}
