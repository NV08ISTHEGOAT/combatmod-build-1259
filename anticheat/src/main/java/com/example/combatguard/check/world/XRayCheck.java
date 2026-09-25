package com.example.combatguard.check.world;

import com.example.combatguard.check.Check;
import com.example.combatguard.data.PlayerData;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * X-Ray is client side, so it can only be detected from mining behaviour. An ore is "hidden" when every air
 * block touching it was dug out by this player: it was not visible in a cave. X-ray users tunnel to hidden
 * veins after digging far less stone than a strip miner needs. This is a statistic, so it only alerts staff by
 * default.
 */
public final class XRayCheck extends Check<XRayCheck.State> {
    public static final class State {
        final LinkedHashSet<Long> dug = new LinkedHashSet<>();
        int stone;
        int hiddenVeins;
        int veins;
        BlockPos lastOre;
        long lastOreMs;
    }

    public XRayCheck() {
        super("XRay", Category.WORLD, "Mining straight to hidden ores (statistical, alert only)", false, 1.0, 0.0, 0.01);
        option("minHiddenVeins", 3.0);
        option("maxHiddenVeinsPer100Stone", 3.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    private static boolean isValuable(BlockState state) {
        return state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES) || state.isOf(Blocks.ANCIENT_DEBRIS);
    }

    private static boolean isStone(BlockState state) {
        return state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER);
    }

    public void onBlockBroken(ServerPlayerEntity player, PlayerData data, BlockPos pos, BlockState state) {
        if (!enabled() || player.isCreative()) {
            return;
        }
        State st = state(data);
        st.dug.add(pos.asLong());
        if (st.dug.size() > 512) {
            st.dug.remove(st.dug.iterator().next());
        }
        if (isStone(state)) {
            st.stone++;
            return;
        }
        if (!isValuable(state)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean sameVein = st.lastOre != null && st.lastOre.isWithinDistance(pos, 4.0) && now - st.lastOreMs < 60_000L;
        st.lastOre = pos.toImmutable();
        st.lastOreMs = now;
        if (sameVein) {
            return;
        }
        st.veins++;
        if (!isHidden(player.getEntityWorld(), pos, st)) {
            return;
        }
        st.hiddenVeins++;
        double per100 = st.hiddenVeins * 100.0 / Math.max(st.stone, 20);
        if (st.hiddenVeins >= opt("minHiddenVeins") && per100 > opt("maxHiddenVeinsPer100Stone")) {
            flag(player, data, "ratio", String.format(Locale.ROOT, "%d hidden veins after %d stone (%.1f per 100)",
                st.hiddenVeins, st.stone, per100), 1.0);
        }
    }

    private static boolean isHidden(ServerWorld world, BlockPos pos, State st) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.offset(direction);
            BlockState state = world.getBlockState(neighbour);
            if (!state.isOpaqueFullCube() && !st.dug.contains(neighbour.asLong())) {
                return false;
            }
        }
        return true;
    }
}
