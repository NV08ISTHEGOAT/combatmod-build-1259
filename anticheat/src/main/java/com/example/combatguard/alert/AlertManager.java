package com.example.combatguard.alert;

import com.example.combatguard.CombatGuard;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Staff alerts, console logging (with levels) and the violation log file. */
public final class AlertManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CombatGuard");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService FILE_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CombatGuard log writer");
        thread.setDaemon(true);
        return thread;
    });

    private AlertManager() {
    }

    /** Everything a flag message can show. */
    public record Flag(ServerPlayerEntity player, PlayerData data, String check, String type, String details,
                       double vl, int ping, double tps) {
    }

    public static String colour(String text) {
        return text.replace('&', '§');
    }

    public static boolean isStaff(ServerPlayerEntity player) {
        return player.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS);
    }

    public static String format(String template, Flag flag) {
        ServerPlayerEntity p = flag.player();
        return template
            .replace("%player%", flag.data().name)
            .replace("%uuid%", flag.data().uuid.toString())
            .replace("%check%", flag.check())
            .replace("%type%", flag.type())
            .replace("%vl%", String.format(Locale.ROOT, "%.1f", flag.vl()))
            .replace("%ping%", Integer.toString(flag.ping()))
            .replace("%tps%", String.format(Locale.ROOT, "%.1f", flag.tps()))
            .replace("%version%", flag.data().clientVersion)
            .replace("%world%", p.getEntityWorld().getRegistryKey().getValue().toString())
            .replace("%x%", String.format(Locale.ROOT, "%.1f", p.getX()))
            .replace("%y%", String.format(Locale.ROOT, "%.1f", p.getY()))
            .replace("%z%", String.format(Locale.ROOT, "%.1f", p.getZ()))
            .replace("%details%", flag.details())
            .replace("%info%", flag.details());
    }

    /**
     * @param aboveThreshold staff with alerts on get the message; below the alert threshold only staff in
     *                       verbose mode do
     */
    public static void alertStaff(MinecraftServer server, Flag flag, boolean aboveThreshold) {
        Text text = Text.literal(colour(format(CombatGuard.config().alertFormat, flag)));
        if (aboveThreshold) {
            info("{} failed {}/{} VL={} ({})", flag.data().name, flag.check(), flag.type(),
                String.format(Locale.ROOT, "%.1f", flag.vl()), flag.details());
        }
        for (ServerPlayerEntity staff : server.getPlayerManager().getPlayerList()) {
            if (!isStaff(staff)) {
                continue;
            }
            PlayerData data = PlayerDataManager.get(staff);
            boolean alerts = data == null || data.alerts;
            boolean verbose = data != null && data.verbose;
            if ((aboveThreshold && alerts) || verbose) {
                staff.sendMessage(text, false);
            }
        }
    }

    public static void log(Flag flag) {
        debug("{} flagged {}/{} VL={} ping={} tps={} ({})", flag.data().name, flag.check(), flag.type(),
            String.format(Locale.ROOT, "%.2f", flag.vl()), flag.ping(), String.format(Locale.ROOT, "%.1f", flag.tps()), flag.details());
        if (!CombatGuard.config().logToFile) {
            return;
        }
        ServerPlayerEntity p = flag.player();
        String line = String.format(Locale.ROOT, "[%s] %s %s/%s VL=%.2f ping=%d tps=%.1f pos=%.1f,%.1f,%.1f (%s)%n",
            LocalDateTime.now().format(TIME), flag.data().name, flag.check(), flag.type(), flag.vl(), flag.ping(), flag.tps(),
            p.getX(), p.getY(), p.getZ(), flag.details());
        FILE_WRITER.execute(() -> {
            Path path = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("combatguard.log");
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                error("Could not write violation log", e);
            }
        });
    }

    private static boolean enabled(GuardConfig.LogLevel level) {
        GuardConfig config = CombatGuard.config();
        return config != null && config.logLevel.ordinal() >= level.ordinal() && config.logLevel != GuardConfig.LogLevel.OFF;
    }

    public static void error(String message, Object... args) {
        if (enabled(GuardConfig.LogLevel.ERROR)) {
            LOGGER.error(message, args);
        }
    }

    public static void warn(String message, Object... args) {
        if (enabled(GuardConfig.LogLevel.WARN)) {
            LOGGER.warn(message, args);
        }
    }

    public static void info(String message, Object... args) {
        if (enabled(GuardConfig.LogLevel.INFO)) {
            LOGGER.info(message, args);
        }
    }

    public static void debug(String message, Object... args) {
        if (enabled(GuardConfig.LogLevel.DEBUG)) {
            LOGGER.info("[debug] " + message, args);
        }
    }

    public static void trace(String message, Object... args) {
        if (enabled(GuardConfig.LogLevel.TRACE)) {
            LOGGER.info("[trace] " + message, args);
        }
    }
}
