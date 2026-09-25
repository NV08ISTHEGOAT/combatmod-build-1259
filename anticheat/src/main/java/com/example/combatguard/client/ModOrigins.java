package com.example.combatguard.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maps a class to the mod whose jar it was loaded from. */
final class ModOrigins {
    private static volatile Map<Path, String> byPath;
    private static final Map<Class<?>, String> CACHE = new ConcurrentHashMap<>();

    private ModOrigins() {
    }

    private static Map<Path, String> paths() {
        if (byPath == null) {
            Map<Path, String> map = new HashMap<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                try {
                    for (Path path : mod.getOrigin().getPaths()) {
                        map.put(path.toAbsolutePath().normalize(), mod.getMetadata().getId());
                    }
                } catch (UnsupportedOperationException ignored) {
                    // Nested mods have no paths of their own.
                }
            }
            byPath = map;
        }
        return byPath;
    }

    /** "in-memory" when the class has no code source at all, which is how injected code usually looks. */
    static String modOf(Class<?> type) {
        return CACHE.computeIfAbsent(type, ModOrigins::lookup);
    }

    static boolean isInMemory(Class<?> type) {
        if (type.getClassLoader() == null || type.isHidden() || type.getName().startsWith("jdk.proxy")
            || type.getName().startsWith("org.spongepowered.asm.synthetic")) {
            return false;
        }
        var domain = type.getProtectionDomain();
        return domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null;
    }

    private static String lookup(Class<?> type) {
        if (type.getClassLoader() == null || type.getClassLoader() == ClassLoader.getPlatformClassLoader()) {
            return "java";
        }
        if (isInMemory(type)) {
            return "in-memory";
        }
        CodeSource source = type.getProtectionDomain().getCodeSource();
        try {
            URL url = source.getLocation();
            Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
            String mod = paths().get(path);
            if (mod != null) {
                return mod;
            }
            for (Map.Entry<Path, String> entry : paths().entrySet()) {
                if (path.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return path.getFileName().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
