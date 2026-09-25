package com.example.combatguard.config;

import com.example.combatguard.check.Check;
import com.example.combatguard.check.CheckRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * plugins/CombatGuard/config.yml. Missing keys are filled in with defaults and written back, so new checks and
 * options appear in old files automatically.
 */
public final class GuardConfig {
    public enum LogLevel { OFF, ERROR, WARN, INFO, DEBUG, TRACE }

    public LogLevel logLevel = LogLevel.INFO;
    public boolean logToFile = true;
    public boolean alertsEnabled = true;
    public String alertFormat = "&8[&bCombatGuard&8] &f%player% &7failed &c%check% &8(&7%type%&8) &7VL &c%vl% &8| &7%details% &8| &7%ping%ms %tps%tps";
    public int alertCooldownMs = 750;
    public double minTps = 18.0;
    public int evidenceSize = 30;
    public boolean exemptOps = false;
    public List<String> exemptNamePrefixes = new ArrayList<>(List.of("."));
    public boolean punishmentsEnabled = false;
    public String kickMessage = "[CombatGuard] Unfair advantage detected (%check%)";
    public ClientSettings client = new ClientSettings();
    public final Map<String, CheckSettings> checks = new LinkedHashMap<>();

    public static final class CheckSettings {
        public boolean enabled = true;
        public double alertVl;
        public double maxVl = 50.0;
        public double kickVl;
        public double punishVl;
        public List<String> punishCommands = new ArrayList<>();
        public double decayPerSecond;
        public boolean mitigate = true;
        public final Map<String, Double> options = new LinkedHashMap<>();
    }

    public static final class ClientSettings {
        public boolean requireClient = false;
        public String requireClientKickMessage = "This server requires the CombatGuard client mod.";
        public int challengeIntervalSeconds = 60;
        public int responseTimeoutSeconds = 20;
        public List<String> blacklistedMods = new ArrayList<>(List.of(
            "meteor-client", "wurst", "aristois", "bleachhack", "liquidbounce", "thunderhack",
            "mathax", "boze", "rusherhack", "combatmod", "freecam", "xray", "advancedxray", "baritone"));
        public List<String> trustedCallerMods = new ArrayList<>(List.of("minecraft", "combatguard"));
        public List<String> trustedNativeLibraries = new ArrayList<>();
    }

    public CheckSettings check(String id) {
        return checks.get(id);
    }

    private static String path(Check<?> check) {
        return "checks." + check.category().name().toLowerCase(Locale.ROOT) + "." + check.id().toLowerCase(Locale.ROOT);
    }

    public static GuardConfig load(File file, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        GuardConfig c = new GuardConfig();
        try {
            c.logLevel = LogLevel.valueOf(yaml.getString("log-level", c.logLevel.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown log-level, using INFO");
        }
        c.logToFile = yaml.getBoolean("log-to-file", c.logToFile);
        c.alertsEnabled = yaml.getBoolean("alerts.enabled", c.alertsEnabled);
        c.alertFormat = yaml.getString("alerts.format", c.alertFormat);
        c.alertCooldownMs = yaml.getInt("alerts.cooldown-ms", c.alertCooldownMs);
        c.minTps = yaml.getDouble("min-tps", c.minTps);
        c.evidenceSize = yaml.getInt("evidence-size", c.evidenceSize);
        c.exemptOps = yaml.getBoolean("exempt.ops", c.exemptOps);
        if (yaml.isList("exempt.name-prefixes")) {
            c.exemptNamePrefixes = yaml.getStringList("exempt.name-prefixes");
        }
        c.punishmentsEnabled = yaml.getBoolean("punishments.enabled", c.punishmentsEnabled);
        c.kickMessage = yaml.getString("punishments.kick-message", c.kickMessage);

        ClientSettings cl = c.client;
        cl.requireClient = yaml.getBoolean("client.require", cl.requireClient);
        cl.requireClientKickMessage = yaml.getString("client.require-kick-message", cl.requireClientKickMessage);
        cl.challengeIntervalSeconds = yaml.getInt("client.challenge-interval-seconds", cl.challengeIntervalSeconds);
        cl.responseTimeoutSeconds = yaml.getInt("client.response-timeout-seconds", cl.responseTimeoutSeconds);
        if (yaml.isList("client.blacklisted-mods")) {
            cl.blacklistedMods = yaml.getStringList("client.blacklisted-mods");
        }
        if (yaml.isList("client.trusted-caller-mods")) {
            cl.trustedCallerMods = yaml.getStringList("client.trusted-caller-mods");
        }
        if (yaml.isList("client.trusted-native-libraries")) {
            cl.trustedNativeLibraries = yaml.getStringList("client.trusted-native-libraries");
        }

        for (Check<?> check : CheckRegistry.all()) {
            CheckSettings d = check.defaults();
            ConfigurationSection s = yaml.getConfigurationSection(path(check));
            CheckSettings cs = new CheckSettings();
            cs.enabled = s == null ? d.enabled : s.getBoolean("enabled", d.enabled);
            cs.alertVl = s == null ? d.alertVl : s.getDouble("alert-vl", d.alertVl);
            cs.maxVl = s == null ? d.maxVl : s.getDouble("max-vl", d.maxVl);
            cs.kickVl = s == null ? d.kickVl : s.getDouble("kick-vl", d.kickVl);
            cs.punishVl = s == null ? d.punishVl : s.getDouble("punish-vl", d.punishVl);
            cs.punishCommands = s != null && s.isList("punish-commands") ? s.getStringList("punish-commands") : new ArrayList<>(d.punishCommands);
            cs.decayPerSecond = s == null ? d.decayPerSecond : s.getDouble("decay-per-second", d.decayPerSecond);
            cs.mitigate = s == null ? d.mitigate : s.getBoolean("mitigate", d.mitigate);
            check.defaultOptions().forEach((key, value) ->
                cs.options.put(key, s == null ? value : s.getDouble("options." + key, value)));
            c.checks.put(check.id(), cs);
        }
        return c;
    }

    public void save(File file, Logger logger) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "CombatGuard anticheat. Reload with /cg reload.",
            "Punishments (kicks and commands) only run when punishments.enabled is true. Watch alerts first."));
        yaml.set("log-level", logLevel.name());
        yaml.setComments("log-level", List.of("OFF, ERROR, WARN, INFO (one line per alert), DEBUG (every flag), TRACE"));
        yaml.set("log-to-file", logToFile);
        yaml.setComments("log-to-file", List.of("Every flag to plugins/CombatGuard/violations.log"));
        yaml.set("alerts.enabled", alertsEnabled);
        yaml.set("alerts.format", alertFormat);
        yaml.setComments("alerts.format", List.of(
            "Placeholders: %player% %uuid% %check% %type% %vl% %ping% %tps% %version% %world% %x% %y% %z% %details%"));
        yaml.set("alerts.cooldown-ms", alertCooldownMs);
        yaml.set("min-tps", minTps);
        yaml.setComments("min-tps", List.of("Checks that depend on server timing stop flagging below this TPS"));
        yaml.set("evidence-size", evidenceSize);
        yaml.set("exempt.ops", exemptOps);
        yaml.set("exempt.name-prefixes", exemptNamePrefixes);
        yaml.setComments("exempt.name-prefixes", List.of("Geyser/Floodgate Bedrock players start with '.' by default"));
        yaml.set("punishments.enabled", punishmentsEnabled);
        yaml.set("punishments.kick-message", kickMessage);
        yaml.set("client.require", client.requireClient);
        yaml.setComments("client.require", List.of(
            "Optional CombatGuard Fabric client mod (injection / cheat mod detection). Only require it if every player can install it."));
        yaml.set("client.require-kick-message", client.requireClientKickMessage);
        yaml.set("client.challenge-interval-seconds", client.challengeIntervalSeconds);
        yaml.set("client.response-timeout-seconds", client.responseTimeoutSeconds);
        yaml.set("client.blacklisted-mods", client.blacklistedMods);
        yaml.set("client.trusted-caller-mods", client.trustedCallerMods);
        yaml.set("client.trusted-native-libraries", client.trustedNativeLibraries);
        for (Check<?> check : CheckRegistry.all()) {
            CheckSettings cs = checks.get(check.id());
            String p = path(check);
            yaml.set(p + ".enabled", cs.enabled);
            yaml.setComments(p, List.of(check.description()));
            yaml.set(p + ".alert-vl", cs.alertVl);
            yaml.set(p + ".max-vl", cs.maxVl);
            yaml.set(p + ".kick-vl", cs.kickVl);
            yaml.set(p + ".punish-vl", cs.punishVl);
            yaml.set(p + ".punish-commands", cs.punishCommands);
            yaml.set(p + ".decay-per-second", cs.decayPerSecond);
            yaml.set(p + ".mitigate", cs.mitigate);
            cs.options.forEach((key, value) -> yaml.set(p + ".options." + key, value));
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.severe("Could not save " + file + ": " + e.getMessage());
        }
    }
}
