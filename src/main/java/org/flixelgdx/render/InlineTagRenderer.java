package org.flixelgdx.render;

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
import org.flixelgdx.util.MdEscaper;
import org.flixelgdx.util.Signatures;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

/**
 * Converts a list of inline {@link DocTree} nodes to a Markdown string.
 *
 * <p>This class handles the common inline doc-tree node types produced by the Javadoc
 * parser: plain text, {@code {@code}}, {@code {@literal}}, {@code {@link}},
 * {@code {@linkplain}}, HTML start/end tags, and HTML entities.
 *
 * <p>When constructed with an {@link Elements} instance and a set of known qualified class
 * names, {@code {@link}} and {@code @see} references that point to a generated page are
 * rendered as relative Markdown links that Docusaurus can follow. References to external
 * classes fall back to inline code spans.
 *
 * <p>For HTML elements that are not explicitly recognized, the tag content is
 * converted to Markdown using {@link FlexmarkHtmlConverter} so that Javadoc
 * descriptions containing tables, definition lists, or other complex HTML still
 * produce readable output.
 *
 * <p>Example usage:
 * <pre>{@code
 * InlineTagRenderer r = new InlineTagRenderer(elements, knownNames, false);
 * r.setCurrentType(myTypeElement);
 * String markdown = r.render(docCommentTree.getFirstSentence());
 * }</pre>
 */
public final class InlineTagRenderer {

  private final Elements elements;
  private final Set<String> knownQualifiedNames;
  // When false (default), links to private/package-private members fall back to code spans
  // because those members are not rendered and their page anchors do not exist.
  private final boolean includePrivate;
  private TypeElement currentType;

  private final FlexmarkHtmlConverter htmlConverter = FlexmarkHtmlConverter.builder().build();

  // True while rendering the content of a <pre> block. Inside a code fence,
  // {@code} must not add backticks, text must not be MDX-escaped, and
  // <code>/<strong>/<em> markers are no-ops.
  private boolean inPre;
  // Carries the href value across the START_ELEMENT and END_ELEMENT nodes of an <a> tag.
  private String pendingHref;
  // Tracks whether we are inside a <thead> block; used to count columns for the GFM separator row.
  private boolean inTableHeader;
  // Number of <th> elements seen in the current <thead>; determines separator row width.
  private int tableHeaderCols;

  /**
   * Creates a renderer without cross-linking capability.
   *
   * <p>Use this constructor when no type context is available. All {@code {@link}} and
   * {@code @see} references are rendered as inline code spans.
   */
  public InlineTagRenderer() {
    this.elements = null;
    this.knownQualifiedNames = null;
    this.includePrivate = false;
  }

  /**
   * Creates a renderer with cross-linking capability.
   *
   * <p>References to classes in {@code knownQualifiedNames} are rendered as relative
   * Markdown links. References to external classes fall back to inline code spans.
   * Links to private or package-private members fall back to code spans when
   * {@code includePrivate} is {@code false}, because those members have no generated page anchor.
   *
   * @param elements the {@link Elements} utility from the doclet environment
   * @param knownQualifiedNames all qualified class names that have a generated Markdown page
   * @param includePrivate {@code true} when private and package-private members are rendered,
   *     so links to them can be resolved; {@code false} causes such links to degrade gracefully
   *     to code spans instead of pointing at non-existent anchors
   */
  public InlineTagRenderer(Elements elements, Set<String> knownQualifiedNames, boolean includePrivate) {
    this.elements = elements;
    this.knownQualifiedNames = knownQualifiedNames;
    this.includePrivate = includePrivate;
  }

  /**
   * Sets the type currently being rendered.
   *
   * <p>Must be called before rendering each new type so that relative link paths are
   * computed from the correct source package.
   *
   * @param type the type element whose Markdown page is being built
   */
  public void setCurrentType(TypeElement type) {
    this.currentType = type;
    this.inPre = false;
    this.pendingHref = null;
    this.inTableHeader = false;
    this.tableHeaderCols = 0;
  }

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
      case TEXT -> {
        String body = ((TextTree) node).getBody();
        if (inPre) {
          // Whitespace-only text nodes inside <pre> are formatting artifacts (e.g.
          // the newline+indent between the closing } of {@code} and </pre> when
          // </pre> appears on its own line). Suppress them to avoid empty trailing
          // lines inside the code fence.
          if (body.isBlank()) yield "";
          yield body.replaceAll("[ \t]+\n", "\n");
        }
        // Javadoc line-wrapping embeds raw newlines + indentation spaces in TextTree nodes.
        // Normalize any newline (and its surrounding horizontal whitespace) to a single space
        // so that list items with inline tags like <b> stay on one line in Markdown.
        // Paragraph breaks come from explicit <p> tags, not from raw line wrapping.
        if (!body.contains("\n")) {
          yield MdEscaper.escapeMdx(body);
        }
        // Do NOT strip() here. Leading/trailing spaces around tags like <b> or {@link}
        // are word-boundary separators and must survive to the output.
        // Only drop the result entirely when it collapsed to pure whitespace.
        String normalized = body.replaceAll("[ \t]*\\n[ \t]*", " ");
        // A whitespace-only text node that collapsed from a line break is still a
        // word-boundary separator between adjacent inline tags (e.g. </b> on one
        // source line, {@link} on the next). Preserve it as a single space so that
        // bold/link boundaries like "**Bold** [link](url)" are not collapsed to
        // "**Bold**[link](url)" which breaks bold rendering in CommonMark.
        yield normalized.isBlank() ? (body.contains("\n") ? " " : "") : MdEscaper.escapeMdx(normalized);
      }
      case CODE, LITERAL -> {
        String body = ((LiteralTree) node).getBody().getBody();
        if (inPre) {
          // Inside <pre>, strip the common leading indentation that the Javadoc
          // source alignment adds to every line, then strip the outer blank lines.
          yield body.stripIndent().strip();
        }
        // Inline {@code}: normalize any internal newlines to a space so the
        // code span stays on one line.
        yield "`" + body.replaceAll("\\s+", " ").strip() + "`";
      }
      case LINK, LINK_PLAIN -> renderLink((LinkTree) node);
      case REFERENCE -> renderReference((ReferenceTree) node);
      case START_ELEMENT -> renderHtmlStart((StartElementTree) node);
      case END_ELEMENT -> renderHtmlEnd((EndElementTree) node);
      case ENTITY -> "&" + ((EntityTree) node).getName() + ";";
      default -> "";
    };
  }

  /**
   * Renders a {@code {@link}} or {@code {@linkplain}} as a Markdown link when the target is
   * a known generated page, or as an inline code span for an external reference.
   *
   * <p>When the tag has no explicit label, the display text is derived from the reference:
   * <ul>
   *   <li>Class-only reference: the simple class name (for example {@code "FlixelSprite"}).</li>
   *   <li>Field reference ({@code ClassName#field} or {@code #field}): {@code "ClassName.field"}.</li>
   *   <li>No-arg method ({@code ClassName#method()} or {@code #method()}): {@code "ClassName.method()"}.</li>
   *   <li>Method with parameters: {@code "ClassName.method(...)"}.</li>
   * </ul>
   *
   * @param link the {@code {@link}} tag node to render
   * @return the Markdown link or code span, never {@code null}
   */
  private String renderLink(LinkTree link) {
    List<? extends DocTree> labelNodes = link.getLabel();
    String sig = link.getReference().getSignature();

    String text;
    if (labelNodes.isEmpty()) {
      int hash = sig.indexOf('#');
      if (hash >= 0) {
        // Member reference: build "ClassName.member" display text.
        String className;
        if (hash == 0) {
          // Same-class reference (#member): use the current type's simple name.
          className = currentType != null ? currentType.getSimpleName().toString() : "";
        } else {
          String classRef = sig.substring(0, hash);
          int lastDot = classRef.lastIndexOf('.');
          className = (lastDot >= 0 && lastDot + 1 < classRef.length()
              && Character.isUpperCase(classRef.charAt(lastDot + 1)))
              ? classRef.substring(lastDot + 1) : classRef;
        }
        String memberPart = sig.substring(hash + 1);
        int paren = memberPart.indexOf('(');
        if (paren >= 0) {
          // Method reference: check whether params are present between the parens.
          String memberName = memberPart.substring(0, paren);
          int closeParen = memberPart.lastIndexOf(')');
          String params = closeParen > paren ? memberPart.substring(paren + 1, closeParen) : "";
          text = params.isBlank()
              ? className + "." + memberName + "()"
              : className + "." + memberName + "(...)";
        } else {
          // No parens in the reference, so this could be a method or a field. Look up the
          // element so we can append "()" or "(...)" for methods rather than leaving
          // it bare (which looks like a field reference to readers).
          text = className + "." + memberPart;
          if (elements != null) {
            String qual = hash == 0
                ? (currentType != null ? currentType.getQualifiedName().toString() : null)
                : resolveToQualifiedName(sig.substring(0, hash));
            if (qual != null) {
              TypeElement te = elements.getTypeElement(qual);
              if (te != null) {
                Element mem = findMemberIn(te, memberPart);
                if (mem instanceof ExecutableElement exec) {
                  text = exec.getParameters().isEmpty()
                      ? className + "." + memberPart + "()"
                      : className + "." + memberPart + "(...)";
                }
              }
            }
          }
        }
      } else {
        // Class-only reference: show simple class name, stripping any package prefix.
        String bare = sig;
        int lastDot = bare.lastIndexOf('.');
        text = lastDot >= 0 && lastDot + 1 < bare.length()
            && Character.isUpperCase(bare.charAt(lastDot + 1))
            ? bare.substring(lastDot + 1) : bare;
        int paren = text.indexOf('(');
        if (paren >= 0) text = text.substring(0, paren);
      }
    } else {
      text = render(labelNodes);
    }

    String url = resolveUrl(sig);
    return url != null ? "[" + text + "](" + url + ")" : "`" + text + "`";
  }

  /**
   * Renders a {@code @see} reference as a Markdown link when the target is a known generated
   * page, or as an inline code span otherwise.
   *
   * @param ref the {@code @see} reference node to render
   * @return the Markdown link or code span, never {@code null}
   */
  private String renderReference(ReferenceTree ref) {
    String sig = ref.getSignature();
    String url = resolveUrl(sig);
    if (url != null) {
      // Use simple class name as display text, stripping any parameter list.
      int hash = sig.indexOf('#');
      String classRef = hash >= 0 ? sig.substring(0, hash) : sig;
      int dot = classRef.lastIndexOf('.');
      String simpleName = dot >= 0 ? classRef.substring(dot + 1) : classRef;
      String memberRaw = hash >= 0 ? sig.substring(hash + 1) : null;
      String member = "";
      if (memberRaw != null) {
        int paren = memberRaw.indexOf('(');
        member = "." + (paren >= 0 ? memberRaw.substring(0, paren) : memberRaw);
      }
      return "[" + simpleName + member + "](" + url + ")";
    }
    return "`" + sig.replace('#', '.') + "`";
  }

  /**
   * Maps a known HTML start tag to its Markdown equivalent.
   *
   * <p>Unrecognized tags are converted with flexmark's HTML-to-Markdown converter.
   *
   * @param tag the HTML start tag node
   * @return the Markdown (or pass-through HTML) for the tag, never {@code null}
   */
  private String renderHtmlStart(StartElementTree tag) {
    String name = tag.getName().toString().toLowerCase();
    return switch (name) {
      case "p" -> "\n\n";
      case "br", "ul", "ol" -> "\n";
      // <tr> must NOT emit "\n", as </tr> already ends the row with "|\n", so adding another
      // "\n" here would produce a blank line between every table row and break GFM parsing.
      case "tr" -> "";
      case "pre" -> { inPre = true; yield "\n```java\n"; }
      // Inside a <pre> block, <code> is a no-op; the fenced delimiters are sufficient.
      case "code" -> inPre ? "" : "`";
      case "b", "strong" -> inPre ? "" : "**";
      case "i", "em" -> inPre ? "" : "*";
      case "h1" -> "\n\n# ";
      case "h2" -> "\n\n## ";
      case "h3" -> "\n\n### ";
      case "h4" -> "\n\n#### ";
      case "h5" -> "\n\n##### ";
      case "h6" -> "\n\n###### ";
      case "li" -> "\n- ";
      // Tables: reset the column counter on <table>, track header columns via <thead>/<th>.
      case "table" -> { tableHeaderCols = 0; yield "\n\n"; }
      case "caption", "tbody" -> "";
      case "thead" -> { inTableHeader = true; yield ""; }
      case "td" -> "| ";
      // Count each <th> so we know how many separator columns to emit after </thead>.
      case "th" -> { if (inTableHeader) tableHeaderCols++; yield "| "; }
      case "a" -> buildHtmlAnchor(tag);
      default -> convertUnknownTag(tag);
    };
  }

  /**
   * Maps a known HTML end tag to its Markdown equivalent.
   *
   * @param tag the HTML end tag node
   * @return the Markdown for the tag, never {@code null}
   */
  private String renderHtmlEnd(EndElementTree tag) {
    String name = tag.getName().toString().toLowerCase();
    return switch (name) {
      case "p", "h1", "h2", "h3", "h4", "h5", "h6", "caption" -> "\n\n";
      case "pre" -> { inPre = false; yield "\n```\n"; }
      case "code" -> inPre ? "" : "`";
      case "b", "strong" -> inPre ? "" : "**";
      case "i", "em" -> inPre ? "" : "*";
      case "td", "th" -> " ";
      case "tr" -> "|\n";
      // Emit the GFM separator row (| --- | --- | ...) immediately after the header row.
      case "thead" -> {
        inTableHeader = false;
        StringBuilder sep = new StringBuilder();
        sep.append("| --- ".repeat(Math.max(0, tableHeaderCols)));
        if (tableHeaderCols > 0) sep.append("|");
        sep.append("\n");
        yield sep.toString();
      }
      case "a" -> {
        String href = pendingHref;
        pendingHref = null;
        // If buildHtmlAnchor decided not to emit "[" (no absolute href), there is nothing to close.
        yield href != null ? "](" + href + ")" : "";
      }
      default -> "";
    };
  }

  /**
   * Extracts the {@code href} from an {@code <a>} tag and begins a Markdown link when it is
   * absolute.
   *
   * <p>Only absolute URLs ({@code http}, {@code https}, or {@code mailto}) become Markdown
   * links. A relative {@code href} points at a Javadoc-site path that does not exist in the
   * generated docs and would produce a broken link in Docusaurus, so for a relative
   * {@code href} the anchor's text content is rendered as plain text (no opening {@code [}
   * and no closing {@code ](...)}).
   *
   * @param tag the {@code <a>} start tag node
   * @return {@code "["} to open a Markdown link for an absolute {@code href}, or an empty
   *     string otherwise
   */
  private String buildHtmlAnchor(StartElementTree tag) {
    for (DocTree child : tag.getAttributes()) {
      if (!(child instanceof AttributeTree attr)) continue;
      if (!attr.getName().toString().equalsIgnoreCase("href")) continue;
      String href = attr.getValue().stream()
          .filter(t -> t instanceof TextTree)
          .map(t -> ((TextTree) t).getBody())
          .collect(Collectors.joining());
      if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
        pendingHref = href;
        return "[";
      }
      break;
    }
    return "";
  }

  /**
   * Converts an unrecognized HTML start tag to Markdown with flexmark.
   *
   * <p>{@code getAttributes()} returns {@code List<? extends DocTree>}; only
   * {@link AttributeTree} nodes carry attribute data, so other node kinds (for example an
   * erroneous tree) are skipped.
   *
   * @param tag the unrecognized HTML start tag node
   * @return the converted Markdown, never {@code null}
   */
  private String convertUnknownTag(StartElementTree tag) {
    StringBuilder html = new StringBuilder("<").append(tag.getName());
    for (DocTree child : tag.getAttributes()) {
      if (!(child instanceof AttributeTree attr)) continue;
      html.append(' ').append(attr.getName());
      if (attr.getValueKind() != AttributeTree.ValueKind.EMPTY) {
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

  /**
   * Resolves a Javadoc reference signature to a relative Markdown URL.
   *
   * @param sig the reference signature (for example {@code "com.example.Foo#bar(int)"} or
   *     {@code "#bar"})
   * @return the relative Markdown URL, or {@code null} when the target is not a known
   *     generated page or its member is not rendered
   */
  private String resolveUrl(String sig) {
    if (elements == null || knownQualifiedNames == null || currentType == null) return null;

    if (sig.startsWith("#")) {
      // Same-class member reference: link to an anchor on the current page.
      String memberSig = sig.substring(1);
      Element member = findMember(memberSig);
      if (member == null) return null;
      // Private members are not rendered and have no page anchor; degrade to a code span.
      if (!isMemberVisible(member)) return null;
      String anchor = elementToAnchor(member);
      return anchor != null ? "#" + anchor : null;
    }

    int hash = sig.indexOf('#');
    String classRef = hash >= 0 ? sig.substring(0, hash) : sig;
    String memberRef = hash >= 0 ? sig.substring(hash + 1) : null;

    if (classRef.isBlank()) return null;

    String qualName = resolveToQualifiedName(classRef);
    if (qualName == null) return null;

    String currentQual = currentType.getQualifiedName().toString();
    String relPath;
    if (qualName.equals(currentQual)) {
      // Same class: pure anchor only.
      if (memberRef == null) return null;
      Element member = findMember(memberRef);
      if (member == null) return null;
      // Private members are not rendered and have no page anchor; degrade to a code span.
      if (!isMemberVisible(member)) return null;
      String anchor = elementToAnchor(member);
      return anchor != null ? "#" + anchor : null;
    }

    relPath = computeRelPath(currentQual, qualName);

    if (memberRef != null) {
      // Find the member in the target type (if resolvable) for an exact anchor.
      TypeElement targetType = elements.getTypeElement(qualName);
      String anchor = null;
      if (targetType != null) {
        Element member = findMemberIn(targetType, memberRef);
        // Only link to the member if it will actually be rendered on the target page.
        if (member != null && !isMemberVisible(member)) member = null;
        if (member != null) anchor = elementToAnchor(member);
      }
      return anchor != null ? relPath + "#" + anchor : relPath;
    }
    return relPath;
  }

  /**
   * Resolves a simple or qualified class name to a fully qualified name that is present in
   * the set of known generated pages.
   *
   * @param classRef the class reference, either fully qualified or a simple name
   * @return the matching fully qualified name, or {@code null} when there is no unique match
   */
  private String resolveToQualifiedName(String classRef) {
    if (knownQualifiedNames.contains(classRef)) return classRef;
    // Simple name: search for a unique match in the known set.
    String suffix = "." + classRef;
    List<String> matches = knownQualifiedNames.stream()
        .filter(q -> q.endsWith(suffix))
        .toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }

  /**
   * Computes the relative Markdown path from the current class's file to the target's file.
   *
   * <p>The {@code .md} extension is included so Docusaurus resolves the link as a doc
   * reference rather than a bare HTML URL, which would 404 on a static site.
   *
   * @param currentQual the fully qualified name of the current class (for example
   *     {@code "me.example.Foo"})
   * @param targetQual the fully qualified name of the target class
   * @return the relative path to the target's {@code .md} file, using forward slashes
   */
  private static String computeRelPath(String currentQual, String targetQual) {
    Path currentDir = Path.of(currentQual.replace('.', '/')).getParent();
    if (currentDir == null) currentDir = Path.of(".");
    Path targetFile = Path.of(targetQual.replace('.', '/') + ".md");
    String rel = currentDir.relativize(targetFile).toString().replace('\\', '/');
    // Ensure a "./" prefix for same-directory or deeper links (Docusaurus requires explicit paths).
    return rel.startsWith("..") ? rel : "./" + rel;
  }

  /**
   * Finds the first member of the current type whose simple name matches the leading name
   * portion of the reference signature.
   *
   * @param memberSig the member reference (for example {@code "bar"} or {@code "bar(int)"})
   * @return the matching member, or {@code null} when none matches
   */
  private Element findMember(String memberSig) {
    return findMemberIn(currentType, memberSig);
  }

  /**
   * Finds the first member of the given type whose simple name matches the reference.
   *
   * @param type the type to search
   * @param memberSig the member reference (for example {@code "bar"} or {@code "bar(int)"})
   * @return the matching member, or {@code null} when none matches
   */
  private static Element findMemberIn(TypeElement type, String memberSig) {
    int paren = memberSig.indexOf('(');
    String name = (paren >= 0 ? memberSig.substring(0, paren) : memberSig).trim();
    for (Element enclosed : type.getEnclosedElements()) {
      if (enclosed.getSimpleName().toString().equals(name)) return enclosed;
    }
    return null;
  }

  /**
   * Returns whether a member will actually be rendered and therefore has a page anchor.
   *
   * <p>Private and package-private members are not rendered unless {@code includePrivate}
   * is {@code true}.
   *
   * @param member the member to test
   * @return {@code true} when the member is rendered and has an anchor
   */
  private boolean isMemberVisible(Element member) {
    if (includePrivate) return true;
    Set<Modifier> mods = member.getModifiers();
    return mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
  }

  /**
   * Converts an element to the Docusaurus anchor that would be generated for its section
   * heading.
   *
   * <p>The signature comes from {@link Signatures}, the single source of truth that
   * {@code MarkdownRenderer} also uses to write the heading, so the computed anchor always
   * matches the heading text.
   *
   * @param el the member element to convert
   * @return the anchor slug, or {@code null} when {@code el} is not a method, constructor,
   *     or field
   */
  private String elementToAnchor(Element el) {
    String heading;
    if (el instanceof ExecutableElement exec) {
      boolean isCtor = exec.getKind() == ElementKind.CONSTRUCTOR;
      String ctorName = isCtor
          ? exec.getEnclosingElement().getSimpleName().toString()
          : null;
      heading = Signatures.methodSignature(exec, ctorName);
    } else if (el instanceof VariableElement var) {
      heading = Signatures.fieldSignature(var, elements);
    } else {
      return null;
    }
    return slugify(heading);
  }

  /**
   * Produces the Docusaurus anchor ID for a heading string, matching github-slugger v1:
   * lowercase, replace each space individually with a hyphen, then strip any remaining
   * non-word (non-{@code [a-zA-Z0-9_]}) and non-hyphen character. Underscores are preserved.
   *
   * @param heading the heading text to slugify
   * @return the anchor slug
   */
  private static String slugify(String heading) {
    return heading.toLowerCase()
        .replace(" ", "-")
        .replaceAll("[^\\w-]", "");
  }
}
