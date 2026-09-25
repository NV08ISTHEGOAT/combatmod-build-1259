package com.example.combatguard.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class GuardPayloads {
    private GuardPayloads() {
    }

    /** Server to client: "send me an integrity report tagged with this nonce". */
    public record Challenge(String nonce) implements CustomPayload {
        public static final Id<Challenge> ID = new Id<>(Identifier.of("combatguard", "challenge"));
        public static final PacketCodec<RegistryByteBuf, Challenge> CODEC = PacketCodec.tuple(
            PacketCodecs.string(64), Challenge::nonce,
            Challenge::new
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client to server: a {@link ClientReport} as JSON. */
    public record Report(String json) implements CustomPayload {
        public static final Id<Report> ID = new Id<>(Identifier.of("combatguard", "report"));
        public static final int MAX_LENGTH = 1 << 18;
        public static final PacketCodec<RegistryByteBuf, Report> CODEC = PacketCodec.tuple(
            PacketCodecs.string(MAX_LENGTH), Report::json,
            Report::new
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Challenge.ID, Challenge.CODEC);
        PayloadTypeRegistry.playC2S().register(Report.ID, Report.CODEC);
    }
}
