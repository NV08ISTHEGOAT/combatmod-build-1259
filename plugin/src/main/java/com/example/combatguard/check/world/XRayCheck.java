package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * X-Ray is client side, so only mining behaviour shows it. An ore is "hidden" when every open side of it was dug
 * out by this player, i.e. it was not visible from a cave. X-rayers tunnel to hidden veins after far less stone
 * than a strip miner needs. Statistical, alert only by default.
 */
public final class XRayCheck extends Check<XRayCheck.State> {
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public static final class State {
        final LinkedHashSet<Long> dug = new LinkedHashSet<>();
        int stone;
        int hiddenVeins;
        Block lastOre;
        long lastOreMs;
    }

    public XRayCheck() {
        super("XRay", Category.WORLD, "Mining straight to hidden ores (XRay, statistical, alert only)", false, 1.0, 0.0, 0.01);
        option("minHiddenVeins", 3.0);
        option("maxHiddenVeinsPer100Stone", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    private static long key(Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL);
    }

    public void onBlockBroken(Player player, PlayerData data, Block block, Material type) {
        if (!enabled() || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        State st = state(data);
        st.dug.add(key(block));
        if (st.dug.size() > 512) {
            st.dug.remove(st.dug.iterator().next());
        }
        if (Tag.BASE_STONE_OVERWORLD.isTagged(type) || Tag.BASE_STONE_NETHER.isTagged(type)) {
            st.stone++;
            return;
        }
        if (!Tag.DIAMOND_ORES.isTagged(type) && !Tag.EMERALD_ORES.isTagged(type) && type != Material.ANCIENT_DEBRIS) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean sameVein = st.lastOre != null && st.lastOre.getWorld().equals(block.getWorld())
            && st.lastOre.getLocation().distanceSquared(block.getLocation()) <= 16.0 && now - st.lastOreMs < 60_000L;
        st.lastOre = block;
        st.lastOreMs = now;
        if (sameVein) {
            return;
        }
        for (BlockFace face : FACES) {
            Block neighbour = block.getRelative(face);
            if (!neighbour.getType().isOccluding() && !st.dug.contains(key(neighbour))) {
                return;
            }
        }
        st.hiddenVeins++;
        double per100 = st.hiddenVeins * 100.0 / Math.max(st.stone, 20);
        if (st.hiddenVeins >= opt("minHiddenVeins") && per100 > opt("maxHiddenVeinsPer100Stone")) {
            flag(player, data, "ratio", String.format(Locale.ROOT, "%d hidden veins after %d stone (%.1f per 100)", st.hiddenVeins, st.stone, per100), 1.0);
        }
    }
}
