package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.GuardMath;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.world.WorldQuery;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The crosshair is on one block per tick, so starting to break two blocks in one tick is impossible (multi), and a
 * full cube can only be hit on a face the player can see (face).
 */
public final class NukerCheck extends Check<Void> {
    public NukerCheck() {
        super("Nuker", Category.WORLD, "Breaking several or hidden blocks at once (Nuker, GhostHand, BreakAura)", false, 1.0, 0.0, 0.1);
        option("faceTolerance", 0.05);
    }

    public void onStartBreaking(Player player, PlayerData data, int x, int y, int z, GuardMath.Face face, List<Vec3> eyes) {
        data.tick.startDigPositions.add(((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL));
        if (!enabled()) {
            return;
        }
        World world = player.getWorld();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (block.getType().isAir() || !WorldQuery.fullCube(block)) {
            return;
        }
        boolean visible = false;
        for (Vec3 eye : eyes) {
            if (AABB.ofBlock(x, y, z).contains(eye)) {
                return;
            }
            visible |= GuardMath.canSeeFace(eye, x, y, z, face, opt("faceTolerance"));
        }
        if (!visible) {
            flag(player, data, "face", "broke the hidden " + face.name().toLowerCase() + " face", 1.0);
        }
    }

    public void onTickEnd(Player player, PlayerData data) {
        int blocks = data.tick.startDigPositions.size();
        if (enabled() && blocks >= 2) {
            flag(player, data, "multi", blocks + " blocks started in one tick", 1.0);
        }
    }
}
