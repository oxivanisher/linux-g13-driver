package com.booker.g13;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the character each physical key produces on the system's keyboard layout, so the GUI
 * can label keys as printed on the user's keyboard (e.g. "Z" and "Ä" on a Swiss layout) instead
 * of their US names. The stored keycodes are not affected, this is only used for display.
 *
 * The keymap is read once via "xmodmap -pk" (first layout group) and the layout name via
 * "setxkbmap -query". If either tool is missing, the GUI falls back to the US key names.
 */
public final class SystemKeyboardLayout {

    /** Offset between X11 keycodes and Linux input event codes (X11 keycode = evdev code + 8). */
    private static final int X11_KEYCODE_OFFSET = 8;

    /** Keysyms of keys like ^ or ¨ that start an accent instead of typing a character. */
    private static final Map<Integer, String> DEAD_KEYSYMS = Map.ofEntries(
            Map.entry(0xfe50, "`"), Map.entry(0xfe51, "´"), Map.entry(0xfe52, "^"),
            Map.entry(0xfe53, "~"), Map.entry(0xfe54, "¯"), Map.entry(0xfe55, "˘"),
            Map.entry(0xfe56, "˙"), Map.entry(0xfe57, "¨"), Map.entry(0xfe58, "°"),
            Map.entry(0xfe59, "˝"), Map.entry(0xfe5a, "ˇ"), Map.entry(0xfe5b, "¸"),
            Map.entry(0xfe5c, "˛")
    );

    /** Matches an "xmodmap -pk" line: X11 keycode followed by the keysym of the first group/level. */
    private static final Pattern XMODMAP_LINE = Pattern.compile("^\\s*(\\d+)\\s+0x([0-9a-fA-F]+)");

    private static final SystemKeyboardLayout INSTANCE = load();

    private final Map<Integer, String> labels;
    private final String layoutName;

    private SystemKeyboardLayout(Map<Integer, String> labels, String layoutName) {
        this.labels = labels;
        this.layoutName = layoutName;
    }

    /**
     * Returns the label of a key on the system keyboard layout.
     * @param linuxCode The Linux keycode.
     * @return The character printed on the key, or empty if it is not a character key or the layout is unknown.
     */
    public static Optional<String> labelFor(int linuxCode) {
        return Optional.ofNullable(INSTANCE.labels.get(linuxCode));
    }

    /**
     * Returns a short description of the layout used for key labels, to be shown in the GUI.
     * @return E.g. "Keyboard layout: ch (system: ch,us)".
     */
    public static String description() {
        if (INSTANCE.labels.isEmpty()) {
            return "Keyboard layout: US (system layout could not be read, xmodmap missing?)";
        }
        if (INSTANCE.layoutName == null) {
            return "Keyboard layout: system default";
        }
        String first = INSTANCE.layoutName.split(",")[0];
        return first.equals(INSTANCE.layoutName)
                ? "Keyboard layout: " + first
                : "Keyboard layout: " + first + " (system: " + INSTANCE.layoutName + ")";
    }

    private static SystemKeyboardLayout load() {
        Map<Integer, String> labels = new HashMap<>();
        runCommand("xmodmap", "-pk").ifPresent(output -> output.lines().forEach(line -> {
            Matcher m = XMODMAP_LINE.matcher(line);
            if (m.find()) {
                int linuxCode = Integer.parseInt(m.group(1)) - X11_KEYCODE_OFFSET;
                String label = keysymToLabel(Integer.parseInt(m.group(2), 16));
                if (linuxCode > 0 && label != null) {
                    labels.put(linuxCode, label);
                }
            }
        }));

        String layoutName = runCommand("setxkbmap", "-query")
                .flatMap(output -> output.lines()
                        .filter(line -> line.startsWith("layout:"))
                        .map(line -> line.substring("layout:".length()).trim())
                        .findFirst())
                .orElse(null);

        return new SystemKeyboardLayout(Map.copyOf(labels), layoutName);
    }

    /**
     * Converts an X11 keysym to a printable label.
     * @return The label, or null for keys without a printable character (Shift, F1, numpad, ...).
     */
    private static String keysymToLabel(int keysym) {
        int codePoint;
        if ((keysym >= 0x21 && keysym <= 0x7e) || (keysym >= 0xa1 && keysym <= 0xff)) {
            codePoint = keysym; // Latin-1 keysyms are identical to Unicode
        } else if ((keysym & 0xff000000) == 0x01000000) {
            codePoint = keysym & 0x00ffffff; // Unicode keysym
        } else {
            return DEAD_KEYSYMS.get(keysym);
        }
        if (!Character.isValidCodePoint(codePoint) || Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
            return null;
        }
        return new String(Character.toChars(Character.toUpperCase(codePoint)));
    }

    private static Optional<String> runCommand(String... command) {
        try {
            Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroy();
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            System.err.println("SystemKeyboardLayout: Could not run " + command[0] + ": " + e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
