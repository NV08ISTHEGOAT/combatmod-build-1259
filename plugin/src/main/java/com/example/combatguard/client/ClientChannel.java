package com.example.combatguard.client;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.check.CheckRegistry;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.data.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Plugin-message link to the optional Fabric client mod (see {@link StringCodec} for the payload format).
 */
public final class ClientChannel implements PluginMessageListener {
    public static final String CHALLENGE = "combatguard:challenge";
    public static final String REPORT = "combatguard:report";
    private static final int MAX_REPORT = 1 << 18;

    public static void sendChallenge(Player player, String nonce) {
        player.sendPluginMessage(CombatGuardPlugin.instance(), CHALLENGE, StringCodec.encode(nonce));
    }

    public static boolean hasClientMod(Player player) {
        return player.getListeningPluginChannels().contains(CHALLENGE);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!REPORT.equals(channel)) {
            return;
        }
        PlayerData data = PlayerDataManager.get(player);
        String json = StringCodec.decode(message, MAX_REPORT);
        if (data != null) {
            if (json == null) {
                CheckRegistry.CLIENT.malformed(player, data);
            } else {
                CheckRegistry.CLIENT.onReport(player, data, json);
            }
        }
    }
}
