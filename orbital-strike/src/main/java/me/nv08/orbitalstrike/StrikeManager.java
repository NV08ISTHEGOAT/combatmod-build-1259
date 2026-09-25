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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * Runs the strikes.
 *
 * <p>Nuke: the TNT falls as one compressed bundle, splits into rings (inside ring first) that fan out
 * to land exactly on their circle, sits on the ground, and all of it explodes a moment after the last
 * one lands. While falling and sitting, the TNT is a falling block rather than primed TNT: Spigot only
 * ticks 100 primed TNT per tick (max-tnt-per-tick), which would leave most of a nuke hanging in the air.
 *
 * <p>Stab: a column of TNT from just above the target down to bedrock, all going off at once.
 */
public final class StrikeManager {

    // Falling block physics (per tick: velocity.y -= GRAVITY, move, velocity *= DRAG).
    private static final double GRAVITY = 0.04;
    private static final double DRAG = 0.98;
    /** A nuke that somehow hasn't landed after this long goes off anyway. */
    private static final int MAX_NUKE_TICKS = 20 * 30;
    /** Extra blocks around the drop zone that stay loaded, for the explosions themselves. */
    private static final int CHUNK_MARGIN_BLOCKS = 8;

    private final OrbitalStrikePlugin plugin;
    private final ForcedChunks forcedChunks;
    private final BlockData tntBlock = Material.TNT.createBlockData();
    private final List<NukeStrike> nukes = new ArrayList<>();
    private BukkitTask nukeTask;

    private static final class NukeStrike {
        final World world;
        final Settings.Nuke nuke;
        final @Nullable Player source;
        final List<Bomb> bombs = new ArrayList<>();
        final List<TNTPrimed> tnt = new ArrayList<>();
        int age;
        int allLandedAt = -1;
        boolean detonated;

        NukeStrike(World world, Settings.Nuke nuke, @Nullable Player source) {
            this.world = world;
            this.nuke = nuke;
            this.source = source;
        }

        boolean finished() {
            return detonated && tnt.stream().noneMatch(Entity::isValid);
        }
    }

    private static final class Bomb {
        FallingBlock entity;
        final double targetX;
        final double targetZ;
        final int releaseTick;
        boolean released;
        boolean landed;

        Bomb(FallingBlock entity, double targetX, double targetZ, int releaseTick) {
            this.entity = entity;
            this.targetX = targetX;
            this.targetZ = targetZ;
            this.releaseTick = releaseTick;
        }
    }

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
            BooleanSupplier finished = type == StrikeType.NUKE
                    ? startNuke(settings.nuke(), target, source)::finished
                    : fireStab(settings.stab(), target, source);
            releaseWhenDone(chunks, finished);
        })), settings.strikeDelayTicks());
    }

    public void shutdown() {
        if (nukeTask != null) {
            nukeTask.cancel();
            nukeTask = null;
        }
        for (NukeStrike strike : nukes) {
            strike.bombs.forEach(bomb -> bomb.entity.remove());
        }
        nukes.clear();
        forcedChunks.releaseAll();
    }

    // ---- Nuke ----

    private NukeStrike startNuke(Settings.Nuke nuke, Block target, @Nullable Player source) {
        World world = target.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double cx = target.getX() + 0.5;
        double cz = target.getZ() + 0.5;
        double y = Math.min(target.getY() + 1 + nuke.height(), world.getMaxHeight() - 1);
        Location bundle = new Location(world, cx, y, cz);

        NukeStrike strike = new NukeStrike(world, nuke, source);
        for (int i = 0; i < nuke.centerTnt(); i++) {
            strike.bombs.add(new Bomb(spawnBomb(bundle, true), cx, cz, nuke.compressedTicks()));
        }
        for (int ring = 0; ring < nuke.rings(); ring++) {
            double radius = nuke.ringRadius(ring);
            int count = nuke.tntOnRing(ring);
            int releaseTick = nuke.compressedTicks() + ring * nuke.ringIntervalTicks();
            double phase = random.nextDouble(Math.PI * 2);
            for (int i = 0; i < count; i++) {
                double angle = phase + Math.PI * 2 * i / count;
                // Uniform random point in a disc of radius `jitter` (0 = exact ring).
                double jitterAngle = random.nextDouble(Math.PI * 2);
                double jitter = nuke.jitter() * Math.sqrt(random.nextDouble());
                double x = cx + Math.cos(angle) * radius + Math.cos(jitterAngle) * jitter;
                double z = cz + Math.sin(angle) * radius + Math.sin(jitterAngle) * jitter;
                strike.bombs.add(new Bomb(spawnBomb(bundle, true), x, z, releaseTick));
            }
        }

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
        boolean allLanded = true;
        boolean bundled = false;
        for (Bomb bomb : strike.bombs) {
            if (bomb.landed) {
                continue;
            }
            if (!bomb.entity.isValid()) {
                // It hit something the prediction missed (a cliff side, a mob): sit where it stopped.
                bomb.entity = spawnBomb(bomb.entity.getLocation(), false);
                bomb.landed = true;
                continue;
            }
            if (!bomb.released) {
                if (strike.age >= bomb.releaseTick) {
                    release(strike.world, bomb);
                } else {
                    bundled = true;
                }
            }
            if (land(strike.world, bomb)) {
                bomb.landed = true;
            } else {
                allLanded = false;
            }
        }

        Location center = strike.bombs.isEmpty() ? null : strike.bombs.getFirst().entity.getLocation();
        if (bundled && center != null && strike.age % 2 == 0) {
            strike.world.spawnParticle(Particle.FLAME, center.clone().add(0, 0.5, 0), 6, 0.3, 0.3, 0.3, 0.02, null, true);
        }
        if (allLanded && strike.allLandedAt < 0) {
            strike.allLandedAt = strike.age;
            if (center != null) {
                strike.world.playSound(center, Sound.ENTITY_TNT_PRIMED, 8f, 0.8f);
            }
        }

        boolean fuseDone = strike.allLandedAt >= 0 && strike.age >= strike.allLandedAt + strike.nuke.explodeAfterLandingTicks();
        if (fuseDone || strike.age >= MAX_NUKE_TICKS) {
            detonate(strike);
            return true;
        }
        return false;
    }

    /** Sends a bomb from the bundle toward its spot on the ring, timed to arrive as it hits the ground. */
    private static void release(World world, Bomb bomb) {
        bomb.released = true;
        FallingBlock entity = bomb.entity;
        Location at = entity.getLocation();
        Vector velocity = entity.getVelocity();
        double dx = bomb.targetX - at.getX();
        double dz = bomb.targetZ - at.getZ();
        if (dx == 0 && dz == 0) {
            return;
        }
        int ticks = ticksToFall(at.getY(), velocity.getY(), surfaceY(world, bomb.targetX, bomb.targetZ));
        // Horizontal distance covered in n ticks is v * (1 - DRAG^n) / (1 - DRAG).
        double scale = (1 - DRAG) / (1 - Math.pow(DRAG, ticks));
        entity.setVelocity(new Vector(dx * scale, velocity.getY(), dz * scale));
    }

    /** If the bomb reaches the ground this tick, stops it on the surface instead of letting it land as a block. */
    private static boolean land(World world, Bomb bomb) {
        FallingBlock entity = bomb.entity;
        Location at = entity.getLocation();
        Vector velocity = entity.getVelocity();
        double nextX = at.getX() + velocity.getX();
        double nextZ = at.getZ() + velocity.getZ();
        double ground = surfaceY(world, nextX, nextZ);
        if (at.getY() + velocity.getY() - GRAVITY > ground) {
            return false;
        }
        entity.setGravity(false);
        entity.setVelocity(new Vector());
        entity.teleport(new Location(world, nextX, ground, nextZ));
        return true;
    }

    private void detonate(NukeStrike strike) {
        strike.detonated = true;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Bomb bomb : strike.bombs) {
            Location at = bomb.entity.getLocation();
            bomb.entity.remove();
            strike.tnt.add(spawnTnt(strike.world, at.getX(), at.getY(), at.getZ(),
                    1 + random.nextInt(3), strike.nuke.power(), true, strike.source));
        }
    }

    private FallingBlock spawnBomb(Location location, boolean gravity) {
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

    /** Ticks until something falling from y with vertical speed vy reaches groundY. */
    private static int ticksToFall(double y, double vy, double groundY) {
        int ticks = 0;
        while (ticks < MAX_NUKE_TICKS) {
            vy -= GRAVITY;
            y += vy;
            ticks++;
            if (y <= groundY) {
                break;
            }
            vy *= DRAG;
        }
        return ticks;
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

    private void releaseWhenDone(List<ForcedChunks.ChunkRef> chunks, BooleanSupplier finished) {
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            waited[0] += 10;
            if (!finished.getAsBoolean() && waited[0] < MAX_NUKE_TICKS + 200) {
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
