package me.nv08.orbitalstrike;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /osc give &lt;player&gt; &lt;nuke|stab&gt; [amount]
 * /osc strike &lt;nuke|stab&gt; [x y z] [world]
 * /osc reload
 */
public final class OscCommand implements TabExecutor {

    private static final List<String> TYPES = Arrays.stream(StrikeType.values()).map(StrikeType::id).toList();

    private final OrbitalStrikePlugin plugin;

    public OscCommand(OrbitalStrikePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> give(sender, label, args);
            case "strike" -> strike(sender, label, args);
            case "reload" -> reload(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("orbitalstrike.give")) {
            plugin.send(sender, "no-permission");
            return;
        }
        StrikeType type = args.length >= 3 ? StrikeType.fromId(args[2]) : null;
        if (type == null) {
            error(sender, "Usage: /" + label + " give <player> <nuke|stab> [amount]");
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                error(sender, "Amount must be a number.");
                return;
            }
        }
        List<Player> players = resolvePlayers(sender, args[1]);
        if (players.isEmpty()) {
            error(sender, "No player found: " + args[1]);
            return;
        }
        for (Player player : players) {
            for (int i = 0; i < amount; i++) {
                ItemStack rod = plugin.items().create(type);
                player.getInventory().addItem(rod).values()
                        .forEach(leftover -> player.getWorld().dropItem(player.getLocation(), leftover));
            }
            plugin.send(sender, "given",
                    Placeholder.unparsed("amount", String.valueOf(amount)),
                    Placeholder.unparsed("type", type.displayName()),
                    Placeholder.unparsed("player", player.getName()));
        }
    }

    private void strike(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("orbitalstrike.strike")) {
            plugin.send(sender, "no-permission");
            return;
        }
        StrikeType type = args.length >= 2 ? StrikeType.fromId(args[1]) : null;
        if (type == null || (args.length > 2 && args.length < 5)) {
            error(sender, "Usage: /" + label + " strike <nuke|stab> [x y z] [world]");
            return;
        }

        Block target;
        if (args.length >= 5) {
            Location origin = senderLocation(sender);
            World world = args.length >= 6 ? Bukkit.getWorld(args[5])
                    : origin != null ? origin.getWorld() : Bukkit.getWorlds().getFirst();
            if (world == null) {
                error(sender, "Unknown world: " + args[5]);
                return;
            }
            try {
                int x = (int) Math.floor(coordinate(args[2], origin == null ? null : origin.getX()));
                int y = (int) Math.floor(coordinate(args[3], origin == null ? null : origin.getY()));
                int z = (int) Math.floor(coordinate(args[4], origin == null ? null : origin.getZ()));
                y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
                target = world.getBlockAt(x, y, z);
            } catch (NumberFormatException e) {
                error(sender, "Invalid coordinates.");
                return;
            }
        } else if (sender instanceof Player player) {
            target = plugin.strikes().findTarget(player, plugin.settings().maxRange());
            if (target == null) {
                plugin.send(sender, "no-target");
                return;
            }
        } else {
            error(sender, "From the console, give coordinates: /" + label + " strike <nuke|stab> <x> <y> <z> [world]");
            return;
        }

        plugin.fire(type, target, sender instanceof Player player ? player : null, sender);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("orbitalstrike.reload")) {
            plugin.send(sender, "no-permission");
            return;
        }
        plugin.loadSettings();
        plugin.send(sender, "reloaded");
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Orbital Strike Cannon", NamedTextColor.GOLD));
        for (String line : List.of("give <player> <nuke|stab> [amount]", "strike <nuke|stab> [x y z] [world]", "reload")) {
            sender.sendMessage(Component.text("/" + label + " " + line, NamedTextColor.YELLOW));
        }
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private static List<Player> resolvePlayers(CommandSender sender, String arg) {
        try {
            return Bukkit.selectEntities(sender, arg).stream()
                    .filter(Player.class::isInstance)
                    .map(Player.class::cast)
                    .toList();
        } catch (IllegalArgumentException e) {
            Player player = Bukkit.getPlayerExact(arg);
            return player == null ? List.of() : List.of(player);
        }
    }

    private static @Nullable Location senderLocation(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return entity.getLocation();
        }
        if (sender instanceof BlockCommandSender block) {
            return block.getBlock().getLocation();
        }
        return null;
    }

    /** A number, or "~" / "~5" relative to the sender's position. */
    private static double coordinate(String arg, @Nullable Double relativeTo) {
        if (arg.startsWith("~")) {
            if (relativeTo == null) {
                throw new NumberFormatException("~ needs a position");
            }
            return relativeTo + (arg.length() > 1 ? Double.parseDouble(arg.substring(1)) : 0);
        }
        return Double.parseDouble(arg);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("give", "strike", "reload")) {
                if (sender.hasPermission("orbitalstrike." + sub)) {
                    options.add(sub);
                }
            }
        } else if (args[0].equalsIgnoreCase("give") && sender.hasPermission("orbitalstrike.give")) {
            switch (args.length) {
                case 2 -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case 3 -> options.addAll(TYPES);
                case 4 -> options.addAll(List.of("1", "16", "64"));
                default -> { }
            }
        } else if (args[0].equalsIgnoreCase("strike") && sender.hasPermission("orbitalstrike.strike")) {
            switch (args.length) {
                case 2 -> options.addAll(TYPES);
                case 3, 4, 5 -> options.add("~");
                case 6 -> Bukkit.getWorlds().forEach(w -> options.add(w.getName()));
                default -> { }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
