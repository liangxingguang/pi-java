package com.pijava.coding.agent.subcommand;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Opens a URL in the platform browser (pi {@code utils/open-browser.ts}).
 *
 * <p>pi-java had no equivalent until the MCP sign-in needed one. A failure is reported and
 * swallowed: the URL is printed anyway, so the user can open it by hand, and pi's own
 * {@code openBrowser} does not fail the sign-in either.</p>
 */
public final class BrowserLauncher {

    private BrowserLauncher() {
    }

    /**
     * Open {@code url}, or report that it could not be opened.
     *
     * @param url the URL to open
     * @param note where to report a failure; the URL is shown regardless
     */
    public static void open(String url, Consumer<String> note) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall through to the platform command, as a headless JVM has no Desktop.
        }
        if (!runPlatformCommand(url)) {
            note.accept("Could not open a browser; visit the URL above.");
        }
    }

    /**
     * The command that opens {@code url} on a platform, for tests and for hosts that want to
     * launch it themselves.
     *
     * @param osName the value of {@code os.name}
     * @param url the URL to open
     * @return the command line
     */
    static List<String> commandFor(String osName, String url) {
        var os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (os.contains("mac")) {
            return List.of("open", url);
        }
        return List.of("xdg-open", url);
    }

    private static boolean runPlatformCommand(String url) {
        try {
            var process = new ProcessBuilder(commandFor(System.getProperty("os.name", ""), url))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Do not wait: the launcher hands the URL over and exits, or keeps running for
            // some browsers. Either way the sign-in continues.
            process.onExit().thenAccept(ignored -> {
            });
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }
}
