package com.example.combatguard.check;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.Exemptions;
import com.example.combatguard.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class ViolationManager {
    public static final String BYPASS_PERMISSION = "combatguard.bypass";

    private ViolationManager() {
    }

    public static boolean isExempt(Player player, PlayerData data) {
        GuardConfig config = CombatGuardPlugin.config();
        if (Exemptions.manual(data) || player.hasPermission(BYPASS_PERMISSION)) {
            return true;
        }
        if (config.exemptOps && player.isOp()) {
            return true;
        }
        for (String prefix : config.exemptNamePrefixes) {
            if (!prefix.isEmpty() && data.name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The higher of the vanilla keep-alive ping and the transaction round trip. */
    public static int ping(Player player, PlayerData data) {
        return Math.max(player.getPing(), data.transactionRttMs);
    }

    public static double tps() {
        return Math.min(20.0, Bukkit.getTPS()[0]);
    }

    /** Server thread only. */
    public static void flag(Player player, PlayerData data, Check<?> check, String type, String info, double weight) {
        if (!check.enabled() || isExempt(player, data)) {
            return;
        }
        GuardConfig config = CombatGuardPlugin.config();
        double tps = tps();
        if (check.lagSensitive() && tps < config.minTps) {
            AlertManager.debug(data.name + " " + check.id() + "/" + type + " ignored, server at " + String.format(Locale.ROOT, "%.1f", tps) + " TPS");
            return;
        }
        GuardConfig.CheckSettings settings = check.settings();
        double vl = data.addViolation(check.id(), weight, settings.maxVl);
        int ping = ping(player, data);
        data.addEvidence(new PlayerData.Evidence(System.currentTimeMillis(), check.id(), type, info, vl, ping, tps,
            player.getWorld().getName(), player.getX(), player.getY(), player.getZ()), config.evidenceSize);

        AlertManager.Flag flag = new AlertManager.Flag(player, data, check.id(), type, info, vl, ping, tps);
        AlertManager.log(flag);
        if (config.alertsEnabled && data.alertCooldownPassed(check.id() + "/" + type, System.currentTimeMillis(), config.alertCooldownMs)) {
            AlertManager.alertStaff(flag, vl >= settings.alertVl);
        }
        if (!config.punishmentsEnabled) {
            return;
        }
        if (settings.punishVl > 0 && vl >= settings.punishVl && data.markPunished(check.id())) {
            String vlText = String.format(Locale.ROOT, "%.1f", vl);
            for (String command : settings.punishCommands) {
                String cmd = command.replace("%player%", data.name).replace("%uuid%", data.uuid.toString())
                    .replace("%check%", check.id()).replace("%vl%", vlText);
                AlertManager.info("Running punishment for " + data.name + ": " + cmd);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.startsWith("/") ? cmd.substring(1) : cmd);
            }
        }
        if (settings.kickVl > 0 && vl >= settings.kickVl && player.isOnline()) {
            AlertManager.info("Kicking " + data.name + " for " + check.id() + " at VL " + String.format(Locale.ROOT, "%.1f", vl));
            player.kick(AlertManager.component(config.kickMessage.replace("%check%", check.id())));
        }
    }
}
