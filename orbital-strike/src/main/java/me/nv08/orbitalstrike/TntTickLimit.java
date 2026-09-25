package me.nv08.orbitalstrike;

import org.bukkit.World;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Lifts Spigot's max-tnt-per-tick (spigot.yml, default 100) in a world while a strike is running.
 * Lit TNT over that limit simply doesn't tick, so most of a nuke would hang frozen in the sky.
 * The original value is put back when the last strike in the world is done; spigot.yml isn't touched.
 */
final class TntTickLimit {

    private final Logger logger;
    /** Strikes currently running per world. */
    private final Map<UUID, Integer> users = new HashMap<>();
    /** The limit each world had before we lifted it. */
    private final Map<UUID, Integer> originals = new HashMap<>();
    private boolean warned;

    TntTickLimit(Logger logger) {
        this.logger = logger;
    }

    void lift(World world) {
        UUID id = world.getUID();
        if (users.merge(id, 1, Integer::sum) > 1) {
            return;
        }
        try {
            Field field = limitField(world);
            Object config = spigotConfig(world);
            int original = field.getInt(config);
            if (original > 0) {
                originals.put(id, original);
                field.setInt(config, 0); // 0 = no limit
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                logger.warning("Couldn't lift max-tnt-per-tick automatically on this server version. For the nuke to fall "
                        + "properly, set max-tnt-per-tick to 0 in spigot.yml (" + e + ")");
            }
        }
    }

    void restore(World world) {
        UUID id = world.getUID();
        Integer count = users.get(id);
        if (count == null) {
            return;
        }
        if (count > 1) {
            users.put(id, count - 1);
            return;
        }
        users.remove(id);
        Integer original = originals.remove(id);
        if (original != null) {
            set(world, original);
        }
    }

    void restoreAll(Iterable<World> worlds) {
        for (World world : worlds) {
            Integer original = originals.remove(world.getUID());
            if (original != null) {
                set(world, original);
            }
        }
        users.clear();
        originals.clear();
    }

    private void set(World world, int value) {
        try {
            limitField(world).setInt(spigotConfig(world), value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Couldn't restore max-tnt-per-tick to " + value + " in " + world.getName() + ": " + e);
        }
    }

    private static Object spigotConfig(World world) throws ReflectiveOperationException {
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        return level.getClass().getField("spigotConfig").get(level);
    }

    private static Field limitField(World world) throws ReflectiveOperationException {
        return spigotConfig(world).getClass().getField("maxTntTicksPerTick");
    }
}
