package com.example.combatguard.check;

import com.example.combatguard.CombatGuard;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.Exemptions;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.util.ServerLag;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Locale;

public final class ViolationManager {
    private ViolationManager() {
    }

    public static boolean isExempt(ServerPlayerEntity player, PlayerData data) {
        GuardConfig config = CombatGuard.config();
        if (Exemptions.manual(data)) {
            return true;
        }
        if (config.exemptOps && player.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS)) {
            return true;
        }
        String name = data.name;
        for (String prefix : config.exemptNamePrefixes) {
            if (!prefix.isEmpty() && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static int ping(ServerPlayerEntity player, PlayerData data) {
        return Math.max(player.networkHandler.getLatency(), data.transactionRttMs);
    }

    /** Must be called on the server thread. */
    public static void flag(ServerPlayerEntity player, PlayerData data, Check<?> check, String type, String info, double weight) {
        if (!check.enabled() || isExempt(player, data)) {
            return;
        }
        GuardConfig config = CombatGuard.config();
        double tps = ServerLag.tps();
        if (check.lagSensitive() && tps < config.minTps) {
            AlertManager.debug("{} {}/{} ignored, server at {} TPS", data.name, check.id(), type, String.format(Locale.ROOT, "%.1f", tps));
            return;
        }
        GuardConfig.CheckSettings settings = check.settings();
        double vl = data.addViolation(check.id(), weight, settings.maxVl);
        int ping = ping(player, data);
        data.addEvidence(new PlayerData.Evidence(System.currentTimeMillis(), check.id(), type, info, vl, ping, tps,
            player.getEntityWorld().getRegistryKey().getValue().toString(), player.getX(), player.getY(), player.getZ()), config.evidenceSize);

        AlertManager.Flag flag = new AlertManager.Flag(player, data, check.id(), type, info, vl, ping, tps);
        AlertManager.log(flag);
        boolean aboveThreshold = vl >= settings.alertVl;
        if (config.alertsEnabled
            && data.alertCooldownPassed(check.id() + "/" + type, System.currentTimeMillis(), config.alertCooldownMs)) {
            AlertManager.alertStaff(player.getEntityWorld().getServer(), flag, aboveThreshold);
        }

        if (!config.punishmentsEnabled) {
            return;
        }
        MinecraftServer server = player.getEntityWorld().getServer();
        if (settings.punishVl > 0 && vl >= settings.punishVl && data.markPunished(check.id())) {
            String vlText = String.format(Locale.ROOT, "%.1f", vl);
            for (String command : settings.punishCommands) {
                String cmd = command
                    .replace("%player%", data.name)
                    .replace("%uuid%", data.uuid.toString())
                    .replace("%check%", check.id())
                    .replace("%vl%", vlText);
                AlertManager.info("Running punishment for {}: {}", data.name, cmd);
                server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd.startsWith("/") ? cmd.substring(1) : cmd);
            }
        }
        if (settings.kickVl > 0 && vl >= settings.kickVl && !player.isDisconnected()) {
            AlertManager.info("Kicking {} for {} at VL {}", data.name, check.id(), String.format(Locale.ROOT, "%.1f", vl));
            player.networkHandler.disconnect(Text.literal(config.kickMessage.replace("%check%", check.id())));
        }
    }
}
