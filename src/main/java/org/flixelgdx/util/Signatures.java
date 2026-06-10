package org.flixelgdx.util;

import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the human-readable signature and type strings shown in generated Markdown.
 *
 * <p>The doclet renders a member heading in one place ({@code MarkdownRenderer}) and
 * computes the matching page anchor in another ({@code InlineTagRenderer}). Both must
 * produce a byte-for-byte identical signature, otherwise a cross-reference link would
 * point at an anchor that does not exist. Centralizing the formatting here is what
 * guarantees the two callers can never drift apart.
 *
 * <p>Example usage:
 * <pre>{@code
 * String heading = Signatures.methodSignature(addMethod, null);
 * // heading => "public int add(int a, int b)"
 * }</pre>
 */
public final class Signatures {

  private Signatures() {}

  /**
   * Strips package and enclosing-class prefixes from a type name, then removes any
   * type-use annotations, so that signatures stay short and readable.
   *
   * <p>For example, {@code "java.util.List<java.lang.String>"} becomes
   * {@code "List<String>"}.
   *
   * @param typeName the fully qualified type string, typically from {@link Object#toString()}
   *     on a {@code TypeMirror}; must not be {@code null}
   * @return the simplified type name with packages and annotations removed, never {@code null}
   */
  public static String simplifyType(String typeName) {
    // The negative lookbehind prevents matching a partial segment inside a word
    // (for example "ig" inside "FlixelAnimateRig" is not a package prefix).
    String noPrefix = typeName.replaceAll(
        "(?<![A-Za-z0-9_$])([A-Za-z][A-Za-z0-9_$]*\\.)+([A-Z])", "$2");
    return noPrefix.replaceAll("@[A-Za-z][A-Za-z0-9_]*(\\([^)]*\\))?\\s*", "").trim();
  }

  /**
   * Returns the element's modifiers as a single space-separated string in canonical
   * Java order, for example {@code "public static final"}.
   *
   * <p>The {@code abstract} modifier is omitted because it is implied by the enclosing
   * interface or class declaration and only adds noise to every member heading.
   * {@link Modifier} declares its constants in the order the Java Language Specification
   * lists them, so simply sorting the set yields the conventional ordering.
   *
   * @param e the element whose modifiers should be formatted; must not be {@code null}
   * @return the formatted modifier prefix, or an empty string when there is nothing to show
   */
  public static String modifierPrefix(Element e) {
    return e.getModifiers().stream()
        .filter(m -> m != Modifier.ABSTRACT)
        .sorted()
        .map(Modifier::toString)
        .collect(Collectors.joining(" "));
  }

  /**
   * Builds the signature string used as the heading for a constructor or method.
   *
   * <p>The format is {@code modifiers returnType name(paramType paramName, ...)}. The
   * return type is omitted for constructors. A trailing array parameter on a varargs
   * method is rendered with {@code ...} instead of {@code []}.
   *
   * @param exec the constructor or method to format; must not be {@code null}
   * @param ctorName the simple class name to use when {@code exec} is a constructor,
   *     because a constructor's own simple name is {@code "<init>"}; pass {@code null}
   *     for a regular method so the method's own name is used
   * @return the formatted signature, for example {@code "public int add(int a, int b)"}
   */
  public static String methodSignature(ExecutableElement exec, @Nullable String ctorName) {
    StringBuilder sig = new StringBuilder();
    boolean isCtor = ctorName != null;

    // Modifiers come first (public static final ...), then the return type, then the name.
    String mods = modifierPrefix(exec);
    if (!mods.isEmpty()) {
      sig.append(mods).append(" ");
    }
    if (!isCtor) {
      sig.append(simplifyType(exec.getReturnType().toString())).append(" ");
    }
    sig.append(isCtor ? ctorName : exec.getSimpleName()).append("(");

    List<? extends VariableElement> params = exec.getParameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        sig.append(", ");
      }
      VariableElement p = params.get(i);
      boolean isLastVararg = exec.isVarArgs() && i == params.size() - 1;
      String typeName = simplifyType(p.asType().toString());
      if (isLastVararg && typeName.endsWith("[]")) {
        // Show the trailing array parameter of a varargs method as "..." instead of "[]".
        typeName = typeName.substring(0, typeName.length() - 2) + "...";
      }
      sig.append(typeName).append(" ").append(p.getSimpleName());
    }
    sig.append(")");
    return sig.toString();
  }

  /**
   * Builds the signature string used as the heading for a field.
   *
   * <p>The format is {@code modifiers type name}, with {@code  = value} appended when the
   * field is a compile-time constant (a {@code static final} primitive or {@code String}).
   *
   * @param field the field to format; must not be {@code null}
   * @param elements the {@link Elements} utility, used to format a constant value exactly
   *     as it would appear in source (quoted strings, {@code 'c'} for characters, and so on);
   *     must not be {@code null}
   * @return the formatted field signature, for example
   *     {@code "public static final int MAX = 10"}
   */
  public static String fieldSignature(VariableElement field, Elements elements) {
    String mods = modifierPrefix(field);
    String sig = (mods.isEmpty() ? "" : mods + " ")
        + simplifyType(field.asType().toString()) + " " + field.getSimpleName();
    // getConstantValue() is non-null only for compile-time constants (primitives, String).
    Object constantVal = field.getConstantValue();
    if (constantVal != null) {
      sig += " = " + elements.getConstantExpression(constantVal);
    }
    return sig;
  }
}
