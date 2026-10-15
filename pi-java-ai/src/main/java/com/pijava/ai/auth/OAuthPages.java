package com.pijava.ai.auth;

/**
 * OAuth 回调浏览器页面（pi {@code utils/oauth-page.ts} 逐字移植）——
 * 共享回调服务器在成功/失败时发给浏览器的深色品牌页。
 *
 * <p>不可变、纯字符串；唯一处理是插值前五字符 HTML 转义。</p>
 */
final class OAuthPages {

    private static final String LOGO_SVG = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 800" aria-hidden="true"><path fill="#F09082" d="M165.29 165.29H517.36V400H400V282.65H165.29Z"/><path fill="#4D9ABF" d="M165.29 282.65H282.65V400H400V517.36H282.65V634.72H165.29Z"/><path fill="#F1BE58" d="M517.36 400H634.72V634.72H517.36Z"/></svg>""";

    private OAuthPages() {}

    /** 成功页：标题/heading 固定「Authentication successful」，正文 {@code message}。 */
    static String success(String message) {
        return renderPage("Authentication successful", "Authentication successful", message, null);
    }

    /** 失败页（无 details 行）。 */
    static String error(String message) {
        return error(message, null);
    }

    /** 失败页：标题/heading 固定「Authentication failed」；{@code details} 可为 null。 */
    static String error(String message, String details) {
        return renderPage("Authentication failed", "Authentication failed", message, details);
    }

    private static String renderPage(String title, String heading, String message, String details) {
        // 用占位符替换而非 String.format：CSS 里有裸 `%`（width: 100%），会撞 Formatter。
        var safeDetails = details == null ? ""
            : "<div class=\"details\">" + escapeHtml(details) + "</div>";
        return TEMPLATE
            .replace("__TITLE__", escapeHtml(title))
            .replace("__LOGO__", LOGO_SVG)
            .replace("__HEADING__", escapeHtml(heading))
            .replace("__MESSAGE__", escapeHtml(message))
            .replace("__DETAILS__", safeDetails);
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>__TITLE__</title>
              <style>
                :root {
                  --text: #fafafa;
                  --text-dim: #a1a1aa;
                  --page-bg: #09090b;
                  --font-sans: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";
                  --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
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
                  width: 100%;
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
                <div class="logo">__LOGO__</div>
                <h1>__HEADING__</h1>
                <p>__MESSAGE__</p>
                __DETAILS__
              </main>
            </body>
            </html>""";

    /** pi {@code escapeHtml}：{@code & < > " '} 五字符。 */
    static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
