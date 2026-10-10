package com.stasis.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BobberEntityManager {

    public static final String HOLDER_TAG = "bobber_holder";

    /** Give up on a cast that hasn't settled on a pressure plate after this many ticks. */
    private static final int MAX_PENDING_TICKS = 20 * 60;
    /** How long a holder's chunk is kept ticking after release so the plate can update. */
    private static final int RELEASE_TICKET_TICKS = 20 * 5;

    /** A placed holder, stored by location + UUID so it can be found even when its chunk is unloaded. */
    private record Holder(UUID standId, String world, double x, double y, double z) {
    }

    private record PendingHook(UUID hookId, int castTick) {
    }

    private final StasisPlugin plugin;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Holder> holders = new HashMap<>();
    private final Map<UUID, PendingHook> pendingHooks = new HashMap<>();
    /** Holders that were released while their entities weren't loaded; removed as soon as they load. */
    private final Set<UUID> pendingRemovals = new HashSet<>();
    private final Map<String, Integer> ticketExpiry = new HashMap<>();

    public BobberEntityManager(StasisPlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    public static boolean isHolder(Entity entity) {
        return entity instanceof ArmorStand && entity.getScoreboardTags().contains(HOLDER_TAG);
    }

    public void onCast(Player player, FishHook hook) {
        UUID owner = player.getUniqueId();
        // Casting again (e.g. after relogging, when the old bobber is gone) releases the old holder.
        releaseHolder(owner);
        pendingHooks.put(owner, new PendingHook(hook.getUniqueId(), Bukkit.getCurrentTick()));
    }

    /**
     * Re-attaches to state that is already in the world after a /reload: bobbers that are
     * still out get tracked again, and holders waiting for removal that are loaded get removed.
     */
    public void resumeAfterReload() {
        for (World world : Bukkit.getWorlds()) {
            for (FishHook hook : world.getEntitiesByClass(FishHook.class)) {
                if (hook.getShooter() instanceof Player player && !hasHolderAt(player.getUniqueId(), hook)) {
                    pendingHooks.put(player.getUniqueId(), new PendingHook(hook.getUniqueId(), Bukkit.getCurrentTick()));
                }
            }
        }
        List<Entity> loaded = new ArrayList<>();
        for (UUID id : pendingRemovals) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                loaded.add(entity);
            }
        }
        onEntitiesLoad(loaded);
    }

    private boolean hasHolderAt(UUID owner, FishHook hook) {
        // A bobber that is already being held down shouldn't spawn a second holder.
        Holder holder = holders.get(owner);
        return holder != null && hook.getWorld().getName().equals(holder.world())
                && hook.getLocation().distanceSquared(new Location(hook.getWorld(), holder.x(), holder.y(), holder.z())) < 1;
    }

    /** Re-reads holders.yml (e.g. after editing it) and re-attaches to what's in the world. */
    public void reload() {
        holders.clear();
        pendingRemovals.clear();
        loadData();
        resumeAfterReload();
    }

    public void onReelIn(UUID owner) {
        pendingHooks.remove(owner);
        releaseHolder(owner);
    }

    public void forgetPendingHook(UUID owner) {
        pendingHooks.remove(owner);
    }

    public void tick() {
        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, PendingHook>> it = pendingHooks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingHook> entry = it.next();
            UUID owner = entry.getKey();
            PendingHook pending = entry.getValue();

            if (now - pending.castTick() > MAX_PENDING_TICKS) {
                it.remove();
                continue;
            }
            if (!(Bukkit.getEntity(pending.hookId()) instanceof FishHook hook) || !hook.isValid()) {
                it.remove();
                continue;
            }
            if (hook.getHookedEntity() != null || !hook.isOnGround()
                    || hook.getVelocity().lengthSquared() >= 0.005) {
                continue;
            }
            Location loc = hook.getLocation();
            if (!isOnPressurePlate(loc)) {
                // Landed somewhere that isn't a pressure plate: nothing to hold down.
                it.remove();
                continue;
            }

            ArmorStand stand = spawnHolder(loc, owner);
            holders.put(owner, new Holder(stand.getUniqueId(), loc.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ()));
            it.remove();
            plugin.getLogger().info("Placed bobber holder for " + owner + " at " + formatLoc(loc));
            saveData();
        }
    }

    /** Removes the owner's holder, loading its chunk if needed, so the pressure plate un-presses. */
    private void releaseHolder(UUID owner) {
        Holder holder = holders.remove(owner);
        if (holder == null) {
            return;
        }
        Entity stand = findHolderEntity(holder);
        if (stand != null) {
            removeHolderEntity(stand);
            plugin.getLogger().info("Released bobber holder for " + owner);
        } else {
            // Entities for that chunk aren't loaded yet; remove it the moment they are.
            pendingRemovals.add(holder.standId());
            plugin.getLogger().info("Bobber holder for " + owner + " is not loaded yet; it will be removed when its chunk loads.");
        }
        saveData();
    }

    private Entity findHolderEntity(Holder holder) {
        Entity entity = Bukkit.getEntity(holder.standId());
        if (entity != null) {
            return entity;
        }
        World world = Bukkit.getWorld(holder.world());
        if (world == null) {
            return null;
        }
        Chunk chunk = world.getChunkAt((int) Math.floor(holder.x()) >> 4, (int) Math.floor(holder.z()) >> 4);
        for (Entity candidate : chunk.getEntities()) {
            if (candidate.getUniqueId().equals(holder.standId())) {
                return candidate;
            }
        }
        return null;
    }

    private void removeHolderEntity(Entity stand) {
        // Keep the chunk ticking for a few seconds so the pressure plate notices the holder is gone,
        // even if the player who released it is far away.
        Chunk chunk = stand.getLocation().getChunk();
        String chunkId = chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
        int expiry = Bukkit.getCurrentTick() + RELEASE_TICKET_TICKS;
        chunk.addPluginChunkTicket(plugin);
        ticketExpiry.put(chunkId, expiry);
        stand.remove();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer current = ticketExpiry.get(chunkId);
            if (current != null && current == expiry) {
                ticketExpiry.remove(chunkId);
                chunk.removePluginChunkTicket(plugin);
            }
        }, RELEASE_TICKET_TICKS);
    }

    public void onEntitiesLoad(List<Entity> entities) {
        if (pendingRemovals.isEmpty()) {
            return;
        }
        List<Entity> toRemove = new ArrayList<>();
        for (Entity entity : entities) {
            if (isHolder(entity) && pendingRemovals.remove(entity.getUniqueId())) {
                toRemove.add(entity);
            }
        }
        if (toRemove.isEmpty()) {
            return;
        }
        // Don't remove entities from inside the load callback itself.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity entity : toRemove) {
                if (entity.isValid()) {
                    removeHolderEntity(entity);
                }
            }
        });
        saveData();
    }

    public void listHolders(CommandSender sender) {
        if (holders.isEmpty()) {
            sender.sendMessage("No active bobber holders.");
        }
        for (Map.Entry<UUID, Holder> entry : holders.entrySet()) {
            Holder h = entry.getValue();
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            sender.sendMessage((name != null ? name : entry.getKey().toString()) + ": " + h.world() + " "
                    + (int) Math.floor(h.x()) + ", " + (int) Math.floor(h.y()) + ", " + (int) Math.floor(h.z()));
        }
        if (!pendingRemovals.isEmpty()) {
            sender.sendMessage(pendingRemovals.size() + " holder(s) waiting to be removed when their chunk loads.");
        }
    }

    /** Removes every holder near a location, including leftovers from older versions of this plugin. */
    public int clearNearby(Location center, double radius) {
        int removed = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!isHolder(entity)) {
                continue;
            }
            UUID id = entity.getUniqueId();
            holders.values().removeIf(h -> h.standId().equals(id));
            pendingRemovals.remove(id);
            removeHolderEntity(entity);
            removed++;
        }
        if (removed > 0) {
            saveData();
        }
        return removed;
    }

    public void saveData() {
        plugin.getDataFolder().mkdirs();
        File file = new File(plugin.getDataFolder(), "holders.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, Holder> entry : holders.entrySet()) {
            Holder h = entry.getValue();
            String path = "holders." + entry.getKey();
            config.set(path + ".standUUID", h.standId().toString());
            config.set(path + ".world", h.world());
            config.set(path + ".x", h.x());
            config.set(path + ".y", h.y());
            config.set(path + ".z", h.z());
        }
        config.set("pendingRemovals", pendingRemovals.stream().map(UUID::toString).toList());
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save holders.yml: " + e.getMessage());
        }
    }

    /**
     * Loads saved holders. Entities are deliberately not looked up here: after a restart they
     * aren't loaded yet, and the old code dropped every holder because of that.
     */
    public void loadData() {
        File file = new File(plugin.getDataFolder(), "holders.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("holders");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                String world = entry.getString("world");
                String standId = entry.getString("standUUID");
                if (world == null || standId == null) {
                    continue;
                }
                try {
                    holders.put(UUID.fromString(key), new Holder(UUID.fromString(standId), world,
                            entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping invalid holder entry: " + key);
                }
            }
        }
        for (String id : config.getStringList("pendingRemovals")) {
            try {
                pendingRemovals.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
            }
        }
        plugin.getLogger().info("Loaded " + holders.size() + " bobber holder(s).");
    }

    private static boolean isOnPressurePlate(Location loc) {
        // Pressure plates have no collision, so a resting bobber sits inside the plate's block.
        return Tag.PRESSURE_PLATES.isTagged(loc.getBlock().getType())
                || Tag.PRESSURE_PLATES.isTagged(loc.clone().add(0, 0.1, 0).getBlock().getType());
    }

    private ArmorStand spawnHolder(Location loc, UUID owner) {
        return loc.getWorld().spawn(loc, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(false); // needs a real hitbox to press the plate
            stand.setSmall(true);
            stand.setBasePlate(false);
            stand.setPersistent(true);
            stand.setRemoveWhenFarAway(false);
            stand.setInvulnerable(true);
            stand.setCollidable(false);
            stand.setSilent(true);
            stand.setCanPickupItems(false);
            stand.setDisabledSlots(EquipmentSlot.values());
            stand.addScoreboardTag(HOLDER_TAG);
            stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        });
    }

    private static String formatLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
