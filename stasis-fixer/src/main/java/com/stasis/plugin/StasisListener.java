package com.stasis.plugin;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

public class StasisListener implements Listener {

    private final BobberEntityManager manager;

    public StasisListener(StasisPlugin plugin) {
        this.manager = plugin.getBobberManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        switch (event.getState()) {
            case FISHING -> manager.onCast(event.getPlayer(), event.getHook());
            // A bobber resting on a pressure plate is "in ground", so IN_GROUND is the usual
            // reel-in state here; every retrieve state counts as reeling in.
            case REEL_IN, IN_GROUND, CAUGHT_ENTITY, CAUGHT_FISH, FAILED_ATTEMPT ->
                    manager.onReelIn(event.getPlayer().getUniqueId());
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // The hook is discarded on quit; the holder stays until the player recasts or reels in.
        manager.forgetPendingHook(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        // The holder spawns inside the bobber, so stop the bobber from hooking onto it.
        Entity hit = event.getHitEntity();
        if (hit != null && BobberEntityManager.isHolder(hit)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (BobberEntityManager.isHolder(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (BobberEntityManager.isHolder(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        manager.onEntitiesLoad(event.getEntities());
    }
}
