package com.example.combatguard.client;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Tlhelp32;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lists the native libraries loaded into the game process. Injected clients are DLLs loaded into the Java
 * process, usually from a temp or download folder. Libraries the game itself extracts (LWJGL, JNA) and common
 * overlays are expected and ignored.
 */
final class NativeModules {
    private static final Pattern EXPECTED = Pattern.compile(
        "(?i).*(lwjgl|jna|jemalloc|glfw|openal|jinput|discord|graphics-hook|obs|nvidia|nvapi|amd|ati|steam|rtss|"
            + "overlay|sqlite|zstd|lz4|netty|jansi|conpty|winpty|oshi|mesa|vulkan|opengl|d3d|dxgi).*");
    private static final String[] UNUSUAL_FOLDERS = {
        "/temp/", "/tmp/", "/downloads/", "/desktop/", "/appdata/roaming/", "/users/public/"
    };

    private NativeModules() {
    }

    static List<String> loaded() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                List<String> paths = new ArrayList<>();
                for (Tlhelp32.MODULEENTRY32W module : Kernel32Util.getModules(Kernel32.INSTANCE.GetCurrentProcessId())) {
                    paths.add(module.szExePath());
                }
                return paths;
            }
            if (os.contains("linux")) {
                Set<String> paths = new LinkedHashSet<>();
                for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
                    int slash = line.indexOf('/');
                    if (slash >= 0 && line.contains(".so")) {
                        paths.add(line.substring(slash).trim());
                    }
                }
                return new ArrayList<>(paths);
            }
        } catch (Throwable ignored) {
            // JNA missing or access denied: report nothing rather than guess.
        }
        return List.of();
    }

    static boolean suspicious(String path) {
        String normalised = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalised.substring(normalised.lastIndexOf('/') + 1);
        if (EXPECTED.matcher(name).matches()) {
            return false;
        }
        for (String folder : UNUSUAL_FOLDERS) {
            if (normalised.contains(folder)) {
                return true;
            }
        }
        return false;
    }
}
