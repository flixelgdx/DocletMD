/*
 * Copyright (c) 2026 String
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
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
