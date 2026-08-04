package net.gravijet.levelblock;

import java.util.Locale;

/** How experience is distributed between the players of a run. */
public enum Sharing {

    /**
     * Everyone keeps their own experience and buys their own blocks. The levels of all
     * players still add up for the border in {@link Mode#LEVEL_BORDER}.
     */
    INDIVIDUAL("Eigene Erfahrung"),

    /** One pool mirrored onto everyone: every player always has exactly the same XP. */
    SHARED("Geteilte Erfahrung");

    private final String display;

    Sharing(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public static Sharing parse(String raw, Sharing fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "INDIVIDUAL", "EIGEN", "EIGENE", "OWN", "SOLO" -> INDIVIDUAL;
            case "SHARED", "GETEILT", "TEAM", "GLEICH" -> SHARED;
            default -> fallback;
        };
    }
}
