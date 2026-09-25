package com.example.combatguard.client;

import com.example.combatguard.network.ClientReport;
import com.example.combatguard.network.GuardPayloads;
import com.google.gson.Gson;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client half of CombatGuard. It never changes gameplay; it only answers the server's integrity challenges.
 */
public final class CombatGuardClient implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CombatGuard scanner");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String nonce;
    private static long lastQuickReportMs;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(GuardPayloads.Challenge.ID, (payload, context) -> {
            nonce = payload.nonce();
            IntegrityScanner.GameState state = IntegrityScanner.GameState.capture(context.client());
            String challenge = payload.nonce();
            SCANNER.execute(() -> send(IntegrityScanner.build(challenge, state, true)));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> nonce = null);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Report new call-origin findings right away instead of waiting for the next challenge.
            String current = nonce;
            long now = System.currentTimeMillis();
            if (current != null && client.player != null && now - lastQuickReportMs > 5000L && CallOriginGuard.takeNewFinding()) {
                lastQuickReportMs = now;
                IntegrityScanner.GameState state = IntegrityScanner.GameState.capture(client);
                SCANNER.execute(() -> send(IntegrityScanner.build(current, state, false)));
            }
        });
    }

    private static void send(ClientReport report) {
        String json = GSON.toJson(report);
        if (json.length() > GuardPayloads.Report.MAX_LENGTH) {
            json = json.substring(0, GuardPayloads.Report.MAX_LENGTH);
        }
        String finalJson = json;
        MinecraftClient.getInstance().execute(() -> {
            if (ClientPlayNetworking.canSend(GuardPayloads.Report.ID)) {
                ClientPlayNetworking.send(new GuardPayloads.Report(finalJson));
            }
        });
    }
}
