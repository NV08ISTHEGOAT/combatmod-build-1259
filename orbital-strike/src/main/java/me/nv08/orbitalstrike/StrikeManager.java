package me.nv08.orbitalstrike;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
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
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * Runs the strikes.
 *
 * <p>Nuke: one compressed TNT shoots down from the sky and lands on the target. Then rings of lit TNT
 * appear in the sky (inside ring first), fall straight down so the rings stay perfect over any terrain,
 * and once they've all landed everything explodes on the ground, the compressed TNT in the middle too.
 *
 * <p>Stab: a column of TNT from just above the target down to bedrock, all going off at once.
 */
public final class StrikeManager {

    private static final double GRAVITY = 0.04;
    /** A nuke that somehow hasn't finished after this long goes off anyway. */
    private static final int MAX_NUKE_TICKS = 20 * 30;
    /** The ring TNT goes off over this many ticks: still one blast, but not one huge lag spike. */
    private static final int DETONATION_SPREAD_TICKS = 6;
    /** Extra blocks around the drop zone that stay loaded, for the explosions themselves. */
    private static final int CHUNK_MARGIN_BLOCKS = 8;

    private final OrbitalStrikePlugin plugin;
    private final ForcedChunks forcedChunks;
    private final TntTickLimit tntTickLimit;
    private final BlockData tntBlock = Material.TNT.createBlockData();
    private final List<NukeStrike> nukes = new ArrayList<>();
    private BukkitTask nukeTask;

    private static final class NukeStrike {
        final World world;
        final Settings.Nuke nuke;
        final @Nullable Player source;
        final double cx;
        final double cz;
        final int targetY;
        FallingBlock core;
        int coreLandedAt = -1;
        int ringsSpawned;
        final List<TNTPrimed> ringTnt = new ArrayList<>();
        final Set<TNTPrimed> falling = new HashSet<>();
        int allLandedAt = -1;
        @Nullable TNTPrimed coreTnt;
        boolean detonated;
        int age;

        NukeStrike(World world, Settings.Nuke nuke, @Nullable Player source, Block target) {
            this.world = world;
            this.nuke = nuke;
            this.source = source;
            this.cx = target.getX() + 0.5;
            this.cz = target.getZ() + 0.5;
            this.targetY = target.getY();
        }

        boolean finished() {
            return detonated
                    && ringTnt.stream().noneMatch(Entity::isValid)
                    && (coreTnt == null || !coreTnt.isValid());
        }
    }

    public StrikeManager(OrbitalStrikePlugin plugin) {
        this.plugin = plugin;
        this.forcedChunks = new ForcedChunks(plugin);
        this.tntTickLimit = new TntTickLimit(plugin.getLogger());
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

        playLaunchEffects(world, target, settings.nuke().shootHeight());

        Bukkit.getScheduler().runTaskLater(plugin, () -> ready.whenComplete((ignored, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Couldn't load part of the strike area", error);
            }
            chunks.forEach(ref -> forcedChunks.acquire(world, ref));
            forcedChunks.save();
            tntTickLimit.lift(world);
            BooleanSupplier finished = type == StrikeType.NUKE
                    ? startNuke(settings.nuke(), target, source)::finished
                    : fireStab(settings.stab(), target, source);
            releaseWhenDone(world, chunks, finished);
        })), settings.strikeDelayTicks());
    }

    public void shutdown() {
        if (nukeTask != null) {
            nukeTask.cancel();
            nukeTask = null;
        }
        for (NukeStrike strike : nukes) {
            strike.core.remove();
            strike.ringTnt.forEach(Entity::remove);
        }
        nukes.clear();
        forcedChunks.releaseAll();
        tntTickLimit.restoreAll(Bukkit.getWorlds());
    }

    // ---- Nuke ----

    private NukeStrike startNuke(Settings.Nuke nuke, Block target, @Nullable Player source) {
        World world = target.getWorld();
        NukeStrike strike = new NukeStrike(world, nuke, source, target);
        double y = Math.min(target.getY() + 1 + nuke.shootHeight(), world.getMaxHeight() - 1);
        strike.core = spawnBlock(new Location(world, strike.cx, y, strike.cz), true);
        world.playSound(strike.core.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 8f, 0.5f);

        nukes.add(strike);
        if (nukeTask == null) {
            nukeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickNukes, 1L, 1L);
        }
        return strike;
    }

    private void tickNukes() {
        Iterator<NukeStrike> it = nukes.iterator();
        while (it.hasNext()) {
            NukeStrike strike = it.next();
            if (tickNuke(strike)) {
                it.remove();
            }
        }
        if (nukes.isEmpty() && nukeTask != null) {
            nukeTask.cancel();
            nukeTask = null;
        }
    }

    /** Advances one nuke by a tick. Returns true once it has exploded. */
    private boolean tickNuke(NukeStrike strike) {
        strike.age++;
        Settings.Nuke nuke = strike.nuke;

        if (strike.coreLandedAt < 0) {
            shootCore(strike);
        } else {
            // Light up the rings in the sky, inside ring first.
            int sinceLanding = strike.age - strike.coreLandedAt;
            while (strike.ringsSpawned < nuke.rings()
                    && sinceLanding >= nuke.ringDelayTicks() + strike.ringsSpawned * nuke.ringIntervalTicks()) {
                spawnRing(strike, strike.ringsSpawned++);
            }
            strike.falling.removeIf(tnt -> !tnt.isValid() || tnt.isOnGround() || tnt.isInWater() || tnt.isInLava());
            if (strike.ringsSpawned == nuke.rings() && strike.falling.isEmpty() && strike.allLandedAt < 0) {
                strike.allLandedAt = strike.age;
            }
        }

        boolean fuseDone = strike.allLandedAt >= 0 && strike.age >= strike.allLandedAt + nuke.explodeAfterLandingTicks();
        if (fuseDone || strike.age >= MAX_NUKE_TICKS) {
            detonate(strike);
            return true;
        }
        return false;
    }

    /** Moves the compressed TNT down at a steady speed and sets it down on the target. */
    private void shootCore(NukeStrike strike) {
        World world = strike.world;
        FallingBlock core = strike.core;
        if (!core.isValid()) {
            // It hit something on the way (a tree, a mob): it stops right there.
            strike.core = spawnBlock(core.getLocation(), false);
            landCore(strike);
            return;
        }
        Location at = core.getLocation();
        double speed = strike.nuke.shootSpeed();
        world.spawnParticle(Particle.FLAME, at.getX(), at.getY() + 1, at.getZ(), 10, 0.15, 0.6, 0.15, 0.02, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, at.getX(), at.getY() + 1.5, at.getZ(), 4, 0.2, 0.8, 0.2, 0.01, null, true);

        double ground = surfaceY(world, strike.cx, strike.cz);
        if (at.getY() - speed - GRAVITY <= ground) {
            core.setGravity(false);
            core.setVelocity(new Vector());
            core.teleport(new Location(world, strike.cx, ground, strike.cz));
            landCore(strike);
        } else {
            core.setVelocity(new Vector(0, -speed, 0));
        }
    }

    private void landCore(NukeStrike strike) {
        strike.coreLandedAt = strike.age;
        Location at = strike.core.getLocation();
        strike.world.playSound(at, Sound.BLOCK_ANVIL_LAND, 6f, 0.5f);
        strike.world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 3f, 1.6f);
        strike.world.spawnParticle(Particle.EXPLOSION, at.getX(), at.getY() + 0.5, at.getZ(), 3, 0.5, 0.3, 0.5, 0, null, true);
        strike.world.spawnParticle(Particle.CLOUD, at.getX(), at.getY() + 0.2, at.getZ(), 40, 1.5, 0.1, 1.5, 0.05, null, true);
    }

    private void spawnRing(NukeStrike strike, int ring) {
        Settings.Nuke nuke = strike.nuke;
        World world = strike.world;
        double radius = nuke.ringRadius(ring);
        int count = nuke.tntOnRing(ring);
        double y = Math.min(strike.targetY + 1 + nuke.skyHeight(), world.getMaxHeight() - 1);
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < count; i++) {
            double angle = phase + Math.PI * 2 * i / count;
            double x = strike.cx + Math.cos(angle) * radius;
            double z = strike.cz + Math.sin(angle) * radius;
            TNTPrimed tnt = spawnTnt(world, x, y, z, MAX_NUKE_TICKS, nuke.power(), true, strike.source);
            strike.ringTnt.add(tnt);
            strike.falling.add(tnt);
        }
        world.playSound(new Location(world, strike.cx, y, strike.cz), Sound.ENTITY_TNT_PRIMED, 8f, 0.6f + ring * 0.1f);
    }

    private void detonate(NukeStrike strike) {
        strike.detonated = true;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (TNTPrimed tnt : strike.ringTnt) {
            if (tnt.isValid()) {
                tnt.setFuseTicks(1 + random.nextInt(DETONATION_SPREAD_TICKS));
            }
        }
        Location core = strike.core.getLocation();
        strike.core.remove();
        strike.coreTnt = spawnTnt(strike.world, core.getX(), core.getY(), core.getZ(), 1,
                strike.nuke.corePower(), true, strike.source);
    }

    private FallingBlock spawnBlock(Location location, boolean gravity) {
        return location.getWorld().spawn(location, FallingBlock.class, block -> {
            block.setBlockData(tntBlock);
            block.setVelocity(new Vector());
            block.setGravity(gravity);
            block.setDropItem(false);
            block.setCancelDrop(true);
            block.setHurtEntities(false);
            block.setPersistent(false);
        });
    }

    /** Top of the highest solid/liquid block at x, z. */
    private static double surfaceY(World world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int by = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING);
        BoundingBox box = world.getBlockAt(bx, by, bz).getBoundingBox();
        return box.getHeight() > 0 ? box.getMaxY() : by + 1;
    }

    // ---- Stab ----

    private BooleanSupplier fireStab(Settings.Stab stab, Block target, @Nullable Player source) {
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
        return () -> spawned.stream().noneMatch(Entity::isValid);
    }

    // ---- Shared ----

    private static TNTPrimed spawnTnt(World world, double x, double y, double z, int fuse, float power,
                                      boolean gravity, @Nullable Player source) {
        return world.spawn(new Location(world, x, y, z), TNTPrimed.class, tnt -> {
            // Spawned TNT gets a random hop like freshly lit TNT; keep it in place instead.
            tnt.setVelocity(new Vector());
            tnt.setFuseTicks(fuse);
            tnt.setYield(power);
            tnt.setGravity(gravity);
            if (source != null) {
                tnt.setSource(source);
            }
        });
    }

    private void playLaunchEffects(World world, Block target, int beamHeight) {
        Location base = target.getLocation().add(0.5, 1, 0.5);
        double top = Math.min(base.getY() + beamHeight, world.getMaxHeight());
        world.playSound(base, Sound.BLOCK_BEACON_ACTIVATE, 4f, 0.6f);
        for (double y = base.getY(); y <= top; y += 1.5) {
            world.spawnParticle(Particle.END_ROD, base.getX(), y, base.getZ(), 1, 0, 0, 0, 0, null, true);
        }
    }

    private void releaseWhenDone(World world, List<ForcedChunks.ChunkRef> chunks, BooleanSupplier finished) {
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            waited[0] += 10;
            if (!finished.getAsBoolean() && waited[0] < MAX_NUKE_TICKS + 200) {
                return;
            }
            task.cancel();
            tntTickLimit.restore(world);
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
