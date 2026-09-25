package me.nv08.orbitalstrike;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Casting a cannon rod fires a strike at the block you're looking at instead of throwing a bobber. */
public final class RodListener implements Listener {

    private final OrbitalStrikePlugin plugin;
    private final Map<UUID, Long> lastStrike = new HashMap<>();

    public RodListener(OrbitalStrikePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCast(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING) {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = castingHand(event);
        ItemStack rod = player.getInventory().getItem(hand);
        StrikeType type = plugin.items().typeOf(rod);
        if (type == null) {
            return;
        }
        // A cannon never throws a bobber, even when it can't fire.
        event.setCancelled(true);

        if (!player.hasPermission("orbitalstrike.use")) {
            plugin.send(player, "no-permission");
            return;
        }

        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        if (settings.cooldownSeconds() > 0 && !player.hasPermission("orbitalstrike.bypasscooldown")) {
            long readyAt = lastStrike.getOrDefault(player.getUniqueId(), 0L) + settings.cooldownSeconds() * 1000L;
            if (now < readyAt) {
                long seconds = (readyAt - now + 999) / 1000;
                plugin.send(player, "cooldown", Placeholder.unparsed("seconds", String.valueOf(seconds)));
                return;
            }
        }

        Block target = plugin.strikes().findTarget(player, settings.maxRange());
        if (target == null) {
            plugin.send(player, "no-target");
            return;
        }

        if (settings.consumeRod() && player.getGameMode() != GameMode.CREATIVE) {
            rod.setAmount(rod.getAmount() - 1);
            player.getInventory().setItem(hand, rod.getAmount() > 0 ? rod : null);
            player.playSound(player, Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        }
        lastStrike.put(player.getUniqueId(), now);
        plugin.fire(type, target, player, player);
    }

    private static EquipmentSlot castingHand(PlayerFishEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand == EquipmentSlot.HAND || hand == EquipmentSlot.OFF_HAND) {
            return hand;
        }
        // Same rule vanilla uses: the main hand wins if it holds a rod.
        return event.getPlayer().getInventory().getItemInMainHand().getType() == Material.FISHING_ROD
                ? EquipmentSlot.HAND
                : EquipmentSlot.OFF_HAND;
    }
}
