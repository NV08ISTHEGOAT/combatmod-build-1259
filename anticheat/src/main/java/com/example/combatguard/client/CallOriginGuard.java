package com.example.combatguard.client;

import com.example.combatguard.network.ClientReport;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.MinecraftClient;

import java.lang.StackWalker.StackFrame;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies who calls attack, use and input methods. In vanilla, attackEntity is only ever called by
 * MinecraftClient.doAttack, doAttack only by handleInputEvents, and so on. KillAura modules, autoclickers and
 * injected clients call these methods from their own code, through reflection, or from native hooks, and that
 * shows up on the stack. Legit mods that do this can be trusted by id in the server config.
 */
public final class CallOriginGuard {
    private static final StackWalker WALKER = StackWalker.getInstance(EnumSet.of(
        StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_REFLECT_FRAMES, StackWalker.Option.SHOW_HIDDEN_FRAMES));
    /** Mixin handler names look like handler$zbc000$modid$name (also wrapOperation$, redirect$, ...). */
    private static final Pattern HANDLER = Pattern.compile("^[a-zA-Z]+\\$[a-z0-9]{6}\\$([^$]+)\\$.*");

    private static final String MC = "net.minecraft.class_310";
    private static final String IM = "net.minecraft.class_636";
    private static final String KB = "net.minecraft.class_304";
    private static final String KEYBOARD = "net.minecraft.class_309";
    private static final String MOUSE = "net.minecraft.class_312";

    public enum Sensitive {
        ATTACK_ENTITY("attackEntity", IM, "method_2918", "(Lnet/minecraft/class_1657;Lnet/minecraft/class_1297;)V",
            MC, "method_1536", "()Z"),
        ATTACK_BLOCK("attackBlock", IM, "method_2910", "(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z",
            MC, "method_1536", "()Z", IM, "method_2902", "(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z"),
        INTERACT_BLOCK("interactBlock", IM, "method_2896", "(Lnet/minecraft/class_746;Lnet/minecraft/class_1268;Lnet/minecraft/class_3965;)Lnet/minecraft/class_1269;",
            MC, "method_1583", "()V"),
        INTERACT_ITEM("interactItem", IM, "method_2919", "(Lnet/minecraft/class_1657;Lnet/minecraft/class_1268;)Lnet/minecraft/class_1269;",
            MC, "method_1583", "()V"),
        INTERACT_ENTITY("interactEntity", IM, "method_2905", "(Lnet/minecraft/class_1657;Lnet/minecraft/class_1297;Lnet/minecraft/class_1268;)Lnet/minecraft/class_1269;",
            MC, "method_1583", "()V"),
        DO_ATTACK("doAttack", MC, "method_1536", "()Z",
            MC, "method_1508", "()V"),
        DO_ITEM_USE("doItemUse", MC, "method_1583", "()V",
            MC, "method_1508", "()V"),
        KEY_PRESSED("KeyBinding.onKeyPressed", KB, "method_1420", "(Lnet/minecraft/class_3675$class_306;)V",
            KEYBOARD, "method_1466", "(JILnet/minecraft/class_11908;)V", MOUSE, "method_1601", "(JLnet/minecraft/class_11910;I)V");

        final String label;
        final String owner;
        final String name;
        final String desc;
        final String[] callers;
        String runtimeOwner;
        String runtimeName;
        final List<String[]> runtimeCallers = new ArrayList<>();

        Sensitive(String label, String owner, String name, String desc, String... callers) {
            this.label = label;
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.callers = callers;
        }
    }

    private static final Map<String, ClientReport.CallFinding> FINDINGS = new LinkedHashMap<>();
    private static volatile boolean resolved;
    private static volatile boolean newFinding;

    private CallOriginGuard() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        for (Sensitive s : Sensitive.values()) {
            s.runtimeOwner = resolver.mapClassName("intermediary", s.owner);
            s.runtimeName = resolver.mapMethodName("intermediary", s.owner, s.name, s.desc);
            for (int i = 0; i + 2 < s.callers.length; i += 3) {
                s.runtimeCallers.add(new String[]{
                    resolver.mapClassName("intermediary", s.callers[i]),
                    resolver.mapMethodName("intermediary", s.callers[i], s.callers[i + 1], s.callers[i + 2])});
            }
        }
        resolved = true;
    }

    private static boolean isHandlerOrHidden(StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        String cls = type.getName();
        return type.isHidden()
            || cls.startsWith("com.llamalad7.mixinextras.")
            || cls.startsWith("java.lang.invoke.")
            || HANDLER.matcher(frame.getMethodName()).matches();
    }

    private static boolean isInfrastructure(String cls) {
        return cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
            || cls.startsWith("com.llamalad7.mixinextras.") || cls.startsWith("org.spongepowered.");
    }

    /** Called at the start of every sensitive method. */
    public static void verify(Sensitive sensitive) {
        try {
            check(sensitive);
        } catch (Throwable ignored) {
            // Never break the game because of the anticheat.
        }
    }

    private static void check(Sensitive s) {
        resolve();
        List<StackFrame> frames = WALKER.walk(stream -> stream.limit(48).toList());
        int target = -1;
        for (int i = 0; i < frames.size(); i++) {
            StackFrame f = frames.get(i);
            if (f.getDeclaringClass().getName().equals(s.runtimeOwner) && f.getMethodName().equals(s.runtimeName)) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            return;
        }
        String thread = Thread.currentThread().getName();
        boolean renderThread = MinecraftClient.getInstance().isOnThread();

        List<String> handlers = new ArrayList<>();
        int j = target + 1;
        while (j < frames.size() && isHandlerOrHidden(frames.get(j))) {
            Matcher m = HANDLER.matcher(frames.get(j).getMethodName());
            if (m.matches()) {
                handlers.add(m.group(1));
            }
            j++;
        }
        if (j >= frames.size()) {
            record(s, "<no Java caller>", "native code", "native", thread);
            return;
        }
        StackFrame caller = frames.get(j);
        if (renderThread && handlers.isEmpty()) {
            for (String[] allowed : s.runtimeCallers) {
                if (caller.getDeclaringClass().getName().equals(allowed[0]) && caller.getMethodName().equals(allowed[1])) {
                    return;
                }
            }
        }
        // Not the vanilla path. The origin is the first frame that is neither JDK/mixin plumbing nor Minecraft
        // itself, which skips accessor methods that cheat mods add to vanilla classes.
        StackFrame origin = caller;
        for (int k = j; k < frames.size(); k++) {
            String cls = frames.get(k).getDeclaringClass().getName();
            if (!isInfrastructure(cls) && !cls.startsWith("net.minecraft.")) {
                origin = frames.get(k);
                break;
            }
        }
        String originMod = handlers.isEmpty() ? ModOrigins.modOf(origin.getDeclaringClass()) : handlers.get(0);
        record(s, caller.getDeclaringClass().getName() + "." + caller.getMethodName(),
            origin.getDeclaringClass().getName() + "." + origin.getMethodName(), originMod, renderThread ? thread : thread + " (not the render thread)");
    }

    private static synchronized void record(Sensitive s, String caller, String origin, String originMod, String thread) {
        String key = s.label + "|" + origin;
        ClientReport.CallFinding finding = FINDINGS.get(key);
        if (finding == null) {
            if (FINDINGS.size() >= 50) {
                return;
            }
            finding = new ClientReport.CallFinding();
            finding.target = s.label;
            finding.caller = caller;
            finding.origin = origin;
            finding.originMod = originMod;
            finding.thread = thread;
            FINDINGS.put(key, finding);
            newFinding = true;
        }
        finding.count++;
    }

    public static synchronized List<ClientReport.CallFinding> findings() {
        return new ArrayList<>(FINDINGS.values());
    }

    /** True once after something new was recorded, so the client can report it without waiting a minute. */
    public static boolean takeNewFinding() {
        boolean value = newFinding;
        newFinding = false;
        return value;
    }
}
