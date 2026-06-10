package org.flixelgdx;

import org.flixelgdx.util.MdEscaper;
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
