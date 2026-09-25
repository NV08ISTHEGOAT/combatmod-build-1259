package com.example.combatguard.alert;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Staff alerts, console logging with levels, and the violation log file (written off the server thread). */
public final class AlertManager {
    public static final String ALERT_PERMISSION = "combatguard.alerts";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final ExecutorService FILE_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CombatGuard log writer");
        thread.setDaemon(true);
        return thread;
    });

    private static Logger logger = Logger.getLogger("CombatGuard");
    private static File logFile;

    private AlertManager() {
    }

    public static void init(Logger pluginLogger, File dataFolder) {
        logger = pluginLogger;
        logFile = new File(dataFolder, "violations.log");
    }

    public record Flag(Player player, PlayerData data, String check, String type, String details, double vl, int ping, double tps) {
    }

    public static Component component(String legacyAmpersandText) {
        return LEGACY.deserialize(legacyAmpersandText);
    }

    public static String format(String template, Flag flag) {
        Player p = flag.player();
        return template
            .replace("%player%", flag.data().name)
            .replace("%uuid%", flag.data().uuid.toString())
            .replace("%check%", flag.check())
            .replace("%type%", flag.type())
            .replace("%vl%", String.format(Locale.ROOT, "%.1f", flag.vl()))
            .replace("%ping%", Integer.toString(flag.ping()))
            .replace("%tps%", String.format(Locale.ROOT, "%.1f", flag.tps()))
            .replace("%version%", flag.data().clientVersion)
            .replace("%world%", p.getWorld().getName())
            .replace("%x%", String.format(Locale.ROOT, "%.1f", p.getX()))
            .replace("%y%", String.format(Locale.ROOT, "%.1f", p.getY()))
            .replace("%z%", String.format(Locale.ROOT, "%.1f", p.getZ()))
            .replace("%details%", flag.details())
            .replace("%info%", flag.details());
    }

    /** Staff with alerts on see flags above the alert threshold; staff in verbose mode see every flag. */
    public static void alertStaff(Flag flag, boolean aboveThreshold) {
        Component text = component(format(CombatGuardPlugin.config().alertFormat, flag));
        if (aboveThreshold) {
            info(flag.data().name + " failed " + flag.check() + "/" + flag.type() + " VL="
                + String.format(Locale.ROOT, "%.1f", flag.vl()) + " (" + flag.details() + ")");
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission(ALERT_PERMISSION)) {
                continue;
            }
            PlayerData data = PlayerDataManager.get(staff);
            boolean alerts = data == null || data.alerts;
            boolean verbose = data != null && data.verbose;
            if ((aboveThreshold && alerts) || verbose) {
                staff.sendMessage(text);
            }
        }
    }

    public static void log(Flag flag) {
        debug(flag.data().name + " flagged " + flag.check() + "/" + flag.type() + " VL=" + String.format(Locale.ROOT, "%.2f", flag.vl())
            + " ping=" + flag.ping() + " (" + flag.details() + ")");
        if (!CombatGuardPlugin.config().logToFile || logFile == null) {
            return;
        }
        Player p = flag.player();
        String line = String.format(Locale.ROOT, "[%s] %s %s/%s VL=%.2f ping=%d tps=%.1f %s %.1f,%.1f,%.1f (%s)%n",
            LocalDateTime.now().format(TIME), flag.data().name, flag.check(), flag.type(), flag.vl(), flag.ping(), flag.tps(),
            p.getWorld().getName(), p.getX(), p.getY(), p.getZ(), flag.details());
        File file = logFile;
        FILE_WRITER.execute(() -> {
            try {
                Files.createDirectories(file.getParentFile().toPath());
                Files.writeString(file.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                error("Could not write violation log: " + e.getMessage());
            }
        });
    }

    private static boolean enabled(GuardConfig.LogLevel level) {
        GuardConfig config = CombatGuardPlugin.config();
        return config != null && config.logLevel != GuardConfig.LogLevel.OFF && config.logLevel.ordinal() >= level.ordinal();
    }

    public static void error(String message) {
        if (enabled(GuardConfig.LogLevel.ERROR)) {
            logger.severe(message);
        }
    }

    public static void warn(String message) {
        if (enabled(GuardConfig.LogLevel.WARN)) {
            logger.warning(message);
        }
    }

    public static void info(String message) {
        if (enabled(GuardConfig.LogLevel.INFO)) {
            logger.info(message);
        }
    }

    public static void debug(String message) {
        if (enabled(GuardConfig.LogLevel.DEBUG)) {
            logger.info("[debug] " + message);
        }
    }

    public static void shutdown() {
        FILE_WRITER.shutdown();
    }
}
