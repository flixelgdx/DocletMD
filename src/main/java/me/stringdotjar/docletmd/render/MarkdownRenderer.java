package me.stringdotjar.docletmd.render;

import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.SeeTree;
import com.sun.source.doctree.SinceTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.util.DocTrees;
import me.stringdotjar.docletmd.util.MdEscaper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Converts a {@link TypeElement} and its members into a Docusaurus-ready Markdown document.
 *
 * <p>One {@code MarkdownRenderer} instance is shared across all types in a single
 * doclet run. Call {@link #render(TypeElement)} once per top-level class, interface,
 * enum, or record that should appear in the output.
 *
 * <p>The Markdown produced by this class follows the structure:
 * <ol>
 *   <li>YAML frontmatter ({@code title}, {@code sidebar_label})</li>
 *   <li>H1 heading with the simple class name</li>
 *   <li>Qualified name as an inline code span</li>
 *   <li>Optional {@code :::caution Deprecated} admonition</li>
 *   <li>Class-level description and meta-tags ({@code @since}, {@code @see})</li>
 *   <li>Constructors section</li>
 *   <li>Fields section</li>
 *   <li>Methods section</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * MarkdownRenderer renderer = new MarkdownRenderer(trees, elements, false, false);
 * String md = renderer.render(myTypeElement);
 * }</pre>
 */
public final class MarkdownRenderer {

  private final DocTrees trees;
  private final Elements elements;
  private final InlineTagRenderer inline;
  private final boolean includePrivate;
  private final boolean skipEmptyDocs;

  /**
   * Creates a new renderer.
   *
   * @param trees the {@link DocTrees} instance provided by the doclet environment
   * @param elements the {@link Elements} utility from the doclet environment
   * @param includePrivate {@code true} to include private and package-private members
   * @param skipEmptyDocs {@code true} to omit members that have no Javadoc comment
   * @param allTypes all top-level types that will be rendered; used to resolve
   *     {@code {@link}} references to relative Markdown links
   */
  public MarkdownRenderer(DocTrees trees, Elements elements,
      boolean includePrivate, boolean skipEmptyDocs, List<TypeElement> allTypes) {
    this.trees = trees;
    this.elements = elements;
    this.includePrivate = includePrivate;
    this.skipEmptyDocs = skipEmptyDocs;
    Set<String> knownNames = allTypes.stream()
        .map(t -> t.getQualifiedName().toString())
        .collect(Collectors.toSet());
    this.inline = new InlineTagRenderer(elements, knownNames);
  }

  /**
   * Renders a single top-level type element to a complete Markdown document string.
   *
   * @param type the class, interface, enum, or record to render; must not be {@code null}
   * @return the full Markdown document as a string, never {@code null}
   */
  public String render(TypeElement type) {
    inline.setCurrentType(type);
    StringBuilder sb = new StringBuilder();
    DocCommentTree classDoc = trees.getDocCommentTree(type);

    appendFrontmatter(type, sb);
    appendClassHeader(type, classDoc, sb);
    appendConstructors(type, sb);
    appendFields(type, sb);
    appendMethods(type, sb);

    // Normalize trailing spaces on each line, collapse 3+ blank lines to one,
    // and ensure the file ends with exactly one newline.
    return sb.toString()
        .replaceAll("[ \t]+\n", "\n")
        .replaceAll("\n{3,}", "\n\n")
        .stripTrailing() + "\n";
  }

  // Writes the YAML frontmatter block.
  private void appendFrontmatter(TypeElement type, StringBuilder sb) {
    String simpleName = type.getSimpleName().toString();
    sb.append("---\n");
    sb.append("title: ").append(simpleName).append("\n");
    sb.append("sidebar_label: ").append(simpleName).append("\n");
    sb.append("---\n\n");
  }

  // Writes the H1 heading, qualified name, optional deprecation notice,
  // class description, and meta-tags.
  private void appendClassHeader(TypeElement type, DocCommentTree classDoc, StringBuilder sb) {
    String simpleName = type.getSimpleName().toString();
    sb.append("# ").append(simpleName).append("\n\n");

    // Kind indicator (omitted for plain classes to reduce noise)
    String kindLabel = kindLabel(type.getKind());
    if (!kindLabel.isEmpty()) {
      sb.append("*`").append(kindLabel).append("`*\n\n");
    }

    sb.append("`").append(type.getQualifiedName()).append("`\n\n");

    if (isDeprecated(type, classDoc)) {
      appendDeprecatedAdmonition(classDoc, sb);
    }

    if (classDoc != null) {
      String desc = renderBody(classDoc);
      if (!desc.isBlank()) {
        sb.append(desc).append("\n\n");
      }
    }

    appendMetaTags(classDoc, sb);
  }

  // Writes a Docusaurus :::caution block for deprecated elements.
  private void appendDeprecatedAdmonition(DocCommentTree doc, StringBuilder sb) {
    sb.append(":::caution Deprecated\n\n");
    if (doc != null) {
      String msg = deprecatedMessage(doc);
      if (!msg.isBlank()) {
        sb.append(msg).append("\n\n");
      }
    }
    sb.append(":::\n\n");
  }

  // Writes @since and @see meta-tags as bold labels.
  private void appendMetaTags(DocCommentTree doc, StringBuilder sb) {
    if (doc == null) {
      return;
    }
    for (DocTree tag : doc.getBlockTags()) {
      if (tag instanceof SinceTree since) {
        String val = inline.render(since.getBody()).strip();
        if (!val.isBlank()) {
          sb.append("**Since:** ").append(val).append("\n\n");
        }
      }
    }
    List<String> seeRefs = new ArrayList<>();
    for (DocTree tag : doc.getBlockTags()) {
      if (tag instanceof SeeTree see) {
        // getReference() returns the body of the @see tag (text, link, or HTML anchor).
        String ref = inline.render(see.getReference()).strip();
        if (!ref.isBlank()) {
          seeRefs.add(ref);
        }
      }
    }
    if (!seeRefs.isEmpty()) {
      sb.append("**See Also:** ").append(String.join(", ", seeRefs)).append("\n\n");
    }
  }

  // Writes the Constructors section.
  private void appendConstructors(TypeElement type, StringBuilder sb) {
    List<ExecutableElement> ctors = ElementFilter.constructorsIn(type.getEnclosedElements());
    List<ExecutableElement> visible = ctors.stream()
        .filter(this::isVisible)
        .filter(e -> !skipEmptyDocs || trees.getDocCommentTree(e) != null)
        .toList();

    if (visible.isEmpty()) {
      return;
    }
    sb.append("## Constructors\n\n");
    for (ExecutableElement ctor : visible) {
      appendExecutable(ctor, type.getSimpleName().toString(), sb);
    }
  }

  // Writes the Fields section.
  private void appendFields(TypeElement type, StringBuilder sb) {
    List<VariableElement> fields = ElementFilter.fieldsIn(type.getEnclosedElements());
    List<VariableElement> visible = fields.stream()
        .filter(this::isVisible)
        .filter(e -> !skipEmptyDocs || trees.getDocCommentTree(e) != null)
        .toList();

    if (visible.isEmpty()) {
      return;
    }
    sb.append("## Fields\n\n");
    for (VariableElement field : visible) {
      appendField(field, sb);
    }
  }

  // Writes the Methods section.
  private void appendMethods(TypeElement type, StringBuilder sb) {
    List<ExecutableElement> methods = ElementFilter.methodsIn(type.getEnclosedElements());
    List<ExecutableElement> visible = methods.stream()
        .filter(this::isVisible)
        .filter(e -> !skipEmptyDocs || trees.getDocCommentTree(e) != null)
        .toList();

    if (visible.isEmpty()) {
      return;
    }
    sb.append("## Methods\n\n");
    for (ExecutableElement method : visible) {
      appendExecutable(method, null, sb);
    }
  }

  // Writes a single field entry.
  private void appendField(VariableElement field, StringBuilder sb) {
    DocCommentTree doc = trees.getDocCommentTree(field);
    String mods = buildModifierPrefix(field);
    String type = simplifyType(field.asType().toString());
    String sig = (mods.isEmpty() ? "" : mods + " ") + type + " " + field.getSimpleName();
    // getConstantValue() returns non-null only for compile-time constants (primitives, String).
    // getConstantExpression() formats the value correctly: quoted strings, 'c' for chars, etc.
    Object constantVal = field.getConstantValue();
    if (constantVal != null) {
      sig += " = " + elements.getConstantExpression(constantVal);
    }
    sb.append(fieldMarker(field)).append("\n");
    sb.append("### `").append(sig).append("`\n\n");

    if (isDeprecated(field, doc)) {
      appendDeprecatedAdmonition(doc, sb);
    }
    if (doc != null) {
      String desc = renderBody(doc);
      if (!desc.isBlank()) {
        sb.append(desc).append("\n\n");
      }
    }
    appendMetaTags(doc, sb);
    sb.append("---\n\n");
  }

  // Writes a single constructor or method entry.
  // ctorName is non-null only when rendering a constructor.
  private void appendExecutable(ExecutableElement exec, String ctorName, StringBuilder sb) {
    DocCommentTree doc = trees.getDocCommentTree(exec);
    String sig = buildSignature(exec, ctorName);
    sb.append(execMarker(exec, ctorName != null)).append("\n");
    sb.append("### `").append(sig).append("`\n\n");

    if (isDeprecated(exec, doc)) {
      appendDeprecatedAdmonition(doc, sb);
    }

    if (doc != null) {
      String desc = renderBody(doc);
      if (!desc.isBlank()) {
        sb.append(desc).append("\n\n");
      }
    }

    appendParamTable(doc, sb);
    appendReturnsLine(doc, sb);
    appendThrowsTable(doc, sb);
    appendMetaTags(doc, sb);
    sb.append("---\n\n");
  }

  // Writes the parameters table if the doc comment has @param tags.
  private void appendParamTable(DocCommentTree doc, StringBuilder sb) {
    if (doc == null) {
      return;
    }
    List<ParamTree> params = doc.getBlockTags().stream()
        .filter(t -> t instanceof ParamTree pt && !pt.isTypeParameter())
        .map(t -> (ParamTree) t)
        .toList();
    if (params.isEmpty()) {
      return;
    }
    sb.append("**Parameters:**\n\n");
    sb.append("| Name | Description |\n");
    sb.append("|------|-------------|\n");
    for (ParamTree p : params) {
      String name = p.getName().toString();
      String desc = MdEscaper.escapeTableCell(inline.render(p.getDescription()).strip());
      sb.append("| `").append(name).append("` | ").append(desc).append(" |\n");
    }
    sb.append("\n");
  }

  // Writes the @return description if present.
  private void appendReturnsLine(DocCommentTree doc, StringBuilder sb) {
    if (doc == null) {
      return;
    }
    doc.getBlockTags().stream()
        .filter(t -> t instanceof ReturnTree)
        .map(t -> (ReturnTree) t)
        .findFirst()
        .ifPresent(r -> {
          String desc = inline.render(r.getDescription()).strip();
          if (!desc.isBlank()) {
            sb.append("**Returns:** ").append(desc).append("\n\n");
          }
        });
  }

  // Writes the throws table if the doc comment has @throws or @exception tags.
  private void appendThrowsTable(DocCommentTree doc, StringBuilder sb) {
    if (doc == null) {
      return;
    }
    List<ThrowsTree> thrown = doc.getBlockTags().stream()
        .filter(t -> t instanceof ThrowsTree)
        .map(t -> (ThrowsTree) t)
        .toList();
    if (thrown.isEmpty()) {
      return;
    }
    sb.append("**Throws:**\n\n");
    sb.append("| Type | Description |\n");
    sb.append("|------|-------------|\n");
    for (ThrowsTree t : thrown) {
      String type = t.getExceptionName().getSignature();
      String desc = MdEscaper.escapeTableCell(inline.render(t.getDescription()).strip());
      sb.append("| `").append(type).append("` | ").append(desc).append(" |\n");
    }
    sb.append("\n");
  }

  // Renders the first sentence and body of a doc comment to a single Markdown string.
  private String renderBody(DocCommentTree doc) {
    String first = inline.render(doc.getFirstSentence());
    String body = inline.render(doc.getBody());
    if (first.isBlank()) return body.strip();
    if (body.isBlank()) return first.strip();
    // The Javadoc parser consumes the whitespace token that separates the first sentence
    // from the body. Re-insert a single space so the text does not run together.
    return (first + " " + body).strip();
  }

  // Returns the text of the @deprecated block tag, or an empty string if absent.
  private String deprecatedMessage(DocCommentTree doc) {
    return doc.getBlockTags().stream()
        .filter(t -> t instanceof DeprecatedTree)
        .map(t -> (DeprecatedTree) t)
        .findFirst()
        .map(d -> inline.render(d.getBody()).strip())
        .orElse("");
  }

  // Returns true if the element is annotated with @Deprecated or has a @deprecated tag.
  private boolean isDeprecated(Element element, DocCommentTree doc) {
    boolean annotated = elements.isDeprecated(element);
    boolean tagged = doc != null && doc.getBlockTags().stream()
        .anyMatch(t -> t instanceof DeprecatedTree);
    return annotated || tagged;
  }

  // Returns true if the element should be included based on visibility settings.
  private boolean isVisible(Element e) {
    Set<Modifier> mods = e.getModifiers();
    if (includePrivate) {
      return true;
    }
    return mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
  }

  // Builds the signature string shown as the heading for a constructor or method.
  // ctorName must be supplied for constructors (since getSimpleName returns "<init>").
  private String buildSignature(ExecutableElement exec, String ctorName) {
    StringBuilder sig = new StringBuilder();
    boolean isCtor = ctorName != null;

    // Modifiers come first (public static final ...), then return type, then name.
    String mods = buildModifierPrefix(exec);
    if (!mods.isEmpty()) {
      sig.append(mods).append(" ");
    }
    if (!isCtor) {
      sig.append(simplifyType(exec.getReturnType().toString())).append(" ");
    }
    sig.append(isCtor ? ctorName : exec.getSimpleName());
    sig.append("(");

    List<? extends VariableElement> params = exec.getParameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        sig.append(", ");
      }
      VariableElement p = params.get(i);
      boolean isLastVararg = exec.isVarArgs() && i == params.size() - 1;
      String typeName = simplifyType(p.asType().toString());
      if (isLastVararg && typeName.endsWith("[]")) {
        // Show varargs as "..." instead of "[]"
        typeName = typeName.substring(0, typeName.length() - 2) + "...";
      }
      sig.append(typeName).append(" ").append(p.getSimpleName());
    }
    sig.append(")");
    return sig.toString();
  }

  // Returns the HTML comment marker for a field. Invisible in all standard Markdown renderers;
  // readable by a Docusaurus remark plugin for per-member color coding.
  private static String fieldMarker(VariableElement field) {
    Set<Modifier> mods = field.getModifiers();
    boolean isStatic = mods.contains(Modifier.STATIC);
    boolean isConstant = isStatic && mods.contains(Modifier.FINAL)
        && field.getConstantValue() != null;
    if (isConstant) return "<!-- docletmd:field:constant -->";
    if (isStatic) return "<!-- docletmd:field:static -->";
    return "<!-- docletmd:field -->";
  }

  // Returns the HTML comment marker for a constructor or method.
  private static String execMarker(ExecutableElement exec, boolean isCtor) {
    if (isCtor) return "<!-- docletmd:constructor -->";
    return exec.getModifiers().contains(Modifier.STATIC)
        ? "<!-- docletmd:method:static -->"
        : "<!-- docletmd:method -->";
  }

  // Returns a space-separated modifier string in canonical Java order (public/protected/private,
  // then static, abstract, final, synchronized, etc.). Omits "abstract" because it is implied
  // by the interface or class declaration and adds noise to every method heading.
  // Modifier.compareTo() follows enum declaration order, which matches the JLS modifier order.
  private static String buildModifierPrefix(Element e) {
    return e.getModifiers().stream()
        .filter(m -> m != Modifier.ABSTRACT)
        .sorted()
        .map(Modifier::toString)
        .collect(Collectors.joining(" "));
  }

  // Strips package prefixes from type names so signatures stay readable.
  // For example: "java.util.List<java.lang.String>" -> "List<String>"
  private String simplifyType(String typeName) {
    return typeName.replaceAll("([a-z][a-z0-9_]*\\.)+([A-Z])", "$2");
  }

  // Returns the human-readable kind label for a type, or empty for plain classes.
  private String kindLabel(ElementKind kind) {
    return switch (kind) {
      case INTERFACE -> "interface";
      case ENUM -> "enum";
      case RECORD -> "record";
      case ANNOTATION_TYPE -> "@interface";
      case CLASS -> "class";
      default -> "";
    };
  }
}
