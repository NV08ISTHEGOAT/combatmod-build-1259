package com.example.combatguard.client;

import com.example.combatguard.network.ClientReport;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the integrity report the server asks for. Heavy parts run off the render thread. */
final class IntegrityScanner {
    /** Threads that legitimately have no Java frames. */
    private static final Set<String> NATIVE_THREADS = Set.of(
        "Signal Dispatcher", "Attach Listener", "Notification Thread", "DestroyJavaVM", "Common-Cleaner",
        "Reference Handler", "Finalizer");
    /** Classes whose mixins are worth showing to staff: combat, movement, input and networking. */
    private static final String[] SENSITIVE_TARGETS = {
        "net.minecraft.class_636", "net.minecraft.class_746", "net.minecraft.class_1297", "net.minecraft.class_1309",
        "net.minecraft.class_1657", "net.minecraft.class_757", "net.minecraft.class_304", "net.minecraft.class_312",
        "net.minecraft.class_309", "net.minecraft.class_2535", "net.minecraft.class_634", "net.minecraft.class_1324"
    };
    private static final Set<String> IGNORED_MIXIN_MODS = Set.of("minecraft", "java", "fabricloader", "combatguard", "mixinextras");

    private static final Map<String, Boolean> CLASS_CACHE = new HashMap<>();

    private IntegrityScanner() {
    }

    /** Values that must be read on the render thread. */
    record GameState(double entityReach, double blockReach) {
        static GameState capture(MinecraftClient client) {
            if (client.player == null) {
                return new GameState(0.0, 0.0);
            }
            return new GameState(client.player.getEntityInteractionRange(), client.player.getBlockInteractionRange());
        }
    }

    static ClientReport build(String nonce, GameState state, boolean full) {
        ClientReport report = new ClientReport();
        report.nonce = nonce;
        report.os = System.getProperty("os.name");
        report.entityReach = state.entityReach();
        report.blockReach = state.blockReach();
        report.callOrigins = CallOriginGuard.findings();
        if (!full) {
            return report;
        }
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (report.mods.size() >= 400) {
                break;
            }
            report.mods.add(new ClientReport.Mod(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
        }
        try {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (argument.startsWith("-javaagent") || argument.startsWith("-agentpath") || argument.startsWith("-agentlib")) {
                    report.agents.add(argument.length() > 200 ? argument.substring(0, 200) : argument);
                }
            }
        } catch (Throwable ignored) {
            // java.management unavailable
        }
        scanThreads(report);
        List<String> natives = NativeModules.loaded();
        report.nativeLibraryCount = natives.size();
        for (String path : natives) {
            if (NativeModules.suspicious(path) && report.suspiciousNatives.size() < 20) {
                report.suspiciousNatives.add(path);
            }
        }
        scanMixins(report);
        return report;
    }

    private static void scanThreads(ClientReport report) {
        ClassLoader gameLoader = MinecraftClient.class.getClassLoader();
        Set<String> inMemory = new HashSet<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if ("Attach Listener".equals(thread.getName())) {
                report.attachListener = true;
            }
            if (stack.length == 0 && !NATIVE_THREADS.contains(thread.getName()) && report.nativeThreads.size() < 20) {
                report.nativeThreads.add(thread.getName());
            }
            for (int i = 0; i < Math.min(stack.length, 40); i++) {
                String name = stack[i].getClassName();
                if (name.contains("$$Lambda") || name.contains("/0x") || name.startsWith("java.") || name.startsWith("jdk.")
                    || name.startsWith("sun.")) {
                    continue;
                }
                if (isInMemory(name, gameLoader)) {
                    inMemory.add(name);
                }
            }
        }
        report.inMemoryClasses.addAll(inMemory.stream().limit(20).toList());
    }

    private static boolean isInMemory(String name, ClassLoader loader) {
        return CLASS_CACHE.computeIfAbsent(name, n -> {
            try {
                return ModOrigins.isInMemory(Class.forName(n, false, loader));
            } catch (Throwable e) {
                return false;
            }
        });
    }

    private static void scanMixins(ClientReport report) {
        try {
            MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
            Set<String> sensitive = new HashSet<>();
            for (String target : SENSITIVE_TARGETS) {
                sensitive.add(resolver.mapClassName("intermediary", target));
            }
            for (Config config : Mixins.getConfigs()) {
                Object modId = config.getConfig().getDecoration(FabricUtil.KEY_MOD_ID);
                String mod = modId == null ? config.getName() : modId.toString();
                if (IGNORED_MIXIN_MODS.contains(mod) || mod.startsWith("fabric-")) {
                    continue;
                }
                for (String target : config.getConfig().getTargets()) {
                    String dotted = target.replace('/', '.');
                    if (sensitive.contains(dotted)) {
                        report.sensitiveMixins.computeIfAbsent(mod, k -> new ArrayList<>()).add(dotted);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Mixin internals changed; this part is informational only.
        }
    }
}
