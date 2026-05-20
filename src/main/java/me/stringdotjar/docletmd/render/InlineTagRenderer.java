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
package me.stringdotjar.docletmd.render;

import com.sun.source.doctree.AttributeTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.TextTree;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import me.stringdotjar.docletmd.util.MdEscaper;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts a list of inline {@link DocTree} nodes to a Markdown string.
 *
 * <p>This class handles the common inline doc-tree node types produced by the Javadoc
 * parser: plain text, {@code {@code}}, {@code {@literal}}, {@code {@link}},
 * {@code {@linkplain}}, HTML start/end tags, and HTML entities.
 *
 * <p>For HTML elements that are not explicitly recognized, the tag content is
 * converted to Markdown using {@link FlexmarkHtmlConverter} so that Javadoc
 * descriptions containing tables, definition lists, or other complex HTML still
 * produce readable output.
 *
 * <p>Example usage:
 * <pre>{@code
 * InlineTagRenderer r = new InlineTagRenderer();
 * String markdown = r.render(docCommentTree.getFirstSentence());
 * }</pre>
 */
public final class InlineTagRenderer {

  /** Creates a new renderer with default settings. */
  public InlineTagRenderer() {}

  private final FlexmarkHtmlConverter htmlConverter = FlexmarkHtmlConverter.builder().build();

  /**
   * Renders a list of inline doc-tree nodes to a Markdown string.
   *
   * @param nodes the inline nodes to render; must not be {@code null}
   * @return the rendered Markdown, never {@code null}
   */
  public String render(List<? extends DocTree> nodes) {
    StringBuilder sb = new StringBuilder();
    for (DocTree node : nodes) {
      sb.append(renderNode(node));
    }
    return sb.toString();
  }

  /**
   * Renders a single doc-tree node to a Markdown fragment.
   *
   * @param node the node to render; must not be {@code null}
   * @return the Markdown fragment, never {@code null}
   */
  public String renderNode(DocTree node) {
    return switch (node.getKind()) {
      case TEXT -> MdEscaper.escapeMdx(((TextTree) node).getBody());
      case CODE, LITERAL -> "`" + ((LiteralTree) node).getBody().getBody() + "`";
      case LINK, LINK_PLAIN -> renderLink((LinkTree) node);
      case REFERENCE -> renderReference((ReferenceTree) node);
      case START_ELEMENT -> renderHtmlStart((StartElementTree) node);
      case END_ELEMENT -> renderHtmlEnd((EndElementTree) node);
      case ENTITY -> "&" + ((EntityTree) node).getName() + ";";
      default -> "";
    };
  }

  // Renders a @see or @throws reference (e.g. "java.lang.Math" or "Foo#bar()") as a code span.
  private String renderReference(ReferenceTree ref) {
    String sig = ref.getSignature();
    // "ClassName#method()" -> "ClassName.method()"
    return "`" + sig.replace('#', '.') + "`";
  }

  // Renders a {@link} or {@linkplain} tag as an inline code span containing the reference.
  // Full hyperlinks to other generated pages are not yet supported; the reference text
  // is always shown as a code span to keep output readable even without cross-linking.
  private String renderLink(LinkTree link) {
    List<? extends DocTree> label = link.getLabel();
    String text;
    if (label.isEmpty()) {
      String sig = link.getReference().getSignature();
      // "#method()" -> "method()"  |  "Class#method()" -> "Class.method()"
      text = sig.startsWith("#") ? sig.substring(1) : sig.replace('#', '.');
    } else {
      text = render(label);
    }
    return "`" + text + "`";
  }

  // Maps known HTML start tags to their Markdown equivalents.
  // Unrecognized tags are converted via flexmark's HTML-to-Markdown converter
  // so that complex HTML (tables, definition lists, etc.) still produces output.
  private String renderHtmlStart(StartElementTree tag) {
    String name = tag.getName().toString().toLowerCase();
    return switch (name) {
      case "p" -> "\n\n";
      case "br" -> "\n";
      case "pre" -> "\n```\n";
      case "code" -> "`";
      case "b", "strong" -> "**";
      case "i", "em" -> "*";
      case "li" -> "\n- ";
      case "ul", "ol", "thead", "tbody", "tr" -> "\n";
      case "td", "th" -> "| ";
      case "a" -> buildHtmlAnchor(tag);
      default -> convertUnknownTag(tag);
    };
  }

  // Maps known HTML end tags to their Markdown equivalents.
  private String renderHtmlEnd(EndElementTree tag) {
    String name = tag.getName().toString().toLowerCase();
    return switch (name) {
      case "p" -> "\n\n";
      case "pre" -> "\n```\n";
      case "code" -> "`";
      case "b", "strong" -> "**";
      case "i", "em" -> "*";
      case "td", "th" -> " ";
      case "tr" -> "|\n";
      case "a" -> "]";
      default -> "";
    };
  }

  // Builds the opening bracket for an <a href="..."> element.
  // The closing "]" comes from renderHtmlEnd for the </a> tag.
  // Full Markdown links ([text](url)) require stateful tracking of the URL
  // across sibling nodes, which the current stateless design does not support.
  // Stripping the href produces "[text]" which is readable without hyperlinking.
  private String buildHtmlAnchor(StartElementTree tag) {
    return "[";
  }

  // Converts an unrecognized HTML start tag to Markdown using flexmark.
  // getAttributes() returns List<? extends DocTree>; only AttributeTree nodes carry
  // attribute data, so other node kinds (e.g., ErroneousTree) are skipped.
  private String convertUnknownTag(StartElementTree tag) {
    StringBuilder html = new StringBuilder("<").append(tag.getName());
    for (DocTree child : tag.getAttributes()) {
      if (!(child instanceof AttributeTree attr)) {
        continue;
      }
      html.append(' ').append(attr.getName());
      if (attr.getValueKind() != AttributeTree.ValueKind.EMPTY) {
        // getValue() returns List<? extends DocTree>; extract text content only.
        String val = attr.getValue().stream()
            .filter(t -> t instanceof TextTree)
            .map(t -> ((TextTree) t).getBody())
            .collect(Collectors.joining());
        html.append("=\"").append(val).append('"');
      }
    }
    if (tag.isSelfClosing()) {
      html.append("/>");
      return htmlConverter.convert(html.toString()).strip();
    }
    html.append(">");
    return htmlConverter.convert(html.toString()).strip();
  }
}
