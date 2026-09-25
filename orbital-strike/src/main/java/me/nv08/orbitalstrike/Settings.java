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
            int shootHeight,
            double shootSpeed,
            int skyHeight,
            int ringDelayTicks,
            int ringIntervalTicks,
            int rings,
            double firstRadius,
            double radiusStep,
            double tntSpacing,
            int explodeAfterLandingTicks,
            float power,
            float corePower
    ) {
        public double ringRadius(int ring) {
            return firstRadius + radiusStep * ring;
        }

        /** Furthest a nuke TNT can land from the target, in blocks. */
        public double outerRadius() {
            return rings <= 0 ? 0 : ringRadius(rings - 1);
        }

        /** TNT on a ring, spaced about tnt-spacing blocks apart. */
        public int tntOnRing(int ring) {
            return Math.max(1, (int) Math.round(2 * Math.PI * ringRadius(ring) / tntSpacing));
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
                        Math.max(1, n.getInt("shoot-height", 80)),
                        Math.max(0.5, Math.min(3.9, n.getDouble("shoot-speed", 3.0))),
                        Math.max(1, n.getInt("sky-height", 60)),
                        Math.max(0, n.getInt("ring-delay-ticks", 10)),
                        Math.max(0, n.getInt("ring-interval-ticks", 2)),
                        Math.max(0, n.getInt("rings", 8)),
                        Math.max(0, n.getDouble("first-radius", 6)),
                        Math.max(0, n.getDouble("radius-step", 4)),
                        Math.max(0.5, n.getDouble("tnt-spacing", 1.5)),
                        Math.max(0, n.getInt("explode-after-landing-ticks", 20)),
                        (float) Math.max(0, n.getDouble("power", 4.0)),
                        (float) Math.max(0, n.getDouble("core-power", 8.0))
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
