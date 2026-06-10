package org.flixelgdx.render;

import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.SeeTree;
import com.sun.source.doctree.SinceTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.util.DocTrees;
import org.flixelgdx.util.MdEscaper;
import org.flixelgdx.util.Signatures;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.jetbrains.annotations.Nullable;

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
 *   <li>H1 heading with the simple class name (wrapped with source link in a flex row when
 *       a source base URL is configured)</li>
 *   <li>Qualified name as an inline code span</li>
 *   <li>Colorized type declaration (modifiers, name, extends, implements)</li>
 *   <li>Optional {@code :::caution Deprecated} admonition</li>
 *   <li>Class-level description and meta-tags ({@code @since}, {@code @see})</li>
 *   <li>Constructors section</li>
 *   <li>Fields section</li>
 *   <li>Methods section</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * MarkdownRenderer renderer = new MarkdownRenderer(trees, elements, false, false, types, null);
 * String md = renderer.render(myTypeElement);
 * }</pre>
 */
public final class MarkdownRenderer {

  private final DocTrees trees;
  private final Elements elements;
  private final InlineTagRenderer inline;
  private final boolean includePrivate;
  private final boolean skipEmptyDocs;
  // Base URL for source links, e.g.
  // "https://github.com/org/repo/blob/master/module/src/main/java/".
  // Null when source links are disabled.
  @Nullable
  private final String sourceBase;

  /**
   * Creates a new renderer.
   *
   * @param trees the {@link DocTrees} instance provided by the doclet environment
   * @param elements the {@link Elements} utility from the doclet environment
   * @param includePrivate {@code true} to include private and package-private members
   * @param skipEmptyDocs {@code true} to omit members that have no Javadoc comment
   * @param allTypes all top-level types that will be rendered; used to resolve
   *     {@code {@link}} references to relative Markdown links
   * @param sourceBase optional URL prefix for source links (e.g.
   *     {@code "https://github.com/org/repo/blob/master/module/src/main/java/"});
   *     pass {@code null} to disable source links
   */
  public MarkdownRenderer(DocTrees trees, Elements elements,
      boolean includePrivate, boolean skipEmptyDocs, List<TypeElement> allTypes,
      @Nullable String sourceBase) {
    this.trees = trees;
    this.elements = elements;
    this.includePrivate = includePrivate;
    this.skipEmptyDocs = skipEmptyDocs;
    this.sourceBase = sourceBase;
    Set<String> knownNames = allTypes.stream()
        .map(t -> t.getQualifiedName().toString())
        .collect(Collectors.toSet());
    this.inline = new InlineTagRenderer(elements, knownNames, includePrivate);
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
    appendNestedTypes(type, sb);

    // Normalize trailing spaces, strip extra blank lines before closing fences,
    // collapse 3+ blank lines to one, and end with exactly one newline.
    return sb.toString()
        .replaceAll("[ \t]+\n", "\n")
        .replaceAll("\n+```\n", "\n```\n")
        .replaceAll("\n{3,}", "\n\n")
        .stripTrailing() + "\n";
  }

  /**
   * Writes the YAML frontmatter block.
   *
   * @param type the type whose simple name is used for {@code title} and {@code sidebar_label}
   * @param sb the buffer to append to
   */
  private void appendFrontmatter(TypeElement type, StringBuilder sb) {
    String simpleName = type.getSimpleName().toString();
    sb.append("---\n");
    sb.append("title: ").append(simpleName).append("\n");
    sb.append("sidebar_label: ").append(simpleName).append("\n");
    // Include h3 member headings in the per-page table of contents.
    sb.append("toc_max_heading_level: 3\n");
    // Suppress Docusaurus's automatic H1 so the plugin's dm-class-header div
    // (which contains the H1 and the inline View Source button) is the only heading.
    sb.append("hide_title: true\n");
    sb.append("---\n\n");
  }

  /**
   * Writes the class header: the title row followed by the shared type body.
   *
   * <p>When a {@code sourceBase} URL is configured, the H1 and "View source" button are
   * wrapped in a {@code <div class="dm-class-header">} flex row so they share the same
   * visual line. Without a {@code sourceBase}, a plain Markdown H1 is emitted instead.
   * The kind label, qualified name, declaration, deprecation notice, description, and
   * meta-tags are then written by {@link #appendTypeMeta(TypeElement, DocCommentTree, StringBuilder)}.
   *
   * @param type the type being documented
   * @param classDoc the type's parsed doc comment, or {@code null} when it has none
   * @param sb the buffer to append to
   */
  private void appendClassHeader(TypeElement type, DocCommentTree classDoc, StringBuilder sb) {
    String simpleName = type.getSimpleName().toString();

    if (sourceBase != null) {
      String path = type.getQualifiedName().toString().replace('.', '/') + ".java";
      String url = sourceBase + path;
      sb.append("<div class=\"dm-class-header\"><h1>").append(simpleName).append("</h1>")
          .append("<a class=\"docletmd-source-link\" href=\"").append(url)
          .append("\" target=\"_blank\">View source</a></div>\n\n");
    } else {
      sb.append("# ").append(simpleName).append("\n\n");
    }

    appendTypeMeta(type, classDoc, sb);
  }

  /**
   * Writes the body shared by the top-level class header and every nested-type section:
   * the kind label, qualified name, colorized declaration, optional deprecation
   * admonition, description, and {@code @since}/{@code @see} meta-tags.
   *
   * <p>The caller is responsible for writing the heading (an H1 for the top-level type,
   * an H2 for a nested type) before calling this method.
   *
   * @param type the type whose body is being written
   * @param doc the type's parsed doc comment, or {@code null} when it has none
   * @param sb the buffer to append to
   */
  private void appendTypeMeta(TypeElement type, @Nullable DocCommentTree doc, StringBuilder sb) {
    String kindLabel = kindLabel(type.getKind());
    if (!kindLabel.isEmpty()) {
      sb.append("*`").append(kindLabel).append("`*\n\n");
    }

    sb.append("`").append(type.getQualifiedName()).append("`\n\n");

    // Colorized type declaration (public class Foo extends Bar implements Baz).
    sb.append("<div class=\"dm-decl\"><span class=\"dm-code\">")
        .append(buildDeclarationHtml(type))
        .append("</span></div>\n\n");

    if (isDeprecated(type, doc)) {
      appendDeprecatedAdmonition(doc, sb);
    }

    if (doc != null) {
      String desc = renderBody(doc);
      if (!desc.isBlank()) {
        sb.append(desc).append("\n\n");
      }
    }

    appendMetaTags(doc, sb);
  }

  /**
   * Writes a Docusaurus {@code :::caution} block for a deprecated element.
   *
   * @param doc the element's doc comment, used for the {@code @deprecated} message;
   *     may be {@code null}
   * @param sb the buffer to append to
   */
  private void appendDeprecatedAdmonition(@Nullable DocCommentTree doc, StringBuilder sb) {
    sb.append(":::caution Deprecated\n\n");
    if (doc != null) {
      String msg = deprecatedMessage(doc);
      if (!msg.isBlank()) {
        sb.append(msg).append("\n\n");
      }
    }
    sb.append(":::\n\n");
  }

  /**
   * Writes the {@code @since} and {@code @see} meta-tags as bold labels.
   *
   * @param doc the doc comment to read block tags from; may be {@code null}, in which
   *     case nothing is written
   * @param sb the buffer to append to
   */
  private void appendMetaTags(@Nullable DocCommentTree doc, StringBuilder sb) {
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

  /**
   * Writes the Constructors section.
   *
   * <p>For record types, the canonical constructor inherits {@code @param} tags from the
   * class-level doc when it has no explicit Javadoc of its own.
   *
   * @param type the type whose constructors are rendered
   * @param sb the buffer to append to
   */
  private void appendConstructors(TypeElement type, StringBuilder sb) {
    List<ExecutableElement> ctors = ElementFilter.constructorsIn(type.getEnclosedElements());
    boolean isRecord = type.getKind() == ElementKind.RECORD;
    DocCommentTree typeDoc = isRecord ? trees.getDocCommentTree(type) : null;

    List<ExecutableElement> visible = ctors.stream()
        .filter(this::isVisible)
        .filter(e -> !skipEmptyDocs || trees.getDocCommentTree(e) != null
            || (isRecord && typeDoc != null && hasParamTags(typeDoc)))
        .toList();

    if (visible.isEmpty()) {
      return;
    }
    sb.append("## Constructors\n\n");
    for (ExecutableElement ctor : visible) {
      // For record canonical constructors with no explicit doc, supply the record's
      // class-level doc as a fallback source for @param descriptions.
      DocCommentTree paramFallback = (isRecord && trees.getDocCommentTree(ctor) == null)
          ? typeDoc : null;
      appendExecutable(ctor, type.getSimpleName().toString(), paramFallback, sb);
    }
  }

  /**
   * Returns whether the given doc comment has at least one {@code @param} tag.
   *
   * @param doc the doc comment to inspect; must not be {@code null}
   * @return {@code true} if a non-type-parameter {@code @param} tag is present
   */
  private static boolean hasParamTags(DocCommentTree doc) {
    return doc.getBlockTags().stream().anyMatch(t -> t instanceof ParamTree pt && !pt.isTypeParameter());
  }

  /**
   * Writes the Fields section, omitting it entirely when no field is visible.
   *
   * @param type the type whose fields are rendered
   * @param sb the buffer to append to
   */
  private void appendFields(TypeElement type, StringBuilder sb) {
    List<VariableElement> visible = visibleMembers(ElementFilter.fieldsIn(type.getEnclosedElements()));
    if (visible.isEmpty()) {
      return;
    }
    sb.append("## Fields\n\n");
    for (VariableElement field : visible) {
      appendField(field, sb);
    }
  }

  /**
   * Writes the Methods section, omitting it entirely when no method is visible.
   *
   * @param type the type whose methods are rendered
   * @param sb the buffer to append to
   */
  private void appendMethods(TypeElement type, StringBuilder sb) {
    List<ExecutableElement> visible = visibleMembers(ElementFilter.methodsIn(type.getEnclosedElements()));
    if (visible.isEmpty()) {
      return;
    }
    sb.append("## Methods\n\n");
    for (ExecutableElement method : visible) {
      appendExecutable(method, null, null, sb);
    }
  }

  /**
   * Filters a member list down to the members that should appear in the output, honoring
   * both the visibility setting and the {@code skipEmptyDocs} flag.
   *
   * @param members the members to filter; must not be {@code null}
   * @param <T> the kind of member being filtered (for example {@link VariableElement}
   *     or {@link ExecutableElement})
   * @return a new list containing only the members that pass the filters
   */
  private <T extends Element> List<T> visibleMembers(List<T> members) {
    return members.stream()
        .filter(this::isVisible)
        .filter(e -> !skipEmptyDocs || trees.getDocCommentTree(e) != null)
        .toList();
  }

  /**
   * Writes a single field entry: its kind marker, signature heading, optional deprecation
   * notice, description, and meta-tags, followed by a horizontal rule.
   *
   * @param field the field to render
   * @param sb the buffer to append to
   */
  private void appendField(VariableElement field, StringBuilder sb) {
    DocCommentTree doc = trees.getDocCommentTree(field);
    sb.append(fieldMarker(field)).append("\n");
    sb.append("### `").append(Signatures.fieldSignature(field, elements)).append("`\n\n");

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

  /**
   * Writes a single constructor or method entry: its kind marker, signature heading,
   * optional deprecation notice, description, parameter table, return line, throws table,
   * and meta-tags, followed by a horizontal rule.
   *
   * @param exec the constructor or method to render
   * @param ctorName the simple class name to use when rendering a constructor; {@code null}
   *     when rendering a regular method
   * @param paramFallback a doc tree to use for {@code @param} lookup when the executable's
   *     own doc has none, used for record canonical constructors whose {@code @param} tags
   *     live in the record's class-level doc; may be {@code null}
   * @param sb the buffer to append to
   */
  private void appendExecutable(ExecutableElement exec, @Nullable String ctorName,
      @Nullable DocCommentTree paramFallback, StringBuilder sb) {
    DocCommentTree doc = trees.getDocCommentTree(exec);
    sb.append(execMarker(exec, ctorName != null)).append("\n");
    sb.append("### `").append(Signatures.methodSignature(exec, ctorName)).append("`\n\n");

    if (isDeprecated(exec, doc)) {
      appendDeprecatedAdmonition(doc, sb);
    }

    if (doc != null) {
      String desc = renderExecDescription(exec, doc);
      if (!desc.isBlank()) {
        sb.append(desc).append("\n\n");
      }
    }

    appendParamTable(exec, doc, paramFallback, sb);
    appendReturnsLine(exec, doc, sb);
    appendThrowsTable(exec, doc, sb);
    appendMetaTags(doc, sb);
    sb.append("---\n\n");
  }

  /**
   * Writes the parameters table when the doc comment (or its fallback) has {@code @param} tags.
   *
   * <p>Falls back to {@code paramFallback} when {@code doc} has no {@code @param} tags, which
   * is how a record canonical constructor reuses the {@code @param} tags from the record's
   * class-level doc.
   *
   * @param exec the executable being documented
   * @param doc the executable's own doc comment; may be {@code null}
   * @param paramFallback a doc comment to read {@code @param} tags from when {@code doc} has
   *     none; may be {@code null}
   * @param sb the buffer to append to
   */
  private void appendParamTable(ExecutableElement exec, @Nullable DocCommentTree doc,
      @Nullable DocCommentTree paramFallback, StringBuilder sb) {
    // Determine which doc tree to pull @param tags from.
    DocCommentTree source = doc;
    if (source == null || !hasParamTags(source)) {
      if (paramFallback != null && hasParamTags(paramFallback)) {
        source = paramFallback;
      }
    }
    if (source == null) {
      return;
    }
    List<ParamTree> params = source.getBlockTags().stream()
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
      String desc = MdEscaper.escapeTableCell(renderParamDescription(exec, p));
      sb.append("| `").append(name).append("` | ").append(desc).append(" |\n");
    }
    sb.append("\n");
  }

  /**
   * Writes the {@code @return} description when the doc comment has one.
   *
   * @param exec the executable being documented
   * @param doc the executable's doc comment; may be {@code null}
   * @param sb the buffer to append to
   */
  private void appendReturnsLine(ExecutableElement exec, @Nullable DocCommentTree doc, StringBuilder sb) {
    if (doc == null) {
      return;
    }
    doc.getBlockTags().stream()
        .filter(t -> t instanceof ReturnTree)
        .map(t -> (ReturnTree) t)
        .findFirst()
        .ifPresent(r -> {
          String desc = renderReturnDescription(exec, r);
          if (!desc.isBlank()) {
            sb.append("**Returns:** ").append(desc).append("\n\n");
          }
        });
  }

  /**
   * Writes the throws table when the doc comment has {@code @throws} or {@code @exception} tags.
   *
   * @param exec the executable being documented
   * @param doc the executable's doc comment; may be {@code null}
   * @param sb the buffer to append to
   */
  private void appendThrowsTable(ExecutableElement exec, @Nullable DocCommentTree doc, StringBuilder sb) {
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
      String desc = MdEscaper.escapeTableCell(renderThrowsDescription(exec, t));
      sb.append("| `").append(type).append("` | ").append(desc).append(" |\n");
    }
    sb.append("\n");
  }

  /**
   * Renders the first sentence and body of a doc comment to a single Markdown string.
   *
   * @param doc the doc comment to render; must not be {@code null}
   * @return the combined first sentence and body as Markdown, never {@code null}
   */
  private String renderBody(DocCommentTree doc) {
    String first = inline.render(doc.getFirstSentence());
    String body = inline.render(doc.getBody());
    if (first.isBlank()) return body.strip();
    if (body.isBlank()) return first.strip();
    // The Javadoc parser consumes the whitespace token that separates the first sentence
    // from the body. Re-insert a single space so the text does not run together.
    return (first + " " + body).strip();
  }

  // {@inheritDoc} resolution.
  //
  // The Javadoc tool does not expand {@inheritDoc} on behalf of a doclet, so the doclet
  // must find the overridden method and copy the matching documentation itself. The
  // helpers below locate the nearest overridden method and substitute its text wherever
  // {@inheritDoc} appears in the description, a @param, the @return, or a @throws.
  // Resolution recurses through the override chain so a tag can be inherited across
  // several levels.

  /**
   * Renders an executable's description, expanding {@code {@inheritDoc}} in the body with
   * the overridden method's description.
   *
   * <p>Falls back to the plain body when no {@code {@inheritDoc}} tag is present.
   *
   * @param exec the executable being documented
   * @param doc the executable's doc comment; must not be {@code null}
   * @return the rendered description, never {@code null}
   */
  private String renderExecDescription(ExecutableElement exec, DocCommentTree doc) {
    if (!containsInheritDoc(doc.getFirstSentence()) && !containsInheritDoc(doc.getBody())) {
      return renderBody(doc);
    }
    String inherited = inheritedDescription(findOverridden(exec));
    String first = renderInlineWithInherit(doc.getFirstSentence(), inherited);
    String body = renderInlineWithInherit(doc.getBody(), inherited);
    if (first.isBlank()) return body.strip();
    if (body.isBlank()) return first.strip();
    return (first + " " + body).strip();
  }

  /**
   * Returns the rendered description of an overridden method, recursing when that method
   * itself uses {@code {@inheritDoc}}.
   *
   * @param parent the overridden method, or {@code null} when none was found
   * @return the inherited description, or an empty string when {@code parent} is {@code null}
   *     or has no doc comment
   */
  private String inheritedDescription(@Nullable ExecutableElement parent) {
    if (parent == null) return "";
    DocCommentTree pdoc = trees.getDocCommentTree(parent);
    return pdoc != null ? renderExecDescription(parent, pdoc) : "";
  }

  /**
   * Renders a single {@code @param} description, expanding {@code {@inheritDoc}} with the
   * overridden method's {@code @param} of the same name.
   *
   * @param exec the executable being documented
   * @param param the {@code @param} tag to render
   * @return the rendered parameter description, never {@code null}
   */
  private String renderParamDescription(ExecutableElement exec, ParamTree param) {
    if (!containsInheritDoc(param.getDescription())) {
      return inline.render(param.getDescription()).strip();
    }
    String inherited = inheritedParam(findOverridden(exec), param.getName().toString());
    return renderInlineWithInherit(param.getDescription(), inherited).strip();
  }

  /**
   * Returns the rendered {@code @param} description of the overridden method whose parameter
   * name matches, recursing through the override chain.
   *
   * @param parent the overridden method, or {@code null} when none was found
   * @param name the parameter name to match
   * @return the inherited parameter description, or an empty string when not found
   */
  private String inheritedParam(@Nullable ExecutableElement parent, String name) {
    if (parent == null) return "";
    DocCommentTree pdoc = trees.getDocCommentTree(parent);
    if (pdoc == null) return "";
    for (DocTree tag : pdoc.getBlockTags()) {
      if (tag instanceof ParamTree pt && !pt.isTypeParameter()
          && pt.getName().toString().equals(name)) {
        if (!containsInheritDoc(pt.getDescription())) {
          return inline.render(pt.getDescription()).strip();
        }
        return renderInlineWithInherit(pt.getDescription(),
            inheritedParam(findOverridden(parent), name)).strip();
      }
    }
    return "";
  }

  /**
   * Renders an {@code @return} description, expanding {@code {@inheritDoc}} with the
   * overridden method's return text.
   *
   * @param exec the executable being documented
   * @param ret the {@code @return} tag to render
   * @return the rendered return description, never {@code null}
   */
  private String renderReturnDescription(ExecutableElement exec, ReturnTree ret) {
    if (!containsInheritDoc(ret.getDescription())) {
      return inline.render(ret.getDescription()).strip();
    }
    return renderInlineWithInherit(ret.getDescription(),
        inheritedReturn(findOverridden(exec))).strip();
  }

  /**
   * Returns the rendered {@code @return} description of the overridden method, recursing
   * through the override chain.
   *
   * @param parent the overridden method, or {@code null} when none was found
   * @return the inherited return description, or an empty string when not found
   */
  private String inheritedReturn(@Nullable ExecutableElement parent) {
    if (parent == null) return "";
    DocCommentTree pdoc = trees.getDocCommentTree(parent);
    if (pdoc == null) return "";
    for (DocTree tag : pdoc.getBlockTags()) {
      if (tag instanceof ReturnTree rt) {
        if (!containsInheritDoc(rt.getDescription())) {
          return inline.render(rt.getDescription()).strip();
        }
        return renderInlineWithInherit(rt.getDescription(),
            inheritedReturn(findOverridden(parent))).strip();
      }
    }
    return "";
  }

  /**
   * Renders a {@code @throws} description, expanding {@code {@inheritDoc}} with the
   * overridden method's {@code @throws} of the same exception type.
   *
   * @param exec the executable being documented
   * @param t the {@code @throws} tag to render
   * @return the rendered throws description, never {@code null}
   */
  private String renderThrowsDescription(ExecutableElement exec, ThrowsTree t) {
    if (!containsInheritDoc(t.getDescription())) {
      return inline.render(t.getDescription()).strip();
    }
    String name = t.getExceptionName().getSignature();
    return renderInlineWithInherit(t.getDescription(),
        inheritedThrows(findOverridden(exec), name)).strip();
  }

  /**
   * Returns the rendered {@code @throws} description of the overridden method whose exception
   * name matches, recursing through the override chain.
   *
   * @param parent the overridden method, or {@code null} when none was found
   * @param exceptionName the exception type signature to match
   * @return the inherited throws description, or an empty string when not found
   */
  private String inheritedThrows(@Nullable ExecutableElement parent, String exceptionName) {
    if (parent == null) return "";
    DocCommentTree pdoc = trees.getDocCommentTree(parent);
    if (pdoc == null) return "";
    for (DocTree tag : pdoc.getBlockTags()) {
      if (tag instanceof ThrowsTree tt
          && tt.getExceptionName().getSignature().equals(exceptionName)) {
        if (!containsInheritDoc(tt.getDescription())) {
          return inline.render(tt.getDescription()).strip();
        }
        return renderInlineWithInherit(tt.getDescription(),
            inheritedThrows(findOverridden(parent), exceptionName)).strip();
      }
    }
    return "";
  }

  /**
   * Renders inline nodes, replacing each {@code {@inheritDoc}} node with the supplied
   * inherited text.
   *
   * @param nodes the inline nodes to render
   * @param inherited the text to substitute for each {@code {@inheritDoc}} node
   * @return the rendered Markdown fragment, never {@code null}
   */
  private String renderInlineWithInherit(List<? extends DocTree> nodes, String inherited) {
    StringBuilder sb = new StringBuilder();
    for (DocTree node : nodes) {
      if (node.getKind() == DocTree.Kind.INHERIT_DOC) {
        sb.append(inherited);
      } else {
        sb.append(inline.renderNode(node));
      }
    }
    return sb.toString();
  }

  /**
   * Returns whether any node in the list is an {@code {@inheritDoc}} inline tag.
   *
   * @param nodes the nodes to inspect
   * @return {@code true} if at least one node is an {@code {@inheritDoc}} tag
   */
  private static boolean containsInheritDoc(List<? extends DocTree> nodes) {
    return nodes.stream().anyMatch(n -> n.getKind() == DocTree.Kind.INHERIT_DOC);
  }

  /**
   * Finds the nearest method that the given method overrides, searching superclasses first
   * and then implemented interfaces (breadth-first).
   *
   * @param method the method whose overridden counterpart should be found
   * @return the overridden method, or {@code null} when the method overrides nothing (for
   *     example a method declared only on this type)
   */
  @Nullable
  private ExecutableElement findOverridden(ExecutableElement method) {
    Element enclosing = method.getEnclosingElement();
    if (!(enclosing instanceof TypeElement origin)) return null;
    Deque<TypeElement> queue = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    enqueueSupertypes(origin, queue);
    while (!queue.isEmpty()) {
      TypeElement t = queue.poll();
      if (!visited.add(t.getQualifiedName().toString())) continue;
      for (ExecutableElement candidate : ElementFilter.methodsIn(t.getEnclosedElements())) {
        if (elements.overrides(method, candidate, origin)) {
          return candidate;
        }
      }
      enqueueSupertypes(t, queue);
    }
    return null;
  }

  /**
   * Adds the direct superclass and implemented interfaces of a type to the search queue.
   *
   * @param type the type whose supertypes are enqueued
   * @param queue the breadth-first search queue to add to
   */
  private static void enqueueSupertypes(TypeElement type, Deque<TypeElement> queue) {
    TypeMirror superclass = type.getSuperclass();
    if (superclass instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
      queue.add(te);
    }
    for (TypeMirror iface : type.getInterfaces()) {
      if (iface instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
        queue.add(te);
      }
    }
  }

  /**
   * Returns the text of the {@code @deprecated} block tag.
   *
   * @param doc the doc comment to read; must not be {@code null}
   * @return the rendered {@code @deprecated} message, or an empty string when absent
   */
  private String deprecatedMessage(DocCommentTree doc) {
    return doc.getBlockTags().stream()
        .filter(t -> t instanceof DeprecatedTree)
        .map(t -> (DeprecatedTree) t)
        .findFirst()
        .map(d -> inline.render(d.getBody()).strip())
        .orElse("");
  }

  /**
   * Returns whether an element is deprecated, by either the {@code @Deprecated} annotation
   * or a {@code @deprecated} block tag.
   *
   * @param element the element to inspect
   * @param doc the element's doc comment; may be {@code null}
   * @return {@code true} when the element is deprecated
   */
  private boolean isDeprecated(Element element, @Nullable DocCommentTree doc) {
    boolean annotated = elements.isDeprecated(element);
    boolean tagged = doc != null && doc.getBlockTags().stream()
        .anyMatch(t -> t instanceof DeprecatedTree);
    return annotated || tagged;
  }

  /**
   * Returns whether an element should be included based on the visibility setting.
   *
   * @param e the element to test
   * @return {@code true} when {@code includePrivate} is set, or the element is
   *     {@code public} or {@code protected}
   */
  private boolean isVisible(Element e) {
    Set<Modifier> mods = e.getModifiers();
    if (includePrivate) {
      return true;
    }
    return mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
  }

  /**
   * Returns the HTML comment marker for a field.
   *
   * <p>The marker is invisible in all standard Markdown renderers and is read by a
   * Docusaurus remark plugin for per-member color coding.
   *
   * @param field the field whose marker is produced
   * @return the {@code <!-- docletmd:field... -->} marker string
   */
  private static String fieldMarker(VariableElement field) {
    Set<Modifier> mods = field.getModifiers();
    boolean isStatic = mods.contains(Modifier.STATIC);
    boolean isConstant = isStatic && mods.contains(Modifier.FINAL)
        && field.getConstantValue() != null;
    if (isConstant) return "<!-- docletmd:field:constant -->";
    if (isStatic) return "<!-- docletmd:field:static -->";
    return "<!-- docletmd:field -->";
  }

  /**
   * Returns the HTML comment marker for a constructor or method.
   *
   * @param exec the constructor or method whose marker is produced
   * @param isCtor {@code true} when {@code exec} is a constructor
   * @return the {@code <!-- docletmd:constructor -->} or {@code <!-- docletmd:method... -->}
   *     marker string
   */
  private static String execMarker(ExecutableElement exec, boolean isCtor) {
    if (isCtor) return "<!-- docletmd:constructor -->";
    return exec.getModifiers().contains(Modifier.STATIC)
        ? "<!-- docletmd:method:static -->"
        : "<!-- docletmd:method -->";
  }

  /**
   * Writes inline H2 sections for all public or protected nested types.
   *
   * @param outerType the enclosing type whose nested types are rendered
   * @param sb the buffer to append to
   */
  private void appendNestedTypes(TypeElement outerType, StringBuilder sb) {
    List<TypeElement> nested = ElementFilter.typesIn(outerType.getEnclosedElements()).stream()
        .filter(this::isVisible)
        .toList();
    for (TypeElement nestedType : nested) {
      appendNestedTypeSection(nestedType, sb);
    }
  }

  /**
   * Renders a nested type as a self-contained H2 block inside the parent page, including
   * its own constructors, fields, and methods.
   *
   * @param type the nested type to render
   * @param sb the buffer to append to
   */
  private void appendNestedTypeSection(TypeElement type, StringBuilder sb) {
    DocCommentTree doc = trees.getDocCommentTree(type);
    sb.append("## ").append(type.getSimpleName()).append("\n\n");

    appendTypeMeta(type, doc, sb);
    appendConstructors(type, sb);
    appendFields(type, sb);
    appendMethods(type, sb);
  }

  /**
   * Returns the human-readable kind label for a type, for example {@code "class"} or
   * {@code "record"}.
   *
   * @param kind the element kind to label
   * @return the label, or an empty string for kinds that have no label
   */
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

  /**
   * Builds the colorized HTML string for a type declaration line.
   *
   * <p>For example, {@code public class FlixelSprite extends FlixelObject} becomes a run of
   * {@code <span>} elements with the {@code dm-kw}, {@code dm-fn}, and {@code dm-type} classes
   * that a stylesheet can color.
   *
   * @param type the type whose declaration is rendered
   * @return the HTML declaration string, never {@code null}
   */
  private String buildDeclarationHtml(TypeElement type) {
    StringBuilder out = new StringBuilder();
    ElementKind kind = type.getKind();

    // Modifiers: omit "abstract" for interfaces (implied) and "static" for inner enums.
    for (Modifier m : type.getModifiers().stream().sorted().toList()) {
      if (kind == ElementKind.INTERFACE && m == Modifier.ABSTRACT) continue;
      out.append(kwSpan(m.toString())).append(" ");
    }

    // Type keyword.
    String kindKw = switch (kind) {
      case CLASS -> "class";
      case INTERFACE -> "interface";
      case ENUM -> "enum";
      case RECORD -> "record";
      case ANNOTATION_TYPE -> "@interface";
      default -> "class";
    };
    // @interface: "@" passes through as plain text, "interface" is the keyword span.
    if (kindKw.equals("@interface")) {
      out.append("@").append(kwSpan("interface")).append(" ");
    } else {
      out.append(kwSpan(kindKw)).append(" ");
    }

    // Declared name.
    out.append(fnSpan(type.getSimpleName().toString()));

    // Type parameters, e.g. <T extends FlixelBasic>.
    List<? extends TypeParameterElement> typeParams = type.getTypeParameters();
    if (!typeParams.isEmpty()) {
      out.append("&lt;");
      for (int i = 0; i < typeParams.size(); i++) {
        if (i > 0) out.append(", ");
        out.append(typeSpan(typeParams.get(i).getSimpleName().toString()));
        List<String> simplified = typeParams.get(i).getBounds().stream()
            .map(b -> Signatures.simplifyType(b.toString()))
            .filter(b -> !b.equals("Object"))
            .toList();
        if (!simplified.isEmpty()) {
          out.append(" ").append(kwSpan("extends")).append(" ");
          for (int j = 0; j < simplified.size(); j++) {
            if (j > 0) out.append(" &amp; ");
            out.append(typeSpan(simplified.get(j)));
          }
        }
      }
      out.append("&gt;");
    }

    // Record components, e.g. (FlixelLogLevel level, String tag).
    if (kind == ElementKind.RECORD) {
      List<? extends RecordComponentElement> comps =
          ElementFilter.recordComponentsIn(type.getEnclosedElements());
      out.append("(");
      for (int i = 0; i < comps.size(); i++) {
        if (i > 0) out.append(", ");
        out.append(typeSpan(Signatures.simplifyType(comps.get(i).asType().toString())));
        out.append(" ");
        out.append(paramSpan(comps.get(i).getSimpleName().toString()));
      }
      out.append(")");
    }

    // Superclass (skip Object, Record, and Enum<T> since those are implicit).
    TypeMirror superclass = type.getSuperclass();
    if (superclass != null && superclass.getKind() != TypeKind.NONE) {
      String superName = Signatures.simplifyType(superclass.toString());
      boolean implicit = superName.equals("Object")
          || superName.equals("Record")
          || superName.startsWith("Enum<");
      if (!implicit) {
        out.append(" ").append(kwSpan("extends")).append(" ").append(typeSpan(superName));
      }
    }

    // Implemented/extended interfaces.
    List<? extends TypeMirror> ifaces = type.getInterfaces();
    if (!ifaces.isEmpty()) {
      String ifaceKw = (kind == ElementKind.INTERFACE) ? "extends" : "implements";
      out.append(" ").append(kwSpan(ifaceKw)).append(" ");
      List<String> ifaceNames = ifaces.stream()
          .map(i -> Signatures.simplifyType(i.toString()))
          .toList();
      for (int i = 0; i < ifaceNames.size(); i++) {
        if (i > 0) out.append(", ");
        out.append(typeSpan(ifaceNames.get(i)));
      }
    }

    return out.toString();
  }

  /**
   * Wraps text in a keyword span ({@code dm-kw}).
   *
   * @param text the keyword text
   * @return the wrapped HTML span
   */
  private static String kwSpan(String text) {
    return "<span class=\"dm-kw\">" + text + "</span>";
  }

  /**
   * Wraps text in a declared-name span ({@code dm-fn}).
   *
   * @param text the declared name text
   * @return the wrapped HTML span
   */
  private static String fnSpan(String text) {
    return "<span class=\"dm-fn\">" + text + "</span>";
  }

  /**
   * Wraps text in a type span ({@code dm-type}), escaping the HTML metacharacters
   * {@code &}, {@code <}, and {@code >} so generic type arguments render correctly.
   *
   * @param text the type text
   * @return the wrapped, escaped HTML span
   */
  private static String typeSpan(String text) {
    String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return "<span class=\"dm-type\">" + safe + "</span>";
  }

  /**
   * Wraps text in a parameter span ({@code dm-param}).
   *
   * @param text the parameter name text
   * @return the wrapped HTML span
   */
  private static String paramSpan(String text) {
    return "<span class=\"dm-param\">" + text + "</span>";
  }
}
