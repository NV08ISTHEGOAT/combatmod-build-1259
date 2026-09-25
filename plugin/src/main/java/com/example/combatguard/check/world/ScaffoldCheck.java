package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.math.AABB;
import com.example.combatguard.math.GuardMath;
import com.example.combatguard.math.Vec3;
import com.example.combatguard.world.WorldQuery;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.List;

import static com.example.combatguard.math.GuardMath.fmt;

/**
 * Block placement. For full cubes the vanilla client can only click a face it can see (face) and sends a hit
 * position exactly on that face (hitvec). The rotation sent in the same tick must point at the block (aim), and
 * placement speed is capped by how fast a person can click (rate). Fast legit bridging passes all four.
 */
public final class ScaffoldCheck extends Check<ScaffoldCheck.State> {
    public static final class State {
        final ArrayDeque<Integer> placeTicks = new ArrayDeque<>();
        double aimBuffer;
        double rateBuffer;
    }

    public ScaffoldCheck() {
        super("Scaffold", Category.WORLD, "Impossible block placement (Scaffold, FastPlace, AirPlace)", false, 1.0, 0.0, 0.1);
        option("faceTolerance", 0.05);
        option("hitVectorTolerance", 0.001);
        option("aimExpand", 0.3);
        option("aimBuffer", 2.0);
        option("maxPlacesPerSecond", 20.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public void onPlace(Player player, PlayerData data, int x, int y, int z, GuardMath.Face face, Vec3 hit, boolean inside, List<Vec3> eyes) {
        if (!enabled() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        State st = state(data);
        World world = player.getWorld();
        if (world.isChunkLoaded(x >> 4, z >> 4) && !inside) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isAir() && WorldQuery.fullCube(block)) {
                boolean visible = false;
                for (Vec3 eye : eyes) {
                    visible |= GuardMath.canSeeFace(eye, x, y, z, face, opt("faceTolerance"));
                }
                if (!visible) {
                    flag(player, data, "face", "clicked the hidden " + face.name().toLowerCase() + " face", 1.0);
                }
                double error = GuardMath.hitVectorError(hit, x, y, z, face);
                if (error > opt("hitVectorTolerance")) {
                    flag(player, data, "hitvec", "hit position " + fmt(error) + " off the face", 1.0);
                }
            }
        }
        int tick = data.clientTick;
        st.placeTicks.addLast(tick);
        while (!st.placeTicks.isEmpty() && st.placeTicks.peekFirst() <= tick - 20) {
            st.placeTicks.removeFirst();
        }
        if (st.placeTicks.size() > opt("maxPlacesPerSecond")) {
            st.rateBuffer += 1.0;
            if (st.rateBuffer > 3.0) {
                flag(player, data, "rate", st.placeTicks.size() + " blocks per second", 0.5);
            }
        } else {
            st.rateBuffer = Math.max(0.0, st.rateBuffer - 0.1);
        }
        data.tick.places.add(new PlayerData.PendingPlace(eyes, x, y, z, face));
    }

    public void onTickEnd(Player player, PlayerData data) {
        if (!enabled() || data.tick.places.isEmpty()) {
            return;
        }
        State st = state(data);
        Vec3 look = GuardMath.lookVector(data.pitch, data.yaw);
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double range = (reach == null ? 4.5 : reach.getValue()) + 1.0;
        for (PlayerData.PendingPlace place : data.tick.places) {
            AABB box = AABB.ofBlock(place.x(), place.y(), place.z()).expand(opt("aimExpand"));
            boolean hit = false;
            for (Vec3 eye : place.eyes()) {
                hit |= box.intersectsRay(eye, look, range);
            }
            if (hit) {
                st.aimBuffer = Math.max(0.0, st.aimBuffer - 0.25);
            } else {
                st.aimBuffer += 1.0;
                if (st.aimBuffer > opt("aimBuffer")) {
                    flag(player, data, "aim", "placed a block it was not looking at", 1.0);
                }
            }
        }
    }
}
