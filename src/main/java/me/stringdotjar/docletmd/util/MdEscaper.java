package me.stringdotjar.docletmd.util;

/**
 * Escapes characters that would break MDX rendering in Docusaurus.
 *
 * <p>MDX treats bare {@code <}, {@code >}, and curly braces as JSX syntax. Any
 * of those characters appearing in plain paragraph text will cause a Docusaurus
 * build error. This class provides helpers that escape the right characters in
 * the right contexts.
 *
 * <p>Example usage:
 * <pre>{@code
 * String safe = MdEscaper.escapeMdx("Use Map<String, Integer> for this.");
 * // safe => "Use Map&lt;String, Integer&gt; for this."
 * }</pre>
 */
public final class MdEscaper {

  private MdEscaper() {}

  /**
   * Escapes angle brackets and curly braces for safe use in MDX paragraph text.
   *
   * <p>The {@code &} character is escaped first so that already-escaped entities
   * (like {@code &lt;}) are not double-escaped.
   *
   * @param text the raw text to escape, or {@code null}
   * @return the MDX-safe version of the text, or {@code null} if the input was {@code null}
   */
  public static String escapeMdx(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("{", "&#123;")
        .replace("}", "&#125;");
  }

  /**
   * Escapes pipe characters and strips line breaks for safe use inside a Markdown table cell.
   *
   * <p>Pipe characters ({@code |}) delimit table columns in Markdown; a raw pipe inside
   * cell text would break the table structure. Line breaks collapse to a single space
   * because multiline table cells are not universally supported.
   *
   * @param text the raw cell content, or {@code null}
   * @return the escaped cell content, or {@code null} if the input was {@code null}
   */
  public static String escapeTableCell(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return text.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").replace("\r", "");
  }
}
