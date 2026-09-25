package com.example.combatguard.check.client;

import com.example.combatguard.CombatGuard;
import com.example.combatguard.alert.AlertManager;
import com.example.combatguard.check.Check;
import com.example.combatguard.config.GuardConfig;
import com.example.combatguard.data.PlayerData;
import com.example.combatguard.network.ClientReport;
import com.example.combatguard.network.GuardPayloads;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Server side of the client integrity module. Players who run the CombatGuard client mod are sent a challenge
 * every minute and answer with a report of mods, JVM agents, native libraries, in-memory classes and
 * attack/input calls that did not come from vanilla code. The client can be modified, so this is a deterrent
 * that catches injected clients and unmodified cheat mods; the server-side checks do not depend on it.
 */
public final class ClientIntegrityCheck extends Check<ClientIntegrityCheck.State> {
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class State {
        boolean hasClient;
        String nonce;
        String previousNonce;
        long challengeSentMs;
        boolean awaiting;
        public ClientReport lastReport;
        public long lastReportMs;
        final Set<String> reported = new HashSet<>();
    }

    public ClientIntegrityCheck() {
        super("Client", Category.CLIENT, "Cheat mods, injected code, JVM agents and modified reach found by the client mod", false, 1.0, 10.0, 0.0);
    }

    @Override
    public State newState() {
        return new State();
    }

    public State stateOf(PlayerData data) {
        return state(data);
    }

    public void onJoin(ServerPlayerEntity player, PlayerData data) {
        State st = state(data);
        st.hasClient = ServerPlayNetworking.canSend(player, GuardPayloads.Challenge.ID);
        if (st.hasClient) {
            sendChallenge(player, st);
        } else if (CombatGuard.config().client.requireClient) {
            player.getEntityWorld().getServer().execute(() ->
                player.networkHandler.disconnect(Text.literal(CombatGuard.config().client.requireClientKickMessage)));
        }
    }

    private static void sendChallenge(ServerPlayerEntity player, State st) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        st.previousNonce = st.nonce;
        st.nonce = HexFormat.of().formatHex(bytes);
        st.challengeSentMs = System.currentTimeMillis();
        st.awaiting = true;
        ServerPlayNetworking.send(player, new GuardPayloads.Challenge(st.nonce));
    }

    /** Every server tick. */
    public void tick(ServerPlayerEntity player, PlayerData data) {
        State st = state(data);
        if (!st.hasClient || !enabled()) {
            return;
        }
        GuardConfig.ClientSettings settings = CombatGuard.config().client;
        long now = System.currentTimeMillis();
        if (st.awaiting && now - st.challengeSentMs > settings.responseTimeoutSeconds * 1000L) {
            st.awaiting = false;
            flag(player, data, "timeout", "client mod stopped answering integrity challenges", 5.0);
            if (settings.requireClient) {
                player.networkHandler.disconnect(Text.literal(settings.requireClientKickMessage));
            }
        } else if (!st.awaiting && now - st.challengeSentMs > settings.challengeIntervalSeconds * 1000L) {
            sendChallenge(player, st);
        }
    }

    public void onReport(ServerPlayerEntity player, PlayerData data, String json) {
        State st = state(data);
        ClientReport report;
        try {
            report = GSON.fromJson(json, ClientReport.class);
        } catch (JsonParseException e) {
            flag(player, data, "malformed", "unreadable integrity report", 5.0);
            return;
        }
        if (report == null || report.nonce == null
            || !(report.nonce.equals(st.nonce) || report.nonce.equals(st.previousNonce))) {
            flag(player, data, "nonce", "integrity report with a wrong or replayed nonce", 5.0);
            return;
        }
        st.awaiting = false;
        st.lastReport = report;
        st.lastReportMs = System.currentTimeMillis();
        data.clientVersion = "combatguard " + (report.version > 0 ? report.version : "?");
        evaluate(player, data, st, report);
    }

    private void once(ServerPlayerEntity player, PlayerData data, State st, String key, String type, String info, double weight) {
        if (st.reported.add(key)) {
            flag(player, data, type, info, weight);
        }
    }

    private void evaluate(ServerPlayerEntity player, PlayerData data, State st, ClientReport report) {
        GuardConfig.ClientSettings settings = CombatGuard.config().client;
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
            for (ClientReport.CallFinding finding : report.callOrigins) {
                String mod = finding.originMod == null ? "unknown" : finding.originMod;
                if (trusted.contains(mod.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                once(player, data, st, "call:" + finding.target + "|" + finding.origin,
                    "injection", finding.target + " called by " + finding.origin + " (" + mod + ", x" + finding.count + ")", 10.0);
            }
        }
        if (report.inMemoryClasses != null) {
            for (String name : report.inMemoryClasses) {
                once(player, data, st, "class:" + name, "injection", "code running from memory: " + name, 10.0);
            }
        }
        double serverEntityReach = player.getEntityInteractionRange();
        double serverBlockReach = player.getBlockInteractionRange();
        if (report.entityReach > serverEntityReach + 0.001 || report.blockReach > serverBlockReach + 0.001) {
            once(player, data, st, "reach", "reach", String.format(Locale.ROOT, "client reach %.2f/%.2f, server %.2f/%.2f",
                report.entityReach, report.blockReach, serverEntityReach, serverBlockReach), 10.0);
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
                if (isTrustedNative(library, settings) || flagged >= 3) {
                    continue;
                }
                flagged++;
                once(player, data, st, "native:" + library, "native", "native library from an unusual folder: " + library, 1.0);
            }
        }
        if (report.nativeThreads != null && !report.nativeThreads.isEmpty()) {
            AlertManager.debug("{} has Java threads without Java frames: {}", data.name, report.nativeThreads);
        }
    }

    private static boolean isTrustedNative(String library, GuardConfig.ClientSettings settings) {
        String lower = library.toLowerCase(Locale.ROOT);
        for (String trusted : settings.trustedNativeLibraries) {
            if (!trusted.isEmpty() && lower.contains(trusted.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
