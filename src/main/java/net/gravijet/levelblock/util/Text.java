package net.gravijet.levelblock.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Locale;

/** MiniMessage helpers plus the small placeholder syntax used across the config. */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component mm(String raw) {
        return MINI.deserialize(raw == null ? "" : raw);
    }

    /**
     * Deserializes {@code raw} after substituting {@code %key%} placeholders.
     * Replacements happen before parsing, so values must not contain MiniMessage tags
     * (all call sites pass numbers, times or player names).
     */
    public static Component mm(String raw, Object... placeholderPairs) {
        return MINI.deserialize(fill(raw, placeholderPairs));
    }

    public static String fill(String raw, Object... placeholderPairs) {
        if (raw == null) {
            return "";
        }
        String out = raw;
        for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
            out = out.replace("%" + placeholderPairs[i] + "%", String.valueOf(placeholderPairs[i + 1]));
        }
        return out;
    }

    /**
     * Colours {@code text} character by character along {@code palette}.
     *
     * @param offset shifts the ramp along the string; pass a value that grows over time to
     *               animate it. Non-zero offsets sample the palette cyclically so the loop
     *               has no visible seam, {@code 0} lays the palette out end to end.
     */
    public static Component gradient(String text, List<TextColor> palette, double offset, boolean bold) {
        if (text.isEmpty()) {
            return Component.empty();
        }
        if (palette.isEmpty()) {
            return Component.text(text);
        }
        if (palette.size() == 1) {
            return Component.text(text, palette.get(0)).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, bold);
        }
        boolean cyclic = offset != 0.0D;
        int last = text.length() - 1;
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i <= last; i++) {
            double position = (last == 0 ? 0.0D : (double) i / last) + offset;
            builder.append(Component.text(String.valueOf(text.charAt(i)), sample(palette, position, cyclic)));
        }
        return builder.build().decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, bold);
    }

    private static TextColor sample(List<TextColor> palette, double position, boolean cyclic) {
        int count = palette.size();
        if (cyclic) {
            double wrapped = position - Math.floor(position);
            double scaled = wrapped * count;
            int index = Math.min((int) scaled, count - 1);
            return TextColor.lerp((float) (scaled - index), palette.get(index), palette.get((index + 1) % count));
        }
        double clamped = Math.max(0.0D, Math.min(1.0D, position));
        double scaled = clamped * (count - 1);
        int index = Math.min((int) scaled, count - 2);
        return TextColor.lerp((float) (scaled - index), palette.get(index), palette.get(index + 1));
    }

    /** {@code 3742 -> "01:02:22"}. Always at least hours:minutes:seconds. */
    public static String formatTime(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }

    /**
     * Parses {@code 90}, {@code 1:30}, {@code 1:02:30}, {@code 10m}, {@code 2h30m}.
     *
     * @return seconds, or {@code -1} when the input is not a valid duration
     */
    public static long parseTime(String input) {
        if (input == null || input.isBlank()) {
            return -1L;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.contains(":")) {
                String[] parts = value.split(":");
                if (parts.length < 2 || parts.length > 3) {
                    return -1L;
                }
                long total = 0L;
                for (String part : parts) {
                    total = total * 60L + Long.parseLong(part.trim());
                }
                return total;
            }
            if (value.matches("\\d+")) {
                return Long.parseLong(value);
            }
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("(\\d+)([hms])").matcher(value);
            long total = 0L;
            int consumed = 0;
            while (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                total += switch (matcher.group(2)) {
                    case "h" -> amount * 3600L;
                    case "m" -> amount * 60L;
                    default -> amount;
                };
                consumed += matcher.group().length();
            }
            return consumed == value.length() && consumed > 0 ? total : -1L;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }
}
