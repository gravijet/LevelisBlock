package net.gravijet.levelblock;

import java.util.Locale;

/** The two challenge formats this plugin implements. */
public enum Mode {

    /** Start on a 3x3 patch of columns; every level buys one more column. */
    LEVEL_BLOCK("Level = Block"),

    /** The classic format: every level widens the vanilla world border. */
    LEVEL_BORDER("Level = Border");

    private final String display;

    Mode(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public static Mode parse(String raw, Mode fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (value) {
            case "LEVEL_BLOCK", "BLOCK", "LEVELBLOCK" -> LEVEL_BLOCK;
            case "LEVEL_BORDER", "BORDER", "LEVELBORDER" -> LEVEL_BORDER;
            default -> fallback;
        };
    }
}
