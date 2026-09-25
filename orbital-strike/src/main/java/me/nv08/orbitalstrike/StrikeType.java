package me.nv08.orbitalstrike;

import java.util.Locale;

public enum StrikeType {
    NUKE("nuke", "Nuke"),
    STAB("stab", "Stab");

    private final String id;
    private final String displayName;

    StrikeType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static StrikeType fromId(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (StrikeType type : values()) {
            if (type.id.equals(lower)) {
                return type;
            }
        }
        return null;
    }
}
