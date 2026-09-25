package com.example.combatguard.command;

import com.example.combatguard.CombatGuard;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.check.Check;
import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.check.ViolationManager;
import com.example.combatguard.check.client.ClientIntegrityCheck;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import com.example.combatguard.network.ClientReport;
import com.example.combatguard.util.ServerLag;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /combatguard (alias /cg). Every subcommand needs permission level 2 (game masters). */
public final class GuardCommand {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private GuardCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = dispatcher.register(build("combatguard"));
        dispatcher.register(CommandManager.literal("cg")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .redirect(root));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> build(String name) {
        return CommandManager.literal(name)
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("alerts").executes(ctx -> toggle(ctx, false)))
            .then(CommandManager.literal("verbose").executes(ctx -> toggle(ctx, true)))
            .then(CommandManager.literal("info")
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(GuardCommand::info)))
            .then(CommandManager.literal("evidence")
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(GuardCommand::evidence)))
            .then(CommandManager.literal("client")
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(GuardCommand::client)))
            .then(CommandManager.literal("violations").executes(GuardCommand::violations))
            .then(CommandManager.literal("checks").executes(GuardCommand::checks))
            .then(CommandManager.literal("toggle")
                .then(CommandManager.argument("check", StringArgumentType.word())
                    .suggests((ctx, builder) -> CommandSource.suggestMatching(CheckRegistry.all().stream().map(Check::id), builder))
                    .executes(GuardCommand::toggleCheck)))
            .then(CommandManager.literal("exempt")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 86400)).executes(GuardCommand::exempt))))
            .then(CommandManager.literal("reset")
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(GuardCommand::reset)))
            .then(CommandManager.literal("debug").executes(GuardCommand::debug))
            .then(CommandManager.literal("reload").executes(GuardCommand::reload));
    }

    private static void send(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(AlertManager.colour(message)), false);
    }

    private static int toggle(CommandContext<ServerCommandSource> ctx, boolean verbose) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        PlayerData data = PlayerDataManager.get(player);
        if (data == null) {
            return 0;
        }
        if (verbose) {
            data.verbose = !data.verbose;
            send(ctx.getSource(), "&bCombatGuard &7verbose alerts " + (data.verbose ? "&aon &7(every flag, even below the alert threshold)" : "&coff"));
        } else {
            data.alerts = !data.alerts;
            send(ctx.getSource(), "&bCombatGuard &7alerts " + (data.alerts ? "&aon" : "&coff"));
        }
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PlayerData data = PlayerDataManager.get(target);
        if (data == null) {
            send(ctx.getSource(), "&cNo data for that player.");
            return 0;
        }
        ServerCommandSource source = ctx.getSource();
        send(source, "&8&m----&r &bCombatGuard &f" + data.name + " &8&m----");
        send(source, "&7UUID: &f" + data.uuid);
        send(source, String.format(Locale.ROOT, "&7Ping: &f%dms &8(keep-alive %dms, transactions %dms) &7TPS: &f%.1f",
            ViolationManager.ping(target, data), target.networkHandler.getLatency(), data.transactionRttMs, ServerLag.tps()));
        send(source, "&7Client: &f" + data.clientVersion + (data.legacyClient ? " &c(no tick packets)" : ""));
        send(source, String.format(Locale.ROOT, "&7World: &f%s &7at &f%.1f %.1f %.1f",
            target.getEntityWorld().getRegistryKey().getValue(), target.getX(), target.getY(), target.getZ()));
        send(source, String.format(Locale.ROOT, "&7Total VL: &c%.2f", data.totalViolations()));
        data.violations().entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .forEach(e -> send(source, String.format(Locale.ROOT, "  &7%s &8- &c%.2f", e.getKey(), e.getValue())));
        List<PlayerData.Evidence> recent = data.evidence();
        if (!recent.isEmpty()) {
            send(source, "&7Recent flags:");
            for (PlayerData.Evidence e : recent.subList(Math.max(0, recent.size() - 5), recent.size())) {
                send(source, String.format(Locale.ROOT, "  &8%s &f%s/%s &8(&7%s&8) &cVL %.1f",
                    TIME.format(Instant.ofEpochMilli(e.timeMs())), e.check(), e.type(), e.details(), e.vl()));
            }
        }
        return 1;
    }

    private static int evidence(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PlayerData data = PlayerDataManager.get(target);
        if (data == null || data.evidence().isEmpty()) {
            send(ctx.getSource(), "&7No evidence recorded.");
            return 0;
        }
        send(ctx.getSource(), "&bCombatGuard &7evidence for &f" + data.name + " &8(oldest first)");
        for (PlayerData.Evidence e : data.evidence()) {
            send(ctx.getSource(), String.format(Locale.ROOT, "&8%s &f%s/%s &cVL %.1f &7%s &8ping %d tps %.1f %s %.1f %.1f %.1f",
                TIME.format(Instant.ofEpochMilli(e.timeMs())), e.check(), e.type(), e.vl(), e.details(), e.ping(), e.tps(),
                e.world(), e.x(), e.y(), e.z()));
        }
        return 1;
    }

    private static int client(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PlayerData data = PlayerDataManager.get(target);
        if (data == null) {
            return 0;
        }
        ClientIntegrityCheck.State st = CheckRegistry.CLIENT.stateOf(data);
        ClientReport report = st.lastReport;
        ServerCommandSource source = ctx.getSource();
        if (report == null) {
            send(source, "&7" + data.name + " has not sent an integrity report (vanilla client or no CombatGuard client mod).");
            return 1;
        }
        send(source, "&bCombatGuard &7client report for &f" + data.name + " &8(" + report.os + ")");
        send(source, "&7Mods: &f" + report.mods.size() + " &7Native libraries: &f" + report.nativeLibraryCount);
        send(source, String.format(Locale.ROOT, "&7Reach attributes: &f%.2f / %.2f", report.entityReach, report.blockReach));
        if (!report.agents.isEmpty()) {
            send(source, "&cJVM agents: &f" + String.join(", ", report.agents));
        }
        if (report.attachListener) {
            send(source, "&cSomething attached to the JVM at runtime.");
        }
        for (ClientReport.CallFinding f : report.callOrigins) {
            send(source, "&cCall: &f" + f.target + " &7from &f" + f.origin + " &8(" + f.originMod + ", x" + f.count + ", " + f.thread + ")");
        }
        for (String c : report.inMemoryClasses) {
            send(source, "&cIn-memory code: &f" + c);
        }
        for (String n : report.suspiciousNatives) {
            send(source, "&eNative: &f" + n);
        }
        if (!report.nativeThreads.isEmpty()) {
            send(source, "&eThreads without Java frames: &f" + String.join(", ", report.nativeThreads));
        }
        report.sensitiveMixins.forEach((mod, targets) ->
            send(source, "&7Mod &f" + mod + " &7changes " + targets.size() + " combat/input class(es)"));
        return 1;
    }

    private static int violations(CommandContext<ServerCommandSource> ctx) {
        List<PlayerData> players = new ArrayList<>(PlayerDataManager.all());
        players.sort(Comparator.comparingDouble(PlayerData::totalViolations).reversed());
        send(ctx.getSource(), "&bCombatGuard &7violations (online players)");
        int shown = 0;
        for (PlayerData data : players) {
            if (data.totalViolations() <= 0.0 || shown++ >= 15) {
                continue;
            }
            String top = data.violations().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(3)
                .map(e -> String.format(Locale.ROOT, "%s %.1f", e.getKey(), e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("");
            send(ctx.getSource(), String.format(Locale.ROOT, "  &f%s &c%.1f &8(%s)", data.name, data.totalViolations(), top));
        }
        if (shown == 0) {
            send(ctx.getSource(), "  &7Nobody has violations right now.");
        }
        return 1;
    }

    private static int checks(CommandContext<ServerCommandSource> ctx) {
        send(ctx.getSource(), "&bCombatGuard &7checks (" + CheckRegistry.all().size() + ")");
        for (Check<?> check : CheckRegistry.all()) {
            GuardConfig.CheckSettings s = check.settings();
            send(ctx.getSource(), String.format(Locale.ROOT, "  %s%s &8[%s] &7%s &8alert %.0f kick %.0f",
                s.enabled ? "&a" : "&c", check.id(), check.category(), check.description(), s.alertVl, s.kickVl));
        }
        return 1;
    }

    private static int toggleCheck(CommandContext<ServerCommandSource> ctx) {
        Check<?> check = CheckRegistry.byId(StringArgumentType.getString(ctx, "check"));
        if (check == null) {
            send(ctx.getSource(), "&cUnknown check.");
            return 0;
        }
        GuardConfig.CheckSettings settings = CombatGuard.config().check(check.id());
        settings.enabled = !settings.enabled;
        CombatGuard.config().save();
        send(ctx.getSource(), "&bCombatGuard &f" + check.id() + " &7is now " + (settings.enabled ? "&aenabled" : "&cdisabled"));
        return 1;
    }

    private static int exempt(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PlayerData data = PlayerDataManager.get(target);
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        if (data == null) {
            return 0;
        }
        data.exemptUntilMs = System.currentTimeMillis() + seconds * 1000L;
        send(ctx.getSource(), "&bCombatGuard &f" + data.name + " &7is exempt from all checks for &f" + seconds + "s");
        return 1;
    }

    private static int reset(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PlayerData data = PlayerDataManager.get(target);
        if (data == null) {
            return 0;
        }
        data.resetViolations();
        send(ctx.getSource(), "&bCombatGuard &7violations of &f" + data.name + " &7cleared");
        return 1;
    }

    private static int debug(CommandContext<ServerCommandSource> ctx) {
        GuardConfig config = CombatGuard.config();
        config.logLevel = config.logLevel == GuardConfig.LogLevel.DEBUG ? GuardConfig.LogLevel.INFO : GuardConfig.LogLevel.DEBUG;
        send(ctx.getSource(), "&bCombatGuard &7console log level: &f" + config.logLevel + " &8(not saved)");
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        CombatGuard.reloadConfig();
        send(ctx.getSource(), "&bCombatGuard &7config reloaded");
        return 1;
    }
}
