package com.termux.localgames.activity;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of a launch log: its absolute (1-based) line number in the file, the text and the log
 * level detected from that text.
 *
 * <p>The launch log mixes the host shell trace ({@code + command}), Wine channel output
 * ({@code 0024:err:module:...}), Android logcat style lines and free text, so the level is derived
 * from the text by {@link #classify(String)}. Lines without any level marker are {@link Level#INFO}.</p>
 */
final class GameLogLine {

    /** Highlight classes, most to least severe. */
    enum Level {
        ERROR('E'), WARN('W'), INFO('I'), DEBUG('D'), TRACE('T');

        /** One-letter badge shown next to the line number (so the level is not color-only). */
        final char badge;

        Level(char badge) {
            this.badge = badge;
        }
    }

    /** {@code + cmd}, {@code ++ cmd}: the shell's own execution trace, never a real error. */
    private static final Pattern SHELL_TRACE = Pattern.compile("^\\+{1,3}( .*)?$");
    /** {@code err:}, {@code warn:}, {@code fixme:}, {@code trace:} with an optional Wine thread id. */
    private static final Pattern WINE_CHANNEL =
        Pattern.compile("^(?:[0-9a-fA-F]{4}:)?(err|warn|fixme|trace):");
    /** {@code E/Tag: ...} logcat brief format. */
    private static final Pattern LOGCAT_BRIEF = Pattern.compile("^([VDIWEF])/\\S+\\s*\\(?\\s*\\d*\\)?:");
    /** {@code 10-21 16:23:22.304   601   631 E Tag: ...} logcat threadtime format. */
    private static final Pattern LOGCAT_THREADTIME = Pattern.compile(
        "^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s");
    /** {@code [ERROR] ...}, {@code WARN: ...}, {@code INFO ...} at the start of a line. */
    private static final Pattern LEADING_LEVEL = Pattern.compile(
        "^\\W{0,3}(FATAL|SEVERE|ERROR|WARNING|WARN|INFO|DEBUG|TRACE|VERBOSE)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_WORD = Pattern.compile(
        "\\b(error|exception|fatal|failed|failure|segfault|segmentation fault|abort(?:ed)?|panic)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern WARN_WORD = Pattern.compile(
        "\\b(warning|warn|deprecated)\\b", Pattern.CASE_INSENSITIVE);

    final int number;
    @NonNull final String text;
    @NonNull final Level level;

    GameLogLine(int number, @NonNull String text) {
        this.number = number;
        this.text = text;
        this.level = classify(text);
    }

    /** Detects the level of one log line; deterministic, first matching rule wins. */
    @NonNull
    static Level classify(@NonNull String text) {
        if (SHELL_TRACE.matcher(text).matches()) return Level.TRACE;

        Matcher wine = WINE_CHANNEL.matcher(text);
        if (wine.find()) {
            switch (wine.group(1)) {
                case "err": return Level.ERROR;
                case "warn":
                case "fixme": return Level.WARN;
                default: return Level.TRACE;
            }
        }

        Matcher logcat = LOGCAT_BRIEF.matcher(text);
        if (logcat.find()) return fromLogcatLetter(logcat.group(1).charAt(0));
        logcat = LOGCAT_THREADTIME.matcher(text);
        if (logcat.find()) return fromLogcatLetter(logcat.group(1).charAt(0));

        Matcher leading = LEADING_LEVEL.matcher(text);
        if (leading.find()) {
            switch (leading.group(1).toUpperCase(Locale.ROOT)) {
                case "FATAL":
                case "SEVERE":
                case "ERROR": return Level.ERROR;
                case "WARNING":
                case "WARN": return Level.WARN;
                case "INFO": return Level.INFO;
                case "DEBUG": return Level.DEBUG;
                default: return Level.TRACE;
            }
        }

        if (ERROR_WORD.matcher(text).find()) return Level.ERROR;
        if (WARN_WORD.matcher(text).find()) return Level.WARN;
        return Level.INFO;
    }

    @NonNull
    private static Level fromLogcatLetter(char letter) {
        switch (letter) {
            case 'F':
            case 'E': return Level.ERROR;
            case 'W': return Level.WARN;
            case 'I': return Level.INFO;
            case 'D': return Level.DEBUG;
            default: return Level.TRACE;
        }
    }
}
