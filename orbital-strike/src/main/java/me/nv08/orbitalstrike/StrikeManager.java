package me.nv08.orbitalstrike;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** Spawns the Nuke and Stab TNT patterns and keeps the blast area loaded while they go off. */
public final class StrikeManager {

    /** Longest a nuke TNT may keep falling before it goes off anyway (detonate-on-impact mode). */
    private static final int IMPACT_SAFETY_FUSE_TICKS = 400;
    /** Landed TNT goes off within this many ticks, so the rings ripple instead of all popping in one tick. */
    private static final int IMPACT_FUSE_SPREAD = 4;
    /** Extra blocks around the drop zone that stay loaded, for the explosions themselves. */
    private static final int CHUNK_MARGIN_BLOCKS = 8;

    private final OrbitalStrikePlugin plugin;
    private final ForcedChunks forcedChunks;
    private final Set<TNTPrimed> falling = new HashSet<>();
    private BukkitTask impactTask;

    public StrikeManager(OrbitalStrikePlugin plugin) {
        this.plugin = plugin;
        this.forcedChunks = new ForcedChunks(plugin);
        forcedChunks.cleanUpLeftovers();
    }

    /** The block the player is looking at, ignoring grass/flowers and fluids, or null if nothing is in range. */
    public @Nullable Block findTarget(Player player, int maxRange) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), maxRange, FluidCollisionMode.NEVER, true);
        return hit == null ? null : hit.getHitBlock();
    }

    public void launch(StrikeType type, Block target, @Nullable Player source) {
        Settings settings = plugin.settings();
        World world = target.getWorld();
        double radius = (type == StrikeType.NUKE ? settings.nuke().outerRadius() : 1) + CHUNK_MARGIN_BLOCKS;
        List<ForcedChunks.ChunkRef> chunks = chunksAround(world, target.getX() + 0.5, target.getZ() + 0.5, radius);

        // Load the blast area in the background during the delay, then keep it ticking,
        // so a long-range strike doesn't leave TNT frozen in chunks nobody is standing in.
        CompletableFuture<?>[] loads = chunks.stream()
                .map(ref -> world.getChunkAtAsync(ref.x(), ref.z()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture<Void> ready = CompletableFuture.allOf(loads);

        playLaunchEffects(world, target, settings.nuke().height());

        Bukkit.getScheduler().runTaskLater(plugin, () -> ready.whenComplete((ignored, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Couldn't load part of the strike area", error);
            }
            chunks.forEach(ref -> forcedChunks.acquire(world, ref));
            forcedChunks.save();
            List<TNTPrimed> tnt = type == StrikeType.NUKE
                    ? fireNuke(settings.nuke(), target, source)
                    : fireStab(settings.stab(), target, source);
            releaseWhenDone(chunks, tnt);
        })), settings.strikeDelayTicks());
    }

    public void shutdown() {
        if (impactTask != null) {
            impactTask.cancel();
            impactTask = null;
        }
        falling.clear();
        forcedChunks.releaseAll();
    }

    private List<TNTPrimed> fireNuke(Settings.Nuke nuke, Block target, @Nullable Player source) {
        World world = target.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double cx = target.getX() + 0.5;
        double cz = target.getZ() + 0.5;
        double y = Math.min(target.getY() + Math.max(1, nuke.height()), world.getMaxHeight() - 1);
        int fuse = nuke.detonateOnImpact() ? Math.max(nuke.fuseTicks(), IMPACT_SAFETY_FUSE_TICKS) : nuke.fuseTicks();

        List<TNTPrimed> spawned = new ArrayList<>();
        for (int i = 0; i < nuke.centerTnt(); i++) {
            spawned.add(spawnTnt(world, cx, y, cz, fuse, nuke.power(), true, source));
        }
        for (int ring = 0; ring < nuke.rings(); ring++) {
            double radius = nuke.firstRadius() + nuke.radiusStep() * ring;
            int count = nuke.tntOnRing(ring);
            double phase = random.nextDouble(Math.PI * 2);
            for (int i = 0; i < count; i++) {
                double angle = phase + Math.PI * 2 * i / count;
                // Uniform random point in a disc of radius `jitter`.
                double jitterAngle = random.nextDouble(Math.PI * 2);
                double jitter = nuke.jitter() * Math.sqrt(random.nextDouble());
                double x = cx + Math.cos(angle) * radius + Math.cos(jitterAngle) * jitter;
                double z = cz + Math.sin(angle) * radius + Math.sin(jitterAngle) * jitter;
                spawned.add(spawnTnt(world, x, y, z, fuse, nuke.power(), true, source));
            }
        }

        if (nuke.detonateOnImpact()) {
            falling.addAll(spawned);
            if (impactTask == null) {
                impactTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkImpacts, 1L, 1L);
            }
        }
        return spawned;
    }

    private List<TNTPrimed> fireStab(Settings.Stab stab, Block target, @Nullable Player source) {
        World world = target.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int top = Math.min(target.getY() + stab.startAbove(), world.getMaxHeight() - 1);

        List<TNTPrimed> spawned = new ArrayList<>();
        for (int y = top; y >= world.getMinHeight(); y--) {
            Block block = world.getBlockAt(target.getX(), y, target.getZ());
            if (block.getType().getBlastResistance() >= stab.stopAtResistance()) {
                break;
            }
            if ((top - y) % stab.step() != 0) {
                continue;
            }
            for (int i = 0; i < stab.tntPerLayer(); i++) {
                double x = target.getX() + 0.5 + random.nextDouble(-0.3, 0.3);
                double z = target.getZ() + 0.5 + random.nextDouble(-0.3, 0.3);
                spawned.add(spawnTnt(world, x, y, z, stab.fuseTicks(), stab.power(), false, source));
            }
        }
        return spawned;
    }

    private static TNTPrimed spawnTnt(World world, double x, double y, double z, int fuse, float power,
                                      boolean gravity, @Nullable Player source) {
        return world.spawn(new Location(world, x, y, z), TNTPrimed.class, tnt -> {
            // Spawned TNT gets a random hop like freshly lit TNT; drop straight down instead.
            tnt.setVelocity(new Vector());
            tnt.setFuseTicks(fuse);
            tnt.setYield(power);
            tnt.setGravity(gravity);
            if (source != null) {
                tnt.setSource(source);
            }
        });
    }

    private void checkImpacts() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Iterator<TNTPrimed> it = falling.iterator();
        while (it.hasNext()) {
            TNTPrimed tnt = it.next();
            if (!tnt.isValid()) {
                it.remove();
            } else if (tnt.isOnGround() || tnt.isInWater() || tnt.isInLava()) {
                tnt.setFuseTicks(Math.min(tnt.getFuseTicks(), 1 + random.nextInt(IMPACT_FUSE_SPREAD)));
                it.remove();
            }
        }
        if (falling.isEmpty() && impactTask != null) {
            impactTask.cancel();
            impactTask = null;
        }
    }

    private void playLaunchEffects(World world, Block target, int beamHeight) {
        Location base = target.getLocation().add(0.5, 1, 0.5);
        double top = Math.min(base.getY() + beamHeight, world.getMaxHeight());
        world.playSound(base, Sound.BLOCK_BEACON_ACTIVATE, 4f, 0.6f);
        for (double y = base.getY(); y <= top; y += 1.5) {
            world.spawnParticle(Particle.END_ROD, base.getX(), y, base.getZ(), 1, 0, 0, 0, 0, null, true);
        }
    }

    private void releaseWhenDone(List<ForcedChunks.ChunkRef> chunks, List<TNTPrimed> tnt) {
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            waited[0] += 10;
            boolean anyLeft = tnt.stream().anyMatch(Entity::isValid);
            if (anyLeft && waited[0] < IMPACT_SAFETY_FUSE_TICKS + 100) {
                return;
            }
            task.cancel();
            // Stay loaded a moment longer for chain reactions and falling blocks.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                chunks.forEach(forcedChunks::release);
                forcedChunks.save();
            }, 40L);
        }, 10L, 10L);
    }

    private void onMain(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private static List<ForcedChunks.ChunkRef> chunksAround(World world, double x, double z, double radius) {
        int minX = (int) Math.floor(x - radius) >> 4;
        int maxX = (int) Math.floor(x + radius) >> 4;
        int minZ = (int) Math.floor(z - radius) >> 4;
        int maxZ = (int) Math.floor(z + radius) >> 4;
        List<ForcedChunks.ChunkRef> chunks = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                chunks.add(new ForcedChunks.ChunkRef(world.getUID(), cx, cz));
            }
        }
        return chunks;
    }
}
