package com.pijava.ai.utils;

import org.jspecify.annotations.Nullable;

/**
 * The page a browser shows after an OAuth redirect
 * (pi {@code packages/ai/src/utils/oauth-page.ts}).
 *
 * <p>Callers that run their own loopback callback server render it; the MCP sign-in is one.</p>
 */
public final class OAuthPage {

    private static final String LOGO_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\""
            + " viewBox=\"0 0 800 800\" aria-hidden=\"true\">"
            + "<path fill=\"#F09082\" d=\"M165.29 165.29H517.36V400H400V282.65H165.29Z\"/>"
            + "<path fill=\"#4D9ABF\" d=\"M165.29 282.65H282.65V400H400V517.36H282.65V634.72H165.29Z\"/>"
            + "<path fill=\"#F1BE58\" d=\"M517.36 400H634.72V634.72H517.36Z\"/>"
            + "</svg>";

    private OAuthPage() {
    }

    /**
     * The success page.
     *
     * @param message what the user should read
     * @return the HTML document
     */
    public static String success(String message) {
        return render("Authentication successful", "Authentication successful", message, null);
    }

    /**
     * The failure page.
     *
     * @param message the headline
     * @param details the server's error description, when it sent one
     * @return the HTML document
     */
    public static String error(String message, @Nullable String details) {
        return render("Authentication failed", "Authentication failed", message, details);
    }

    private static String render(String title, String heading, String message, @Nullable String details) {
        return TEMPLATE.formatted(escape(title), LOGO_SVG, escape(heading), escape(message),
                details == null ? "" : "<div class=\"details\">" + escape(details) + "</div>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Percent signs are literal CSS, so each one is doubled for {@link String#format}. */
    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>%s</title>
              <style>
                :root {
                  --text: #fafafa;
                  --text-dim: #a1a1aa;
                  --page-bg: #09090b;
                  --font-sans: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI",
                    Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif, "Apple Color Emoji",
                    "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";
                  --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas,
                    "Liberation Mono", "Courier New", monospace;
                }
                * { box-sizing: border-box; }
                html { color-scheme: dark; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  padding: 24px;
                  background: var(--page-bg);
                  color: var(--text);
                  font-family: var(--font-sans);
                  text-align: center;
                }
                main {
                  width: 100%%;
                  max-width: 560px;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                }
                .logo {
                  width: 72px;
                  height: 72px;
                  display: block;
                  margin-bottom: 24px;
                }
                h1 {
                  margin: 0 0 10px;
                  font-size: 28px;
                  line-height: 1.15;
                  font-weight: 650;
                  color: var(--text);
                }
                p {
                  margin: 0;
                  line-height: 1.7;
                  color: var(--text-dim);
                  font-size: 15px;
                }
                .details {
                  margin-top: 16px;
                  font-family: var(--font-mono);
                  font-size: 13px;
                  color: var(--text-dim);
                  white-space: pre-wrap;
                  word-break: break-word;
                }
              </style>
            </head>
            <body>
              <main>
                <div class="logo">%s</div>
                <h1>%s</h1>
                <p>%s</p>
                %s
              </main>
            </body>
            </html>""";
}
