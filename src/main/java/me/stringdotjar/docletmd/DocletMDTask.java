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

import me.stringdotjar.docletmd.doclet.DocletMDDoclet;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Gradle task that runs the DocletMD doclet over a set of Java source directories
 * and writes one Markdown file per top-level class.
 *
 * <p>This task is registered automatically by {@link DocletMDPlugin} under the name
 * {@code generateDocletMD}. When the {@code java} plugin is also applied, the task
 * is pre-wired to the {@code main} source set and its compile classpath; no extra
 * configuration is usually needed.
 *
 * <p>To run the task:
 * <pre>{@code
 * ./gradlew generateDocletMD
 * }</pre>
 */
public abstract class DocletMDTask extends DefaultTask {

  /** Creates a new task instance; called by the Gradle task factory. */
  public DocletMDTask() {}

  /**
   * The Java source directories to scan for {@code .java} files.
   *
   * <p>When the {@code java} plugin is present, this is automatically set to all
   * source directories of the {@code main} source set.
   *
   * @return the configurable source-directory collection
   */
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getSourceDirs();

  /**
   * The compile classpath used to resolve types referenced in the source files.
   *
   * <p>Without a valid classpath, unresolvable types will appear as fully qualified
   * names. When the {@code java} plugin is present, this is set automatically.
   *
   * @return the optional classpath file collection
   */
  @Classpath
  @Optional
  public abstract ConfigurableFileCollection getClasspath();

  /**
   * Whether to include private and package-private members.
   *
   * @return the configurable boolean property
   */
  @Input
  public abstract Property<Boolean> getIncludePrivate();

  /**
   * Whether to skip members with no Javadoc comment.
   *
   * @return the configurable boolean property
   */
  @Input
  public abstract Property<Boolean> getSkipEmptyDocs();

  /**
   * The directory where generated {@code .md} files are written.
   *
   * @return the output directory property
   */
  @OutputDirectory
  public abstract DirectoryProperty getOutputDir();

  /**
   * Executes the Javadoc documentation tool using {@link DocletMDDoclet}.
   *
   * <p>All {@code .java} files found under {@link #getSourceDirs()} are passed to the
   * tool as compilation units. Configuration values are forwarded as doclet options.
   *
   * @throws GradleException if no documentation tool is available, or if the doclet fails
   * @throws IOException if source files cannot be read or the output directory cannot be written
   */
  @TaskAction
  public void generate() throws IOException {
    List<File> sourceFiles = collectSourceFiles();
    if (sourceFiles.isEmpty()) {
      getLogger().lifecycle("DocletMD: no Java source files found, skipping.");
      return;
    }

    DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
    if (tool == null) {
      throw new GradleException(
          "DocletMD: no documentation tool found. Make sure the build is running on a JDK, not a JRE.");
    }

    StringWriter toolOutput = new StringWriter();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    try (StandardJavaFileManager fm = tool.getStandardFileManager(diagnostics, null, null)) {
      List<File> classpathFiles = getClasspath().getFiles().stream()
          .filter(File::exists)
          .toList();
      if (!classpathFiles.isEmpty()) {
        fm.setLocation(StandardLocation.CLASS_PATH, classpathFiles);
      }

      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sourceFiles);
      List<String> opts = buildOptions();

      DocumentationTool.DocumentationTask docTask = tool.getTask(
          new PrintWriter(toolOutput), fm, diagnostics, DocletMDDoclet.class, opts, units);

      boolean success = docTask.call();

      // Surface any warnings or errors from the tool output.
      String out = toolOutput.toString().strip();
      if (!out.isEmpty()) {
        getLogger().lifecycle("DocletMD: {}", out);
      }

      if (!success) {
        String errors = diagnostics.getDiagnostics().stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("(no details)");
        throw new GradleException("DocletMD generation failed:\n" + errors);
      }
    }

    getLogger().lifecycle("DocletMD: wrote Markdown files to {}",
        getOutputDir().get().getAsFile());
  }

  // Builds the flat list of options passed to the documentation tool.
  private List<String> buildOptions() {
    List<String> opts = new ArrayList<>();
    opts.add("-outputDir");
    opts.add(getOutputDir().get().getAsFile().getAbsolutePath());
    if (getIncludePrivate().get()) {
      opts.add("-includePrivate");
    }
    if (getSkipEmptyDocs().get()) {
      opts.add("-skipEmptyDocs");
    }
    return opts;
  }

  // Recursively collects every .java file under the configured source directories.
  private List<File> collectSourceFiles() throws IOException {
    List<File> files = new ArrayList<>();
    for (File dir : getSourceDirs().getFiles()) {
      if (!dir.exists() || !dir.isDirectory()) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(dir.toPath())) {
        walk.filter(p -> p.toString().endsWith(".java"))
            .map(Path::toFile)
            .forEach(files::add);
      }
    }
    return files;
  }
}
