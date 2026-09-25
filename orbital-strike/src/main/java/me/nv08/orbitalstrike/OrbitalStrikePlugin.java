package me.nv08.orbitalstrike;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

public final class OrbitalStrikePlugin extends JavaPlugin {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private Settings settings;
    private CannonItems items;
    private StrikeManager strikes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        items = new CannonItems(this);
        strikes = new StrikeManager(this);

        getServer().getPluginManager().registerEvents(new RodListener(this), this);
        OscCommand command = new OscCommand(this);
        PluginCommand osc = getCommand("osc");
        if (osc != null) {
            osc.setExecutor(command);
            osc.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (strikes != null) {
            strikes.shutdown();
        }
    }

    public void loadSettings() {
        reloadConfig();
        settings = Settings.from(getConfig());
    }

    /** Launches a strike and sends the "fired"/"broadcast" messages. */
    public void fire(StrikeType type, Block target, @Nullable Player source, @Nullable CommandSender feedback) {
        strikes.launch(type, target, source);

        TagResolver tags = TagResolver.resolver(
                Placeholder.unparsed("type", type.displayName()),
                Placeholder.unparsed("player", source != null ? source.getName() : "Console"),
                Placeholder.unparsed("x", String.valueOf(target.getX())),
                Placeholder.unparsed("y", String.valueOf(target.getY())),
                Placeholder.unparsed("z", String.valueOf(target.getZ()))
        );
        if (feedback != null) {
            send(feedback, "fired", tags);
        }
        if (settings.broadcast()) {
            String raw = getConfig().getString("messages.broadcast", "");
            if (!raw.isEmpty()) {
                Bukkit.broadcast(miniMessage.deserialize(getConfig().getString("messages.prefix", "") + raw, tags));
            }
        }
    }

    public void send(CommandSender to, String key, TagResolver... tags) {
        String raw = getConfig().getString("messages." + key, "");
        if (!raw.isEmpty()) {
            to.sendMessage(miniMessage.deserialize(getConfig().getString("messages.prefix", "") + raw, tags));
        }
    }

    public MiniMessage miniMessage() {
        return miniMessage;
    }

    public Settings settings() {
        return settings;
    }

    public CannonItems items() {
        return items;
    }

    public StrikeManager strikes() {
        return strikes;
    }
}
