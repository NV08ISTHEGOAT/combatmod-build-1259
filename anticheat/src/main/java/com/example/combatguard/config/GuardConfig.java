package com.example.combatguard.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON config stored at {@code config/combatguard.json}. Missing keys fall back to the defaults below,
 * and every check registers its own defaults so new checks show up in old config files automatically.
 */
public final class GuardConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("CombatGuard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public enum LogLevel { OFF, ERROR, WARN, INFO, DEBUG, TRACE }

    /** Console verbosity. INFO logs one line per alert, DEBUG every flag, TRACE also buffered near-misses. */
    public LogLevel logLevel = LogLevel.INFO;
    /** Append every flag to logs/combatguard.log. */
    public boolean logToFile = true;
    /** Send alerts to online staff (permission level 2+) who have alerts toggled on. */
    public boolean alertsEnabled = true;
    /** Operators are never flagged. Off by default so staff can test the checks themselves. */
    public boolean exemptOps = false;
    /** Players whose name starts with one of these are exempt (Geyser/Floodgate Bedrock players use "."). */
    public List<String> exemptNamePrefixes = new ArrayList<>(List.of("."));
    /** Checks that depend on server timing stop flagging while the server runs below this TPS. */
    public double minTps = 18.0;
    /** Minimum time between two alerts for the same player, check and type. */
    public int alertCooldownMs = 750;
    /** Recent flags kept per player for /cg evidence. */
    public int evidenceSize = 30;
    /**
     * Placeholders: %player% %uuid% %check% %type% %vl% %ping% %tps% %version% %world% %x% %y% %z% %details%.
     * '&' colour codes are supported.
     */
    public String alertFormat = "&8[&bCombatGuard&8] &f%player% &7failed &c%check% &8(&7%type%&8) &7VL &c%vl% &8| &7%details% &8| &7%ping%ms %tps%tps";
    /** Kicks and punish commands only run when this is true. Alerts always work. */
    public boolean punishmentsEnabled = false;
    /** Placeholders: %check%. */
    public String kickMessage = "[CombatGuard] Unfair advantage detected (%check%)";

    public ClientSettings client = new ClientSettings();
    public Map<String, CheckSettings> checks = new LinkedHashMap<>();

    public static final class CheckSettings {
        public boolean enabled = true;
        /** Alert staff once the violation level reaches this value (verbose staff see every flag). */
        public double alertVl = 1.0;
        /** The violation level never goes above this. */
        public double maxVl = 50.0;
        /** Kick once the violation level reaches this value. 0 disables kicking. Needs punishmentsEnabled. */
        public double kickVl = 0.0;
        /** Run {@link #punishCommands} once the violation level reaches this value. 0 disables it. Needs punishmentsEnabled. */
        public double punishVl = 0.0;
        /** Console commands, e.g. "tempban %player% 7d Unfair advantage". Placeholders: %player% %uuid% %check% %vl%. */
        public List<String> punishCommands = new ArrayList<>();
        /** Violation level removed per second. */
        public double decayPerSecond = 0.05;
        /** Cancel the offending action or set the player back when possible. */
        public boolean mitigate = true;
        /** Check specific thresholds (buffers, tolerances). */
        public Map<String, Double> options = new LinkedHashMap<>();

        public CheckSettings() {
        }

        public CheckSettings(double alertVl, double kickVl, double decayPerSecond) {
            this.alertVl = alertVl;
            this.kickVl = kickVl;
            this.decayPerSecond = decayPerSecond;
        }
    }

    public static final class ClientSettings {
        /** Kick players who do not run the CombatGuard client mod. Only enable this on modded servers. */
        public boolean requireClient = false;
        public String requireClientKickMessage = "This server requires the CombatGuard client mod.";
        /** Seconds between integrity challenges. */
        public int challengeIntervalSeconds = 60;
        /** Seconds a client has to answer a challenge before it counts as a violation. */
        public int responseTimeoutSeconds = 20;
        /** Mod ids that are treated as cheat clients when present. */
        public List<String> blacklistedMods = new ArrayList<>(List.of(
            "meteor-client", "wurst", "aristois", "bleachhack", "liquidbounce", "thunderhack",
            "mathax", "boze", "rusherhack", "combatmod", "freecam", "xray", "advancedxray", "baritone"
        ));
        /** Mods allowed to call attack/use/input methods from outside vanilla code (e.g. "litematica"). */
        public List<String> trustedCallerMods = new ArrayList<>(List.of("minecraft", "combatguard"));
        /** Native libraries (case-insensitive file name substring) that are never reported. */
        public List<String> trustedNativeLibraries = new ArrayList<>();
    }

    public CheckSettings check(String id) {
        return checks.get(id);
    }

    /** Registers defaults for a check if the config does not have them yet. */
    public void registerDefaults(String id, CheckSettings defaults, Map<String, Double> defaultOptions) {
        CheckSettings settings = checks.computeIfAbsent(id, k -> defaults);
        if (settings.options == null) {
            settings.options = new LinkedHashMap<>();
        }
        if (settings.punishCommands == null) {
            settings.punishCommands = new ArrayList<>();
        }
        defaultOptions.forEach(settings.options::putIfAbsent);
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("combatguard.json");
    }

    public static GuardConfig load() {
        Path path = path();
        GuardConfig config = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, GuardConfig.class);
            } catch (Exception e) {
                LOGGER.error("Could not read {}, using defaults", path, e);
            }
        }
        if (config == null) {
            config = new GuardConfig();
        }
        if (config.client == null) {
            config.client = new ClientSettings();
        }
        if (config.checks == null) {
            config.checks = new LinkedHashMap<>();
        }
        if (config.exemptNamePrefixes == null) {
            config.exemptNamePrefixes = new ArrayList<>();
        }
        if (config.logLevel == null) {
            config.logLevel = LogLevel.INFO;
        }
        return config;
    }

    public void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Could not write {}", path, e);
        }
    }
}
