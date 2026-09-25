package com.example.combatguard.client;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** A VarInt length followed by UTF-8 bytes: Minecraft's string codec, which the client mod's payloads use. */
public final class StringCodec {
    private StringCodec() {
    }

    public static byte[] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 5);
        int value = bytes.length;
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    /** @return the string, or null when the payload is malformed or longer than {@code maxBytes} */
    public static String decode(byte[] message, int maxBytes) {
        int value = 0;
        int position = 0;
        int index = 0;
        while (true) {
            if (index >= message.length || position >= 35) {
                return null;
            }
            byte b = message[index++];
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                break;
            }
            position += 7;
        }
        if (value < 0 || value > maxBytes || index + value > message.length) {
            return null;
        }
        return new String(message, index, value, StandardCharsets.UTF_8);
    }
}
