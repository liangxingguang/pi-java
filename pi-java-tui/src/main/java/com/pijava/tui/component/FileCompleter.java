package com.pijava.tui.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * {@code @file} / path completion (pi {@code tui/src/autocomplete.ts}, K25).
 *
 * <p>Ported behaviour: token extraction with path delimiters, opening wrappers
 * and CJK punctuation separators; {@code @"quoted"} prefixes; directory listing
 * with case-insensitive prefix and directory-first ordering; fuzzy scoring
 * (exact name 100 / name prefix 80 / name substring 50 / path substring 30,
 * +10 for directories); and completion-value quoting. The external {@code fd}
 * walk is replaced by a bounded NIO walk (narrow diff N). Shared pure helpers
 * live in {@link FileCompletionPaths}.</p>
 */
public final class FileCompleter {

    /** One completion candidate. */
    public record Suggestion(String value, String label, String description) {
        /** The popup row text (label plus optional description), clipped to 64 columns. */
        public String display() {
            String line = label;
            if (description != null && !description.isEmpty() && !description.equals(label)) {
                line += "  — " + description;
            }
            return line.length() <= 64 ? line : line.substring(0, 64);
        }
    }

    /** Result of feeding a key to the completer. */
    public enum KeyAction { IGNORED, HANDLED, COMPLETE }

    private static final int MAX_VISIBLE_DEFAULT = 5;
    private static final int TOP_RESULTS = 20;

    private final FileCompletionPaths paths;
    private final IntSupplier maxVisible;
    private final Map<String, List<Suggestion>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Suggestion>> eldest) {
            return size() > 64;
        }
    };

    private List<Suggestion> matches = List.of();
    private int selected;
    private boolean active;
    private String token = "";
    private boolean selectedDirectory;

    /**
     * @param basePath   working directory for relative paths
     * @param maxVisible live row count (pi {@code autocompleteMaxVisible})
     */
    public FileCompleter(Path basePath, IntSupplier maxVisible) {
        this.paths = new FileCompletionPaths(basePath == null ? Path.of(".") : basePath);
        this.maxVisible = maxVisible == null ? () -> MAX_VISIBLE_DEFAULT : maxVisible;
    }

    /**
     * Refresh suggestions from the text before the cursor.
     *
     * @param textBeforeCursor current line {@code [0, cursor)}
     * @param force            Tab forced completion (path branch always considered)
     * @return whether the popup should be shown
     */
    public boolean update(String textBeforeCursor, boolean force) {
        active = false;
        matches = List.of();
        selected = 0;
        token = "";
        selectedDirectory = false;
        if (textBeforeCursor == null || textBeforeCursor.isEmpty()) {
            return false;
        }
        if (FileCompletionPaths.isSlashNameContext(textBeforeCursor)) {
            return false;
        }
        String atPrefix = FileCompletionPaths.extractAtPrefix(textBeforeCursor);
        if (atPrefix != null) {
            FileCompletionPaths.Parsed parsed = FileCompletionPaths.parsePathPrefix(atPrefix);
            List<Suggestion> list = query(parsed.rawPrefix(), parsed, true);
            if (list.isEmpty()) {
                return false;
            }
            token = atPrefix;
            matches = list;
            active = true;
            selectedDirectory = list.get(0).label().endsWith("/");
            return true;
        }
        String pathPrefix = FileCompletionPaths.extractPathPrefix(textBeforeCursor, force);
        if (pathPrefix == null) {
            return false;
        }
        FileCompletionPaths.Parsed parsed = FileCompletionPaths.parsePathPrefix(pathPrefix);
        List<Suggestion> list = query(parsed.rawPrefix(), parsed, false);
        if (list.isEmpty()) {
            return false;
        }
        token = pathPrefix;
        matches = list;
        active = true;
        selectedDirectory = list.get(0).label().endsWith("/");
        return true;
    }

    /** Whether the popup is currently visible. */
    public boolean active() {
        return active;
    }

    /** Suggestions in display order. */
    public List<Suggestion> matches() {
        return List.copyOf(matches);
    }

    /** Value of the highlighted suggestion, or null. */
    public String selectedValue() {
        return matches.isEmpty() ? null : matches.get(selected).value();
    }

    /** Whether the highlighted suggestion is a directory (no trailing space). */
    public boolean selectedDirectory() {
        return selectedDirectory;
    }

    /** Grapheme length of the token to replace in the editor. */
    public int replaceGraphemes() {
        return EditorWordNav.graphemeCount(token);
    }

    /** Handle a key while the popup is active. */
    public KeyAction onKeyEvent(KeyEvent event) {
        if (!active) {
            return KeyAction.IGNORED;
        }
        int visible = Math.max(1, maxVisible.getAsInt());
        if (event.isPageUp()) {
            selected = Math.max(0, selected - visible);
            syncDirectory();
            return KeyAction.HANDLED;
        }
        if (event.isPageDown()) {
            selected = Math.min(matches.size() - 1, selected + visible);
            syncDirectory();
            return KeyAction.HANDLED;
        }
        if (event.isUp()) {
            selected = Math.max(0, selected - 1);
            syncDirectory();
            return KeyAction.HANDLED;
        }
        if (event.isDown()) {
            selected = Math.min(matches.size() - 1, selected + 1);
            syncDirectory();
            return KeyAction.HANDLED;
        }
        boolean tab = event.isKey(KeyCode.TAB) || event.isChar('\t');
        if ((tab && !event.hasShift()) || TamboUIAdapter.isSendEnter(event)) {
            syncDirectory();
            return KeyAction.COMPLETE;
        }
        if (event.isCancel()) {
            active = false;
            matches = List.of();
            return KeyAction.HANDLED;
        }
        return KeyAction.IGNORED;
    }

    /** The popup element, or null when inactive. */
    public Element render() {
        if (!active) {
            return null;
        }
        int visible = Math.max(1, maxVisible.getAsInt());
        int start = matches.size() <= visible
            ? 0
            : Math.max(0, Math.min(selected - visible / 2, matches.size() - visible));
        int shown = Math.min(matches.size(), visible);
        var rows = new ArrayList<Element>();
        for (int i = start; i < start + shown; i++) {
            String line = matches.get(i).display();
            rows.add(i == selected
                ? TamboUIAdapter.markupText("[cyan]▸ " + line + "[/]")
                : TamboUIAdapter.markupText("  " + line));
        }
        if (matches.size() > shown) {
            rows.add(TamboUIAdapter.markupText("[dim]  … " + (matches.size() - shown) + " more[/]"));
        }
        return TamboUIAdapter.column(rows);
    }

    /** Number of popup rows (drives the inline bottom-region height). */
    public int lineCount() {
        if (!active) {
            return 0;
        }
        int shown = Math.min(matches.size(), Math.max(1, maxVisible.getAsInt()));
        return matches.size() > shown ? shown + 1 : shown;
    }

    // ── Query pipeline ─────────────────────────────────────────────────

    private List<Suggestion> query(String raw, FileCompletionPaths.Parsed parsed, boolean atPrefix) {
        String cacheKey = (atPrefix ? "@" : "") + parsed.rawPrefix();
        List<Suggestion> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<Suggestion> result;
        if (raw.contains("/")) {
            result = scopedFuzzy(raw, parsed, atPrefix);
        } else if (atPrefix) {
            result = fuzzy(raw, null, parsed, true);
        } else {
            result = listDirectory(raw, parsed);
        }
        cache.put(cacheKey, result);
        return result;
    }

    /** Directory-listing branch (pi getFileSuggestions). */
    private List<Suggestion> listDirectory(String raw, FileCompletionPaths.Parsed parsed) {
        String searchPrefix;
        Path searchDir;
        boolean rootish = raw.isEmpty() || raw.equals("./") || raw.equals("../")
            || raw.equals("~") || raw.equals("~/") || raw.equals("/");
        if (rootish || raw.endsWith("/")) {
            searchDir = paths.resolve(raw);
            searchPrefix = "";
        } else {
            searchDir = paths.resolve(FileCompletionPaths.dirname(raw));
            searchPrefix = FileCompletionPaths.basename(raw);
        }
        if (searchDir == null || !Files.isDirectory(searchDir)) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(searchDir)) {
            for (Path entry : stream.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).startsWith(searchPrefix.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                boolean directory = Files.isDirectory(entry);
                String relative = FileCompletionPaths.joinDisplay(raw, name);
                String pathValue = directory ? relative + "/" : relative;
                out.add(new Suggestion(
                    FileCompletionPaths.buildCompletionValue(
                        pathValue, parsed.isAtPrefix(), parsed.isQuotedPrefix()),
                    name + (directory ? "/" : ""), relative));
            }
        } catch (IOException e) {
            return List.of();
        }
        out.sort((a, b) -> {
            boolean aDir = a.label().endsWith("/");
            boolean bDir = b.label().endsWith("/");
            if (aDir && !bDir) {
                return -1;
            }
            if (!aDir && bDir) {
                return 1;
            }
            return a.label().compareToIgnoreCase(b.label());
        });
        return out;
    }

    /** Fuzzy branch (pi getFuzzyFileSuggestions). */
    private List<Suggestion> fuzzy(String query, String displayBase,
            FileCompletionPaths.Parsed parsed, boolean atPrefix) {
        Path base = paths.resolve(displayBase == null ? "" : displayBase);
        if (base == null || !Files.isDirectory(base)) {
            return List.of();
        }
        List<FileCompletionPaths.RawEntry> entries = paths.walk(base);
        List<ScoredEntry> scored = new ArrayList<>();
        for (FileCompletionPaths.RawEntry entry : entries) {
            int score = query.isEmpty()
                ? 1 : FileCompletionPaths.scoreEntry(entry.path(), query, entry.directory());
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }
        scored.sort((a, b) -> {
            int byScore = Integer.compare(b.score(), a.score());
            if (byScore != 0) {
                return byScore;
            }
            int byDepth = Integer.compare(
                FileCompletionPaths.depth(a.entry().path()),
                FileCompletionPaths.depth(b.entry().path()));
            if (byDepth != 0) {
                return byDepth;
            }
            int byLength = Integer.compare(a.entry().path().length(), b.entry().path().length());
            if (byLength != 0) {
                return byLength;
            }
            return a.entry().path().compareToIgnoreCase(b.entry().path());
        });
        int limit = Math.min(TOP_RESULTS, scored.size());
        List<Suggestion> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            FileCompletionPaths.RawEntry entry = scored.get(i).entry();
            String displayPath = displayBase == null
                ? entry.path() : FileCompletionPaths.combine(displayBase, entry.path());
            String completionPath = entry.directory() ? displayPath + "/" : displayPath;
            out.add(new Suggestion(
                FileCompletionPaths.buildCompletionValue(completionPath, atPrefix, parsed.isQuotedPrefix()),
                FileCompletionPaths.basename(displayPath) + (entry.directory() ? "/" : ""),
                displayPath));
        }
        return out;
    }

    /** Scoped fuzzy: split {@code dir/query} and walk that directory (pi :558-586). */
    private List<Suggestion> scopedFuzzy(String raw, FileCompletionPaths.Parsed parsed,
            boolean atPrefix) {
        int slash = raw.lastIndexOf('/');
        String displayBase = raw.substring(0, slash + 1);
        String query = raw.substring(slash + 1);
        return fuzzy(query, displayBase, parsed, atPrefix);
    }

    private void syncDirectory() {
        selectedDirectory = !matches.isEmpty() && matches.get(selected).label().endsWith("/");
    }

    // ── Static delegates (test surface, mirrors pi pure helpers) ───────

    static String extractAtPrefix(String text) {
        return FileCompletionPaths.extractAtPrefix(text);
    }

    static String extractPathPrefix(String text, boolean force) {
        return FileCompletionPaths.extractPathPrefix(text, force);
    }

    static String buildCompletionValue(String path, boolean atPrefix, boolean quoted) {
        return FileCompletionPaths.buildCompletionValue(path, atPrefix, quoted);
    }

    static int scoreEntry(String filePath, String query, boolean directory) {
        return FileCompletionPaths.scoreEntry(filePath, query, directory);
    }

    private record ScoredEntry(FileCompletionPaths.RawEntry entry, int score) {
    }
}
