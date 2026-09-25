package com.example.combatguard;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.client.ClientChannel;
import com.example.combatguard.command.GuardCommand;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.stream.NettyListener;
import com.example.combatguard.stream.Processor;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class CombatGuardPlugin extends JavaPlugin implements Listener {
    private static CombatGuardPlugin instance;
    private static volatile GuardConfig config;
    private PacketListenerCommon packetListener;

    public static CombatGuardPlugin instance() {
        return instance;
    }

    public static GuardConfig config() {
        return config;
    }

    public void reloadGuardConfig() {
        File file = new File(getDataFolder(), "config.yml");
        GuardConfig loaded = GuardConfig.load(file, getLogger());
        loaded.save(file, getLogger());
        config = loaded;
    }

    @Override
    public void onEnable() {
        instance = this;
        AlertManager.init(getLogger(), getDataFolder());
        reloadGuardConfig();

        packetListener = PacketEvents.getAPI().getEventManager().registerListener(new NettyListener(), PacketListenerPriority.LOWEST);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ClientChannel.CHALLENGE);
        getServer().getMessenger().registerIncomingPluginChannel(this, ClientChannel.REPORT, new ClientChannel());

        PluginCommand command = getCommand("combatguard");
        if (command != null) {
            GuardCommand executor = new GuardCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            join(player);
        }
        getLogger().info("CombatGuard enabled with " + CheckRegistry.all().size() + " checks");
    }

    @Override
    public void onDisable() {
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        PlayerDataManager.clear();
        AlertManager.shutdown();
    }

    private static void join(Player player) {
        PlayerData data = PlayerDataManager.create(player);
        data.position = new Vec3(player.getX(), player.getY(), player.getZ());
        data.lastGameMode = player.getGameMode();
        data.alerts = true;
        if (ClientChannel.hasClientMod(player)) {
            CheckRegistry.CLIENT.onChannelRegistered(player, data);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerDataManager.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = PlayerDataManager.get(event.getPlayer());
        if (data != null) {
            data.lastRespawnTick = data.serverTicks;
            data.move.invalidate(10);
            data.history.clear();
            data.digging = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = PlayerDataManager.get(event.getPlayer());
        if (data != null) {
            data.lastWorldChangeTick = data.serverTicks;
            data.move.invalidate(10);
            data.history.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        PlayerData data = PlayerDataManager.get(event.getPlayer());
        if (data != null) {
            data.lastGameModeChangeTick = data.serverTicks;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = PlayerDataManager.get(player);
            if (data != null) {
                data.containerOpenedAtMs = System.currentTimeMillis();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Processor.onBlockBroken(event.getPlayer(), event.getBlock());
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (ClientChannel.CHALLENGE.equals(event.getChannel())) {
            PlayerData data = PlayerDataManager.get(event.getPlayer());
            if (data != null) {
                CheckRegistry.CLIENT.onChannelRegistered(event.getPlayer(), data);
            }
        }
    }

    @EventHandler
    public void onTickStart(ServerTickStartEvent event) {
        Processor.onTickStart();
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        Processor.onTickEnd();
    }
}
