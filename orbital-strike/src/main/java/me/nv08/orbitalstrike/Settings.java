package me.nv08.orbitalstrike;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Numeric settings, read once per (re)load so a strike in flight isn't affected by /osc reload. */
public record Settings(
        int maxRange,
        int strikeDelayTicks,
        boolean consumeRod,
        int cooldownSeconds,
        boolean broadcast,
        Nuke nuke,
        Stab stab
) {

    public record Nuke(
            int height,
            int centerTnt,
            int rings,
            double firstRadius,
            double radiusStep,
            int firstRingTnt,
            int lastRingTnt,
            double jitter,
            boolean detonateOnImpact,
            int fuseTicks,
            float power
    ) {
        /** Furthest a nuke TNT can land from the target, in blocks. */
        public double outerRadius() {
            return rings <= 0 ? jitter : firstRadius + radiusStep * (rings - 1) + jitter;
        }

        public int tntOnRing(int ring) {
            if (rings <= 1) {
                return firstRingTnt;
            }
            double t = (double) ring / (rings - 1);
            return (int) Math.round(firstRingTnt + (lastRingTnt - firstRingTnt) * t);
        }
    }

    public record Stab(
            int startAbove,
            int step,
            int tntPerLayer,
            double stopAtResistance,
            int fuseTicks,
            float power
    ) {
    }

    public static Settings from(FileConfiguration config) {
        ConfigurationSection n = section(config, "nuke");
        ConfigurationSection s = section(config, "stab");
        return new Settings(
                Math.max(1, config.getInt("max-range", 300)),
                Math.max(0, config.getInt("strike-delay-ticks", 10)),
                config.getBoolean("consume-rod", true),
                Math.max(0, config.getInt("cooldown-seconds", 0)),
                config.getBoolean("broadcast", false),
                new Nuke(
                        Math.max(0, n.getInt("height", 72)),
                        Math.max(0, n.getInt("center-tnt", 1)),
                        Math.max(0, n.getInt("rings", 10)),
                        Math.max(0, n.getDouble("first-radius", 6)),
                        Math.max(0, n.getDouble("radius-step", 5)),
                        Math.max(1, n.getInt("first-ring-tnt", 15)),
                        Math.max(1, n.getInt("last-ring-tnt", 119)),
                        Math.max(0, n.getDouble("jitter", 1.75)),
                        n.getBoolean("detonate-on-impact", true),
                        Math.max(1, n.getInt("fuse-ticks", 80)),
                        (float) Math.max(0, n.getDouble("power", 4.0))
                ),
                new Stab(
                        s.getInt("start-above", 5),
                        Math.max(1, s.getInt("step", 4)),
                        Math.max(1, s.getInt("tnt-per-layer", 5)),
                        s.getDouble("stop-at-resistance", 1200),
                        Math.max(1, s.getInt("fuse-ticks", 1)),
                        (float) Math.max(0, s.getDouble("power", 4.0))
                )
        );
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section != null ? section : config.createSection(path);
    }
}
