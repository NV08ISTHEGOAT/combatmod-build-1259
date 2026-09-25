package me.nv08.orbitalstrike;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Creates cannon rods and recognises them by a tag, so renaming one in an anvil doesn't break it. */
public final class CannonItems {

    private final OrbitalStrikePlugin plugin;
    private final NamespacedKey typeKey;

    public CannonItems(OrbitalStrikePlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "strike_type");
    }

    public ItemStack create(StrikeType type) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();

        String path = "items." + type.id();
        String name = plugin.getConfig().getString(path + ".name", "Orbital Strike Cannon (" + type.displayName() + ")");
        List<String> lore = plugin.getConfig().getStringList(path + ".lore");

        meta.displayName(noItalic(plugin.miniMessage().deserialize(name)));
        meta.lore(lore.stream().map(line -> noItalic(plugin.miniMessage().deserialize(line))).toList());
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());

        item.setItemMeta(meta);
        return item;
    }

    /** The strike this rod fires, or null if it isn't a cannon. */
    public StrikeType typeOf(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return StrikeType.fromId(id);
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
