package com.example.combatguard.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON report sent by the optional CombatGuard Fabric client mod (same format as the mod). */
public final class ClientReport {
    public int version = 1;
    public String nonce;
    public String os;
    public List<Mod> mods = new ArrayList<>();
    public List<String> agents = new ArrayList<>();
    public boolean attachListener;
    public List<String> nativeThreads = new ArrayList<>();
    public List<String> inMemoryClasses = new ArrayList<>();
    public List<String> suspiciousNatives = new ArrayList<>();
    public int nativeLibraryCount;
    public List<CallFinding> callOrigins = new ArrayList<>();
    public Map<String, List<String>> sensitiveMixins = new LinkedHashMap<>();
    public double entityReach;
    public double blockReach;

    public static final class Mod {
        public String id;
        public String version;
    }

    public static final class CallFinding {
        public String target;
        public String caller;
        public String origin;
        public String originMod;
        public String thread;
        public int count;
    }
}
