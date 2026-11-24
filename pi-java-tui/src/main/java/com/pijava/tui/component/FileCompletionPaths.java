package com.pijava.tui.component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Pure path/token helpers behind {@link FileCompleter} (pi {@code autocomplete.ts}).
 *
 * <p>Split out to keep both classes under the 500-line limit. Holds prefix
 * extraction (delimiters, wrappers, quotes, CJK punctuation separation),
 * completion-value quoting, entry scoring, display-path joining, home/absolute
 * resolution, and the bounded NIO directory walk that replaces {@code fd}.</p>
 */
final class FileCompletionPaths {

    static final String PATH_DELIMITERS = " \t\"'=";
    private static final String WRAPPERS_OPEN = "([{<`";
    private static final String WRAPPERS_CLOSE = ")]}>`";
    private static final String FULLWIDTH_PUNCT = "，．：；！？（）［］｛｝“”‘’…—";
    private static final int WALK_VISIT_CAP = 20_000;
    private static final int WALK_RESULT_CAP = 100;

    private final Path basePath;

    FileCompletionPaths(Path basePath) {
        this.basePath = basePath;
    }

    record Parsed(String rawPrefix, boolean isAtPrefix, boolean isQuotedPrefix) {
    }

    record RawEntry(String path, boolean directory) {
    }

    // ── Prefix extraction ──────────────────────────────────────────────

    /** pi {@code extractAtPrefix}. */
    static String extractAtPrefix(String text) {
        String quoted = extractQuotedPrefix(text);
        if (quoted != null && quoted.startsWith("@\"")) {
            return quoted;
        }
        int lastDelimiter = findLastDelimiter(text);
        String token = stripLeadingWrappers(
            lastDelimiter < 0 ? text : text.substring(lastDelimiter + 1));
        return token.startsWith("@") ? token : null;
    }

    /** pi {@code extractPathPrefix}. */
    static String extractPathPrefix(String text, boolean force) {
        String quoted = extractQuotedPrefix(text);
        if (quoted != null) {
            return quoted;
        }
        int lastDelimiter = findLastDelimiter(text);
        String pathPrefix = stripLeadingWrappers(
            lastDelimiter < 0 ? text : text.substring(lastDelimiter + 1));
        if (force) {
            return pathPrefix;
        }
        if (pathPrefix.contains("/") || pathPrefix.startsWith(".") || pathPrefix.startsWith("~/")) {
            return pathPrefix;
        }
        if (pathPrefix.isEmpty() && !text.isEmpty() && endsWithSeparator(text)) {
            return pathPrefix;
        }
        return null;
    }

    /** pi {@code buildCompletionValue}. */
    static String buildCompletionValue(String path, boolean atPrefix, boolean quoted) {
        boolean needsQuotes = quoted || containsSeparator(path);
        String prefix = atPrefix ? "@" : "";
        return needsQuotes ? prefix + "\"" + path + "\"" : prefix + path;
    }

    /** pi {@code scoreEntry}. */
    static int scoreEntry(String filePath, String query, boolean directory) {
        String name = basename(filePath).toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.equals(lowerQuery)) {
            score = 100;
        } else if (name.startsWith(lowerQuery)) {
            score = 80;
        } else if (name.contains(lowerQuery)) {
            score = 50;
        } else if (filePath.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            score = 30;
        }
        if (directory && score > 0) {
            score += 10;
        }
        return score;
    }

    static Parsed parsePathPrefix(String prefix) {
        if (prefix.startsWith("@\"")) {
            return new Parsed(prefix.substring(2), true, true);
        }
        if (prefix.startsWith("\"")) {
            return new Parsed(prefix.substring(1), false, true);
        }
        if (prefix.startsWith("@")) {
            return new Parsed(prefix.substring(1), true, false);
        }
        return new Parsed(prefix, false, false);
    }

    static boolean isSlashNameContext(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("/") && !trimmed.contains(" ");
    }

    private static int findLastDelimiter(String text) {
        int lastDelimiter = -1;
        int index = 0;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            if (isSeparator(cp)) {
                lastDelimiter = index;
            }
            index += Character.charCount(cp);
        }
        return lastDelimiter;
    }

    private static String stripLeadingWrappers(String token) {
        String result = token;
        while (!result.isEmpty()) {
            char first = result.charAt(0);
            int closerIndex = WRAPPERS_OPEN.indexOf(first);
            if (closerIndex < 0 || result.indexOf(WRAPPERS_CLOSE.charAt(closerIndex), 1) >= 0) {
                break;
            }
            result = result.substring(1);
        }
        return result;
    }

    private static String extractQuotedPrefix(String text) {
        int quoteStart = findUnclosedQuoteStart(text);
        if (quoteStart < 0) {
            return null;
        }
        if (quoteStart > 0 && text.charAt(quoteStart - 1) == '@') {
            return isTokenStart(text, quoteStart - 1) ? text.substring(quoteStart - 1) : null;
        }
        return isTokenStart(text, quoteStart) ? text.substring(quoteStart) : null;
    }

    private static int findUnclosedQuoteStart(String text) {
        boolean inQuotes = false;
        int quoteStart = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                inQuotes = !inQuotes;
                if (inQuotes) {
                    quoteStart = i;
                }
            }
        }
        return inQuotes ? quoteStart : -1;
    }

    private static boolean isTokenStart(String text, int index) {
        int start = index;
        while (start > 0 && WRAPPERS_OPEN.indexOf(text.charAt(start - 1)) >= 0) {
            start--;
        }
        if (start == 0) {
            return true;
        }
        char before = text.charAt(start - 1);
        return isSeparator(before) || PATH_DELIMITERS.indexOf(before) >= 0;
    }

    // ── Separators ─────────────────────────────────────────────────────

    static boolean isSeparator(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        if (FULLWIDTH_PUNCT.indexOf(cp) >= 0) {
            return true;
        }
        return isCjk(cp) && isPunctuation(cp);
    }

    private static boolean containsSeparator(String text) {
        return text.codePoints().anyMatch(FileCompletionPaths::isSeparator);
    }

    private static boolean endsWithSeparator(String text) {
        return !text.isEmpty() && isSeparator(text.codePointBefore(text.length()));
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
            || script == Character.UnicodeScript.BOPOMOFO;
    }

    private static boolean isPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION;
    }

    // ── Display paths ──────────────────────────────────────────────────

    static String joinDisplay(String displayPrefix, String name) {
        if (displayPrefix.isEmpty()) {
            return name;
        }
        if (displayPrefix.endsWith("/")) {
            return displayPrefix + name;
        }
        if (displayPrefix.startsWith("~/")) {
            String rest = displayPrefix.substring(2);
            String dir = dirname(rest);
            return "~/" + (dir.isEmpty() || dir.equals(".") ? name : dir + "/" + name);
        }
        if (displayPrefix.startsWith("/")) {
            String dir = dirname(displayPrefix);
            return dir.equals("/") ? "/" + name : dir + "/" + name;
        }
        if (displayPrefix.contains("/")) {
            String dir = dirname(displayPrefix);
            return dir.equals(".") ? displayPrefix + "/" + name : dir + "/" + name;
        }
        return name;
    }

    static String combine(String displayBase, String relative) {
        if (displayBase.isEmpty()) {
            return relative;
        }
        return displayBase.endsWith("/") ? displayBase + relative : displayBase + "/" + relative;
    }

    static String dirname(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) {
            return ".";
        }
        return slash == 0 ? "/" : normalized.substring(0, slash);
    }

    static String basename(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    static int depth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    // ── Filesystem ─────────────────────────────────────────────────────

    /** Resolve a display path (with {@code ~} / absolute support) against the base. */
    Path resolve(String display) {
        if (display == null || display.isEmpty() || display.equals("./")) {
            return basePath;
        }
        if (display.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(display.substring(2));
        }
        if (display.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (display.startsWith("/")) {
            return Path.of(display);
        }
        return basePath.resolve(display);
    }

    /** Bounded recursive walk (fd replacement): hidden files, follow links, skip .git. */
    List<RawEntry> walk(Path base) {
        List<RawEntry> entries = new ArrayList<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(base, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        Path name = dir.getFileName();
                        if (name != null && ".git".equals(name.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (++visited[0] > WALK_VISIT_CAP || entries.size() >= WALK_RESULT_CAP * 5) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (!dir.equals(base)) {
                            entries.add(new RawEntry(displayRelative(base, dir), true));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (++visited[0] > WALK_VISIT_CAP || entries.size() >= WALK_RESULT_CAP * 5) {
                            return FileVisitResult.TERMINATE;
                        }
                        entries.add(new RawEntry(displayRelative(base, file), false));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            return entries;
        }
        return entries;
    }

    private static String displayRelative(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }
}
