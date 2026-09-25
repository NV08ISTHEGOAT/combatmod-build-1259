package me.nv08.orbitalstrike;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Force-loads the blast area while a strike goes off. Plugin chunk tickets only keep chunks loaded:
 * entities in them don't tick, so TNT outside every player's simulation distance would hang in the air.
 * Force-loading ticks them the same way /forceload does.
 *
 * <p>Only chunks this plugin forced are ever un-forced, and they're written to disk so a crash
 * mid-strike can't leave them loaded forever.
 */
final class ForcedChunks {

    record ChunkRef(UUID world, int x, int z) {
    }

    private final OrbitalStrikePlugin plugin;
    private final File file;
    /** Chunks we forced, with how many strikes still need each one. */
    private final Map<ChunkRef, Integer> owned = new HashMap<>();

    ForcedChunks(OrbitalStrikePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "forced-chunks.yml");
    }

    /** Un-forces chunks left over from a crash during a strike. */
    void cleanUpLeftovers() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String line : yaml.getStringList("chunks")) {
            String[] parts = line.split(",");
            try {
                World world = Bukkit.getWorld(UUID.fromString(parts[0]));
                if (world != null) {
                    world.setChunkForceLoaded(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), false);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipping bad entry in forced-chunks.yml: " + line);
            }
        }
        if (!file.delete()) {
            plugin.getLogger().warning("Couldn't delete " + file);
        }
    }

    void acquire(World world, ChunkRef ref) {
        Integer count = owned.get(ref);
        if (count != null) {
            owned.put(ref, count + 1);
        } else if (!world.isChunkForceLoaded(ref.x(), ref.z())) {
            // A chunk someone else force-loaded (e.g. with /forceload) is left alone.
            world.setChunkForceLoaded(ref.x(), ref.z(), true);
            owned.put(ref, 1);
        }
    }

    void release(ChunkRef ref) {
        Integer count = owned.get(ref);
        if (count == null) {
            return;
        }
        if (count > 1) {
            owned.put(ref, count - 1);
            return;
        }
        owned.remove(ref);
        World world = Bukkit.getWorld(ref.world());
        if (world != null) {
            world.setChunkForceLoaded(ref.x(), ref.z(), false);
        }
    }

    void releaseAll() {
        for (ChunkRef ref : owned.keySet()) {
            World world = Bukkit.getWorld(ref.world());
            if (world != null) {
                world.setChunkForceLoaded(ref.x(), ref.z(), false);
            }
        }
        owned.clear();
        save();
    }

    void save() {
        if (owned.isEmpty()) {
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Couldn't delete " + file);
            }
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("chunks", owned.keySet().stream().map(r -> r.world() + "," + r.x() + "," + r.z()).toList());
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't save " + file, e);
        }
    }
}
