package com.example.combatguard.command;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.check.Check;
import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.check.ViolationManager;
import com.example.combatguard.check.client.ClientIntegrityCheck;
import com.example.combatguard.client.ClientReport;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /combatguard (alias /cg). Needs combatguard.command. */
public final class GuardCommand implements TabExecutor {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final List<String> SUBCOMMANDS = List.of("alerts", "verbose", "info", "evidence", "client", "violations",
        "checks", "toggle", "exempt", "reset", "debug", "reload");

    private final CombatGuardPlugin plugin;

    public GuardCommand(CombatGuardPlugin plugin) {
        this.plugin = plugin;
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(AlertManager.component(message));
    }

    private static PlayerData target(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&cUsage: /cg " + args[0] + " <player>");
            return null;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        PlayerData data = player == null ? null : PlayerDataManager.get(player);
        if (data == null) {
            send(sender, "&cThat player is not online.");
        }
        return data;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "&bCombatGuard &7commands: &f" + String.join(", ", SUBCOMMANDS));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "alerts", "verbose" -> {
                if (!(sender instanceof Player player) || PlayerDataManager.get(player) == null) {
                    send(sender, "&cOnly players can toggle alerts.");
                    return true;
                }
                PlayerData data = PlayerDataManager.get(player);
                if (args[0].equalsIgnoreCase("verbose")) {
                    data.verbose = !data.verbose;
                    send(sender, "&bCombatGuard &7verbose alerts " + (data.verbose ? "&aon &7(every flag, even below the alert threshold)" : "&coff"));
                } else {
                    data.alerts = !data.alerts;
                    send(sender, "&bCombatGuard &7alerts " + (data.alerts ? "&aon" : "&coff"));
                }
            }
            case "info" -> info(sender, args);
            case "evidence" -> evidence(sender, args);
            case "client" -> client(sender, args);
            case "violations" -> violations(sender);
            case "checks" -> checks(sender);
            case "toggle" -> toggle(sender, args);
            case "exempt" -> exempt(sender, args);
            case "reset" -> {
                PlayerData data = target(sender, args);
                if (data != null) {
                    data.resetViolations();
                    send(sender, "&bCombatGuard &7violations of &f" + data.name + " &7cleared");
                }
            }
            case "debug" -> {
                GuardConfig config = CombatGuardPlugin.config();
                config.logLevel = config.logLevel == GuardConfig.LogLevel.DEBUG ? GuardConfig.LogLevel.INFO : GuardConfig.LogLevel.DEBUG;
                send(sender, "&bCombatGuard &7console log level: &f" + config.logLevel + " &8(not saved)");
            }
            case "reload" -> {
                plugin.reloadGuardConfig();
                send(sender, "&bCombatGuard &7config reloaded");
            }
            default -> send(sender, "&cUnknown subcommand. Try: " + String.join(", ", SUBCOMMANDS));
        }
        return true;
    }

    private void info(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        Player player = Bukkit.getPlayer(data.uuid);
        send(sender, "&8&m----&r &bCombatGuard &f" + data.name + " &8&m----");
        send(sender, "&7UUID: &f" + data.uuid);
        send(sender, String.format(Locale.ROOT, "&7Ping: &f%dms &8(keep-alive %dms, transactions %dms) &7TPS: &f%.1f",
            ViolationManager.ping(player, data), player.getPing(), data.transactionRttMs, ViolationManager.tps()));
        send(sender, "&7Client: &f" + data.clientVersion + (data.legacyClient ? " &c(no tick packets)" : ""));
        send(sender, String.format(Locale.ROOT, "&7World: &f%s &7at &f%.1f %.1f %.1f", player.getWorld().getName(),
            player.getX(), player.getY(), player.getZ()));
        send(sender, String.format(Locale.ROOT, "&7Total VL: &c%.2f", data.totalViolations()));
        data.violations().entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .forEach(e -> send(sender, String.format(Locale.ROOT, "  &7%s &8- &c%.2f", e.getKey(), e.getValue())));
        List<PlayerData.Evidence> recent = data.evidence();
        if (!recent.isEmpty()) {
            send(sender, "&7Recent flags:");
            for (PlayerData.Evidence e : recent.subList(Math.max(0, recent.size() - 5), recent.size())) {
                send(sender, String.format(Locale.ROOT, "  &8%s &f%s/%s &8(&7%s&8) &cVL %.1f",
                    TIME.format(Instant.ofEpochMilli(e.timeMs())), e.check(), e.type(), e.details(), e.vl()));
            }
        }
    }

    private void evidence(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        List<PlayerData.Evidence> list = data.evidence();
        if (list.isEmpty()) {
            send(sender, "&7No evidence recorded.");
            return;
        }
        send(sender, "&bCombatGuard &7evidence for &f" + data.name + " &8(oldest first)");
        for (PlayerData.Evidence e : list) {
            send(sender, String.format(Locale.ROOT, "&8%s &f%s/%s &cVL %.1f &7%s &8ping %d tps %.1f %s %.1f %.1f %.1f",
                TIME.format(Instant.ofEpochMilli(e.timeMs())), e.check(), e.type(), e.vl(), e.details(), e.ping(), e.tps(),
                e.world(), e.x(), e.y(), e.z()));
        }
    }

    private void client(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        ClientIntegrityCheck.State st = CheckRegistry.CLIENT.stateOf(data);
        ClientReport report = st.lastReport;
        if (report == null) {
            send(sender, "&7" + data.name + (st.hasClient ? " has the client mod but has not reported yet." : " does not run the CombatGuard client mod."));
            return;
        }
        send(sender, "&bCombatGuard &7client report for &f" + data.name + " &8(" + report.os + ")");
        send(sender, "&7Mods: &f" + report.mods.size() + " &7Native libraries: &f" + report.nativeLibraryCount);
        send(sender, String.format(Locale.ROOT, "&7Reach attributes: &f%.2f / %.2f", report.entityReach, report.blockReach));
        if (!report.agents.isEmpty()) {
            send(sender, "&cJVM agents: &f" + String.join(", ", report.agents));
        }
        if (report.attachListener) {
            send(sender, "&cSomething attached to the JVM at runtime.");
        }
        for (ClientReport.CallFinding f : report.callOrigins) {
            send(sender, "&cCall: &f" + f.target + " &7from &f" + f.origin + " &8(" + f.originMod + ", x" + f.count + ", " + f.thread + ")");
        }
        for (String c : report.inMemoryClasses) {
            send(sender, "&cIn-memory code: &f" + c);
        }
        for (String n : report.suspiciousNatives) {
            send(sender, "&eNative: &f" + n);
        }
        report.sensitiveMixins.forEach((mod, targets) -> send(sender, "&7Mod &f" + mod + " &7changes " + targets.size() + " combat/input class(es)"));
    }

    private void violations(CommandSender sender) {
        List<PlayerData> players = new ArrayList<>(PlayerDataManager.all());
        players.sort(Comparator.comparingDouble(PlayerData::totalViolations).reversed());
        send(sender, "&bCombatGuard &7violations (online players)");
        int shown = 0;
        for (PlayerData data : players) {
            if (data.totalViolations() <= 0.0 || shown >= 15) {
                continue;
            }
            shown++;
            String top = data.violations().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(3)
                .map(e -> String.format(Locale.ROOT, "%s %.1f", e.getKey(), e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("");
            send(sender, String.format(Locale.ROOT, "  &f%s &c%.1f &8(%s)", data.name, data.totalViolations(), top));
        }
        if (shown == 0) {
            send(sender, "  &7Nobody has violations right now.");
        }
    }

    private void checks(CommandSender sender) {
        send(sender, "&bCombatGuard &7checks (" + CheckRegistry.all().size() + ")");
        for (Check<?> check : CheckRegistry.all()) {
            GuardConfig.CheckSettings s = check.settings();
            send(sender, String.format(Locale.ROOT, "  %s%s &8[%s] &7%s &8alert %.0f kick %.0f",
                s.enabled ? "&a" : "&c", check.id(), check.category(), check.description(), s.alertVl, s.kickVl));
        }
    }

    private void toggle(CommandSender sender, String[] args) {
        Check<?> check = args.length < 2 ? null : CheckRegistry.byId(args[1]);
        if (check == null) {
            send(sender, "&cUsage: /cg toggle <check>");
            return;
        }
        GuardConfig config = CombatGuardPlugin.config();
        GuardConfig.CheckSettings settings = config.check(check.id());
        settings.enabled = !settings.enabled;
        config.save(new File(plugin.getDataFolder(), "config.yml"), plugin.getLogger());
        send(sender, "&bCombatGuard &f" + check.id() + " &7is now " + (settings.enabled ? "&aenabled" : "&cdisabled"));
    }

    private void exempt(CommandSender sender, String[] args) {
        PlayerData data = target(sender, args);
        if (data == null) {
            return;
        }
        int seconds;
        try {
            seconds = args.length < 3 ? 60 : Math.max(0, Math.min(86400, Integer.parseInt(args[2])));
        } catch (NumberFormatException e) {
            send(sender, "&cUsage: /cg exempt <player> <seconds>");
            return;
        }
        data.exemptUntilMs = System.currentTimeMillis() + seconds * 1000L;
        send(sender, "&bCombatGuard &f" + data.name + " &7is exempt from all checks for &f" + seconds + "s");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("toggle")) {
                return CheckRegistry.all().stream().map(Check::id)
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            if (List.of("info", "evidence", "client", "exempt", "reset").contains(args[0].toLowerCase(Locale.ROOT))) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
        }
        return List.of();
    }
}
