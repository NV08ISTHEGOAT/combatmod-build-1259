package com.example.combatguard.check.client;

import com.example.combatguard.CombatGuardPlugin;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.check.Check;
import com.example.combatguard.client.ClientChannel;
import com.example.combatguard.client.ClientReport;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Talks to the optional CombatGuard Fabric client mod: every minute the server sends a challenge and the mod
 * answers with mods, JVM agents, native libraries, in-memory classes and attack/input calls that did not come from
 * vanilla code. The mod runs on the player's computer, so it is an extra layer, not a guarantee.
 */
public final class ClientIntegrityCheck extends Check<ClientIntegrityCheck.State> {
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class State {
        public boolean hasClient;
        String nonce;
        String previousNonce;
        long challengeSentMs;
        boolean awaiting;
        public ClientReport lastReport;
        final Set<String> reported = new HashSet<>();
    }

    public ClientIntegrityCheck() {
        super("Client", Category.CLIENT, "Cheat mods, injected code, JVM agents and modified reach found by the optional client mod", false, 1.0, 10.0, 0.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public State stateOf(PlayerData data) {
        return state(data);
    }

    /** The client registered our channel: it runs the client mod. */
    public void onChannelRegistered(Player player, PlayerData data) {
        State st = state(data);
        if (!st.hasClient) {
            st.hasClient = true;
            sendChallenge(player, st);
        }
    }

    private static void sendChallenge(Player player, State st) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        st.previousNonce = st.nonce;
        st.nonce = HexFormat.of().formatHex(bytes);
        st.challengeSentMs = System.currentTimeMillis();
        st.awaiting = true;
        ClientChannel.sendChallenge(player, st.nonce);
    }

    /** Every server tick. */
    public void tick(Player player, PlayerData data) {
        State st = state(data);
        GuardConfig.ClientSettings settings = CombatGuardPlugin.config().client;
        long now = System.currentTimeMillis();
        if (!st.hasClient) {
            if (settings.requireClient && now - data.joinedAtMs > 5000L) {
                player.kick(AlertManager.component(settings.requireClientKickMessage));
            }
            return;
        }
        if (!enabled()) {
            return;
        }
        if (st.awaiting && now - st.challengeSentMs > settings.responseTimeoutSeconds * 1000L) {
            st.awaiting = false;
            flag(player, data, "timeout", "client mod stopped answering integrity challenges", 5.0);
            if (settings.requireClient) {
                player.kick(AlertManager.component(settings.requireClientKickMessage));
            }
        } else if (!st.awaiting && now - st.challengeSentMs > settings.challengeIntervalSeconds * 1000L) {
            sendChallenge(player, st);
        }
    }

    public void malformed(Player player, PlayerData data) {
        flag(player, data, "malformed", "unreadable integrity report", 5.0);
    }

    public void onReport(Player player, PlayerData data, String json) {
        State st = state(data);
        ClientReport report;
        try {
            report = GSON.fromJson(json, ClientReport.class);
        } catch (JsonParseException e) {
            malformed(player, data);
            return;
        }
        if (report == null || report.nonce == null || !(report.nonce.equals(st.nonce) || report.nonce.equals(st.previousNonce))) {
            flag(player, data, "nonce", "integrity report with a wrong or replayed nonce", 5.0);
            return;
        }
        st.awaiting = false;
        st.lastReport = report;
        data.clientVersion = "combatguard-client";
        evaluate(player, data, st, report);
    }

    private void once(Player player, PlayerData data, State st, String key, String type, String info, double weight) {
        if (st.reported.add(key)) {
            flag(player, data, type, info, weight);
        }
    }

    private static double attribute(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    private void evaluate(Player player, PlayerData data, State st, ClientReport report) {
        GuardConfig.ClientSettings settings = CombatGuardPlugin.config().client;
        Set<String> blacklist = new HashSet<>();
        settings.blacklistedMods.forEach(id -> blacklist.add(id.toLowerCase(Locale.ROOT)));
        Set<String> trusted = new HashSet<>();
        settings.trustedCallerMods.forEach(id -> trusted.add(id.toLowerCase(Locale.ROOT)));
        if (report.mods != null) {
            for (ClientReport.Mod mod : report.mods) {
                if (mod.id != null && blacklist.contains(mod.id.toLowerCase(Locale.ROOT))) {
                    once(player, data, st, "mod:" + mod.id, "mod", "cheat mod installed: " + mod.id, 10.0);
                }
            }
        }
        if (report.callOrigins != null) {
            for (ClientReport.CallFinding f : report.callOrigins) {
                String mod = f.originMod == null ? "unknown" : f.originMod;
                if (!trusted.contains(mod.toLowerCase(Locale.ROOT))) {
                    once(player, data, st, "call:" + f.target + "|" + f.origin, "injection",
                        f.target + " called by " + f.origin + " (" + mod + ", x" + f.count + ")", 10.0);
                }
            }
        }
        if (report.inMemoryClasses != null) {
            for (String name : report.inMemoryClasses) {
                once(player, data, st, "class:" + name, "injection", "code running from memory: " + name, 10.0);
            }
        }
        double entityReach = attribute(player, Attribute.ENTITY_INTERACTION_RANGE);
        double blockReach = attribute(player, Attribute.BLOCK_INTERACTION_RANGE);
        if (report.entityReach > entityReach + 0.001 || report.blockReach > blockReach + 0.001) {
            once(player, data, st, "reach", "reach", String.format(Locale.ROOT, "client reach %.2f/%.2f, server %.2f/%.2f",
                report.entityReach, report.blockReach, entityReach, blockReach), 10.0);
        }
        if (report.agents != null) {
            for (String agent : report.agents) {
                if (!agent.startsWith("-agentlib:jdwp")) {
                    once(player, data, st, "agent:" + agent, "agent", "JVM agent: " + agent, 2.0);
                }
            }
        }
        if (report.attachListener) {
            once(player, data, st, "attach", "attach", "something attached to the game's JVM at runtime", 1.0);
        }
        if (report.suspiciousNatives != null) {
            int flagged = 0;
            for (String library : report.suspiciousNatives) {
                String lower = library.toLowerCase(Locale.ROOT);
                boolean trustedLibrary = settings.trustedNativeLibraries.stream()
                    .anyMatch(t -> !t.isEmpty() && lower.contains(t.toLowerCase(Locale.ROOT)));
                if (trustedLibrary || flagged >= 3) {
                    continue;
                }
                flagged++;
                once(player, data, st, "native:" + library, "native", "native library from an unusual folder: " + library, 1.0);
            }
        }
    }
}
