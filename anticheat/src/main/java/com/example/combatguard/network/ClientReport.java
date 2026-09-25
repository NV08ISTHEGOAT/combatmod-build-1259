package com.example.combatguard.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the client side scanner found. Serialised as JSON inside {@link GuardPayloads.Report}. */
public final class ClientReport {
    public int version = 1;
    public String nonce;
    public String os;
    public List<Mod> mods = new ArrayList<>();
    /** -javaagent / -agentpath / -agentlib arguments the JVM was started with. */
    public List<String> agents = new ArrayList<>();
    /** The JVM attach listener only runs after something attached to the process (agents, profilers, injectors). */
    public boolean attachListener;
    /** Java threads with no Java frames at all, typical for native threads attached through JNI. */
    public List<String> nativeThreads = new ArrayList<>();
    /** Classes running on game threads that were not loaded from any jar (defined in memory). */
    public List<String> inMemoryClasses = new ArrayList<>();
    /** Native libraries loaded from unusual places (temp folders, downloads, desktop). */
    public List<String> suspiciousNatives = new ArrayList<>();
    public int nativeLibraryCount;
    /** Attack/use/input calls that did not come from the vanilla code path. */
    public List<CallFinding> callOrigins = new ArrayList<>();
    /** Mods that change combat or input related classes, for staff review. */
    public Map<String, List<String>> sensitiveMixins = new LinkedHashMap<>();
    public double entityReach;
    public double blockReach;

    public static final class Mod {
        public String id;
        public String version;

        public Mod() {
        }

        public Mod(String id, String version) {
            this.id = id;
            this.version = version;
        }
    }

    public static final class CallFinding {
        /** The protected method, e.g. "attackEntity". */
        public String target;
        /** The method that called it. */
        public String caller;
        /** First non-JDK frame on the stack, i.e. the code that started the call. */
        public String origin;
        /** Mod that owns {@link #origin}, "in-memory" for injected code, or "unknown". */
        public String originMod;
        public String thread;
        public int count;
    }
}
