package com.stasis.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class StasisPlugin extends JavaPlugin {

    private BobberEntityManager bobberManager;

    @Override
    public void onEnable() {
        bobberManager = new BobberEntityManager(this);
        bobberManager.loadData();
        // Safe on startup and after /reload: picks up bobbers and holders already in the world.
        bobberManager.resumeAfterReload();
        getServer().getPluginManager().registerEvents(new StasisListener(this), this);
        getServer().getScheduler().runTaskTimer(this, bobberManager::tick, 1L, 1L);
        getLogger().info("StasisPlugin enabled.");
    }

    @Override
    public void onDisable() {
        // Holders are persistent entities saved with their chunk; only our records need saving.
        if (bobberManager != null) {
            bobberManager.saveData();
        }
        getLogger().info("StasisPlugin disabled.");
    }

    public BobberEntityManager getBobberManager() {
        return bobberManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> {
                bobberManager.listHolders(sender);
                return true;
            }
            case "clearnearby" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this.");
                    return true;
                }
                double radius = 5;
                if (args.length > 1) {
                    try {
                        radius = Math.min(64, Math.max(1, Double.parseDouble(args[1])));
                    } catch (NumberFormatException e) {
                        sender.sendMessage("Invalid radius: " + args[1]);
                        return true;
                    }
                }
                int removed = bobberManager.clearNearby(player.getLocation(), radius);
                sender.sendMessage("Removed " + removed + " bobber holder(s) within " + radius + " blocks.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
