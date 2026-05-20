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
package me.stringdotjar.docletmd;

import me.stringdotjar.docletmd.util.MdEscaper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdEscaperTest {

  @Test
  void escapeMdx_null_returnsNull() {
    assertNull(MdEscaper.escapeMdx(null));
  }

  @Test
  void escapeMdx_empty_returnsEmpty() {
    assertEquals("", MdEscaper.escapeMdx(""));
  }

  @Test
  void escapeMdx_escapesAngleBrackets() {
    assertEquals("Map&lt;String, Integer&gt;", MdEscaper.escapeMdx("Map<String, Integer>"));
  }

  @Test
  void escapeMdx_escapesCurlyBraces() {
    assertEquals("&#123;key&#125;", MdEscaper.escapeMdx("{key}"));
  }

  @Test
  void escapeMdx_escapesAmpersandFirst() {
    // A pre-existing &lt; should become &amp;lt; not &lt;lt;
    assertEquals("&amp;lt;", MdEscaper.escapeMdx("&lt;"));
  }

  @Test
  void escapeMdx_plainText_unchanged() {
    assertEquals("Hello, world!", MdEscaper.escapeMdx("Hello, world!"));
  }

  @Test
  void escapeTableCell_null_returnsNull() {
    assertNull(MdEscaper.escapeTableCell(null));
  }

  @Test
  void escapeTableCell_pipeEscaped() {
    assertEquals("a \\| b", MdEscaper.escapeTableCell("a | b"));
  }

  @Test
  void escapeTableCell_newlineCollapsed() {
    assertEquals("line1 line2", MdEscaper.escapeTableCell("line1\nline2"));
  }

  @Test
  void escapeTableCell_crlfCollapsed() {
    assertEquals("line1 line2", MdEscaper.escapeTableCell("line1\r\nline2"));
  }
}
