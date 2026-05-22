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
 * InlineTagRenderer r = new InlineTagRenderer(elements, knownNames);
 * r.setCurrentType(myTypeElement);
 * String markdown = r.render(docCommentTree.getFirstSentence());
 * }</pre>
 */
public final class InlineTagRenderer {

  private final Elements elements;
  private final Set<String> knownQualifiedNames;
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
  }

  /**
   * Creates a renderer with cross-linking capability.
   *
   * <p>References to classes in {@code knownQualifiedNames} are rendered as relative
   * Markdown links. References to external classes fall back to inline code spans.
   *
   * @param elements the {@link Elements} utility from the doclet environment
   * @param knownQualifiedNames all qualified class names that have a generated Markdown page
   */
  public InlineTagRenderer(Elements elements, Set<String> knownQualifiedNames) {
    this.elements = elements;
    this.knownQualifiedNames = knownQualifiedNames;
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
        if (inPre) yield body;
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
        yield normalized.isBlank() ? "" : MdEscaper.escapeMdx(normalized);
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

  // Renders a {@link} or {@linkplain} as a Markdown link when the target is a known
  // generated page, or falls back to an inline code span for external references.
  private String renderLink(LinkTree link) {
    List<? extends DocTree> labelNodes = link.getLabel();
    String sig = link.getReference().getSignature();

    String text;
    if (labelNodes.isEmpty()) {
      // Derive display text from the signature: strip leading "#", replace "#" with ".".
      String bare = sig.startsWith("#") ? sig.substring(1) : sig.replace('#', '.');
      // Show only simple class name for qualified references (e.g. "pkg.Foo" -> "Foo").
      int lastDot = bare.lastIndexOf('.');
      text = lastDot >= 0 && Character.isUpperCase(bare.charAt(lastDot + 1))
          ? bare.substring(lastDot + 1) : bare;
    } else {
      text = render(labelNodes);
    }

    String url = resolveUrl(sig);
    return url != null ? "[" + text + "](" + url + ")" : "`" + text + "`";
  }

  // Renders a @see reference (ReferenceTree) as a Markdown link when the target is known,
  // or as an inline code span otherwise.
  private String renderReference(ReferenceTree ref) {
    String sig = ref.getSignature();
    String url = resolveUrl(sig);
    if (url != null) {
      // Use simple class name as display text.
      int hash = sig.indexOf('#');
      String classRef = hash >= 0 ? sig.substring(0, hash) : sig;
      int dot = classRef.lastIndexOf('.');
      String simpleName = dot >= 0 ? classRef.substring(dot + 1) : classRef;
      String member = hash >= 0 ? "." + sig.substring(hash + 1) : "";
      return "[" + simpleName + member + "](" + url + ")";
    }
    return "`" + sig.replace('#', '.') + "`";
  }

  // Maps known HTML start tags to their Markdown equivalents.
  // Unrecognized tags are converted via flexmark's HTML-to-Markdown converter.
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

  // Maps known HTML end tags to their Markdown equivalents.
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

  // Extracts href from an <a> tag. Only absolute URLs (http/https/mailto) become Markdown
  // links; relative hrefs point to Javadoc-site paths that do not exist in the generated
  // docs and would produce broken links in Docusaurus. For relative hrefs, the anchor's
  // text content is rendered as plain text (no "[" emitted, no "](...)" emitted).
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

  // Converts an unrecognized HTML start tag to Markdown via flexmark.
  // getAttributes() returns List<? extends DocTree>; only AttributeTree nodes carry
  // attribute data, so other node kinds (e.g., ErroneousTree) are skipped.
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

  // Resolves a Javadoc reference signature to a relative Markdown URL, or returns
  // null if the target is not a known generated page.
  private String resolveUrl(String sig) {
    if (elements == null || knownQualifiedNames == null || currentType == null) return null;

    if (sig.startsWith("#")) {
      // Same-class member reference: link to an anchor on the current page.
      String memberSig = sig.substring(1);
      Element member = findMember(memberSig);
      if (member == null) return null;
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
        if (member != null) anchor = elementToAnchor(member);
      }
      return anchor != null ? relPath + "#" + anchor : relPath;
    }
    return relPath;
  }

  // Resolves a simple or qualified class name to a fully qualified name
  // that is present in the set of known generated pages.
  private String resolveToQualifiedName(String classRef) {
    if (knownQualifiedNames.contains(classRef)) return classRef;
    // Simple name: search for a unique match in the known set.
    String suffix = "." + classRef;
    List<String> matches = knownQualifiedNames.stream()
        .filter(q -> q.endsWith(suffix))
        .toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }

  // Computes the relative Markdown path from the current class's file to the target's file.
  // Both arguments are fully qualified class names (e.g. "me.example.Foo").
  // The ".md" extension is included so Docusaurus resolves the link as a doc reference
  // rather than a bare HTML URL, which would 404 on a static site.
  private static String computeRelPath(String currentQual, String targetQual) {
    Path currentDir = Path.of(currentQual.replace('.', '/')).getParent();
    if (currentDir == null) currentDir = Path.of(".");
    Path targetFile = Path.of(targetQual.replace('.', '/') + ".md");
    String rel = currentDir.relativize(targetFile).toString().replace('\\', '/');
    // Ensure a "./" prefix for same-directory or deeper links (Docusaurus requires explicit paths).
    return rel.startsWith("..") ? rel : "./" + rel;
  }

  // Finds the first member (method, constructor, or field) of the current type whose
  // simple name matches the leading name portion of the reference signature.
  private Element findMember(String memberSig) {
    return findMemberIn(currentType, memberSig);
  }

  // Finds the first member of the given type whose simple name matches the reference.
  private static Element findMemberIn(TypeElement type, String memberSig) {
    int paren = memberSig.indexOf('(');
    String name = (paren >= 0 ? memberSig.substring(0, paren) : memberSig).trim();
    for (Element enclosed : type.getEnclosedElements()) {
      if (enclosed.getSimpleName().toString().equals(name)) return enclosed;
    }
    return null;
  }

  // Converts an element to the Docusaurus anchor that would be generated for its
  // section heading, using the same github-slugger rules Docusaurus applies.
  // The heading text must exactly mirror what MarkdownRenderer writes into the ### heading.
  private String elementToAnchor(Element el) {
    String heading;
    if (el instanceof ExecutableElement exec) {
      boolean isCtor = exec.getKind() == ElementKind.CONSTRUCTOR;
      String ctorName = isCtor
          ? exec.getEnclosingElement().getSimpleName().toString()
          : null;
      heading = buildHeadingSig(exec, ctorName);
    } else if (el instanceof VariableElement var) {
      // Mirror appendField(): modifiers + type + name [+ " = " + constant].
      String mods = buildModifierPrefix(var);
      heading = (mods.isEmpty() ? "" : mods + " ")
          + simplifyType(var.asType().toString()) + " " + var.getSimpleName();
      Object val = var.getConstantValue();
      if (val != null && elements != null) {
        heading += " = " + elements.getConstantExpression(val);
      }
    } else {
      return null;
    }
    return slugify(heading);
  }

  // Builds the method/constructor signature string that MarkdownRenderer uses as the
  // section heading. Must mirror MarkdownRenderer.buildSignature() exactly,
  // including the modifier prefix added when modifiers were introduced.
  private static String buildHeadingSig(ExecutableElement exec, String ctorName) {
    StringBuilder sig = new StringBuilder();
    String mods = buildModifierPrefix(exec);
    if (!mods.isEmpty()) {
      sig.append(mods).append(" ");
    }
    if (ctorName == null) {
      sig.append(simplifyType(exec.getReturnType().toString())).append(" ");
    }
    sig.append(ctorName != null ? ctorName : exec.getSimpleName()).append("(");
    List<? extends VariableElement> params = exec.getParameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) sig.append(", ");
      VariableElement p = params.get(i);
      boolean isLastVararg = exec.isVarArgs() && i == params.size() - 1;
      String typeName = simplifyType(p.asType().toString());
      if (isLastVararg && typeName.endsWith("[]")) {
        typeName = typeName.substring(0, typeName.length() - 2) + "...";
      }
      sig.append(typeName).append(" ").append(p.getSimpleName());
    }
    sig.append(")");
    return sig.toString();
  }

  // Returns a space-separated modifier string in canonical Java order, omitting "abstract"
  // because it is implied by the enclosing type. Must stay identical to the equivalent
  // helper in MarkdownRenderer so that anchor slugs match section headings.
  private static String buildModifierPrefix(Element e) {
    return e.getModifiers().stream()
        .filter(m -> m != Modifier.ABSTRACT)
        .sorted()
        .map(Modifier::toString)
        .collect(Collectors.joining(" "));
  }

  // Produces the Docusaurus anchor ID for a heading string, matching the github-slugger
  // algorithm: lowercase, strip non-alphanumeric chars (except spaces/hyphens),
  // collapse and replace spaces with hyphens.
  private static String slugify(String heading) {
    return heading.toLowerCase()
        .replaceAll("[^a-z0-9\\s-]", "")
        .trim()
        .replaceAll("\\s+", "-");
  }

  // Strips package prefixes from type names so signatures stay readable.
  private static String simplifyType(String typeName) {
    return typeName.replaceAll("([a-z][a-z0-9_]*\\.)+([A-Z])", "$2");
  }
}
