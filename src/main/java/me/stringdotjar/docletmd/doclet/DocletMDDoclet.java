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
package me.stringdotjar.docletmd.doclet;

import com.sun.source.util.DocTrees;
import me.stringdotjar.docletmd.render.MarkdownRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/**
 * A custom Javadoc doclet that generates Docusaurus-ready Markdown files.
 *
 * <p>This doclet is invoked by the {@code DocumentationTool} inside
 * {@code DocletMDTask}. It accepts the following options in addition to the
 * standard Javadoc options:
 *
 * <ul>
 *   <li>{@code -outputDir <path>} -- directory where {@code .md} files are written</li>
 *   <li>{@code -includePrivate} -- flag that includes private and package-private members</li>
 *   <li>{@code -skipEmptyDocs} -- flag that skips members with no Javadoc comment</li>
 * </ul>
 *
 * <p>The doclet writes one {@code .md} file per top-level type element, organized
 * under the output directory in the same package structure as the source.
 * For example, {@code com.example.Calculator} produces
 * {@code <outputDir>/com/example/Calculator.md}.
 */
public final class DocletMDDoclet implements Doclet {

  /** Creates a new doclet instance; called reflectively by the Javadoc tool. */
  public DocletMDDoclet() {}

  private Reporter reporter;
  private Path outputDir;
  private boolean includePrivate;
  private boolean skipEmptyDocs;

  @Override
  public void init(Locale locale, Reporter reporter) {
    this.reporter = reporter;
  }

  @Override
  public String getName() {
    return "DocletMD";
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latest();
  }

  @Override
  public Set<? extends Option> getSupportedOptions() {
    return Set.of(
        opt("-outputDir", "<path>", "Directory where .md files are written", 1,
            args -> this.outputDir = Path.of(args.get(0))),
        opt("-includePrivate", "", "Include private and package-private members", 0,
            args -> this.includePrivate = true),
        opt("-skipEmptyDocs", "", "Skip members that have no Javadoc comment", 0,
            args -> this.skipEmptyDocs = true)
    );
  }

  /**
   * Entry point called by the Javadoc tool after options are processed.
   *
   * <p>Iterates over every included top-level type element and delegates rendering
   * to {@link MarkdownRenderer}. Returns {@code false} if any file cannot be written.
   *
   * @param env the doclet environment providing element and doc-tree access
   * @return {@code true} on success, {@code false} if any error occurred
   */
  @Override
  public boolean run(DocletEnvironment env) {
    if (outputDir == null) {
      reporter.print(Diagnostic.Kind.ERROR, "DocletMD: -outputDir option is required");
      return false;
    }

    DocTrees trees = env.getDocTrees();
    Elements elems = env.getElementUtils();
    MarkdownRenderer renderer = new MarkdownRenderer(trees, elems, includePrivate, skipEmptyDocs);

    List<TypeElement> types = collectTopLevelTypes(env);

    for (TypeElement type : types) {
      try {
        String markdown = renderer.render(type);
        writeFile(type, markdown);
      } catch (IOException e) {
        reporter.print(Diagnostic.Kind.ERROR,
            "DocletMD: failed to write " + type.getQualifiedName() + ": " + e.getMessage());
        return false;
      }
    }
    return true;
  }

  // Collects all top-level, included type elements from the doclet environment.
  // Inner/nested classes are excluded; they appear inside their enclosing class's file.
  private List<TypeElement> collectTopLevelTypes(DocletEnvironment env) {
    List<TypeElement> result = new ArrayList<>();
    for (Element e : env.getIncludedElements()) {
      if (!(e instanceof TypeElement te)) {
        continue;
      }
      // Only top-level types (enclosed directly by a package, not another type)
      if (!(te.getEnclosingElement() instanceof PackageElement)) {
        continue;
      }
      // Respect visibility setting
      if (!includePrivate) {
        ElementKind kind = te.getKind();
        Set<Modifier> mods = te.getModifiers();
        boolean accessible = mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
        if (!accessible && kind != ElementKind.ENUM && kind != ElementKind.INTERFACE) {
          continue;
        }
      }
      result.add(te);
    }
    return result;
  }

  // Writes the rendered Markdown to a file under outputDir, mirroring
  // the package structure. For example, com.example.Foo -> com/example/Foo.md.
  private void writeFile(TypeElement type, String markdown) throws IOException {
    String qualName = type.getQualifiedName().toString();
    String relativePath = qualName.replace('.', '/') + ".md";
    Path outFile = outputDir.resolve(relativePath);
    Files.createDirectories(outFile.getParent());
    Files.writeString(outFile, markdown, StandardCharsets.UTF_8);
  }

  // Builds a Doclet.Option that calls the given handler when processed.
  private static Option opt(String name, String params, String desc, int argCount,
      Consumer<List<String>> handler) {
    return new Option() {
      @Override public int getArgumentCount() { return argCount; }
      @Override public String getDescription() { return desc; }
      @Override public Kind getKind() { return Kind.STANDARD; }
      @Override public List<String> getNames() { return List.of(name); }
      @Override public String getParameters() { return params; }
      @Override public boolean process(String option, List<String> arguments) {
        handler.accept(arguments);
        return true;
      }
    };
  }
}
