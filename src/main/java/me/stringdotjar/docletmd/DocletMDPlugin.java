package me.stringdotjar.docletmd;

import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

/**
 * Gradle plugin entry point for DocletMD.
 *
 * <p>Applying this plugin to a project registers:
 * <ul>
 *   <li>A {@code docletmd} extension block (type {@link DocletMDExtension}) for configuration.</li>
 *   <li>A {@code generateDocletMD} task (type {@link DocletMDTask}) that generates Markdown.</li>
 * </ul>
 *
 * <p>If the {@code java} plugin is also applied, the task is automatically wired to the
 * {@code main} source set and its compile classpath.
 *
 * <p>Example {@code build.gradle}:
 * <pre>{@code
 * plugins {
 *     id 'java'
 *     id 'me.stringdotjar.docletmd'
 * }
 *
 * docletmd {
 *     outputDir = layout.buildDirectory.dir("docs/api")
 * }
 * }</pre>
 */
public final class DocletMDPlugin implements Plugin<Project> {

  /** Creates a new plugin instance; called by the Gradle plugin framework. */
  public DocletMDPlugin() {}

  /** The name of the configuration extension added to the project. */
  public static final String EXTENSION_NAME = "docletmd";

  /** The name of the task registered by this plugin. */
  public static final String TASK_NAME = "generateDocletMD";

  @Override
  public void apply(Project project) {
    DocletMDExtension ext = project.getExtensions()
        .create(EXTENSION_NAME, DocletMDExtension.class);

    // Default values for the extension properties.
    ext.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("docletmd"));
    ext.getIncludePrivate().convention(false);
    ext.getSkipEmptyDocs().convention(false);
    ext.getAdditionalArgs().convention(List.of());

    project.getTasks().register(TASK_NAME, DocletMDTask.class, task -> {
      task.setDescription("Generates Docusaurus-ready Markdown from Javadoc comments.");
      task.setGroup("documentation");

      // Bind task properties to the extension.
      task.getOutputDir().set(ext.getOutputDir());
      task.getIncludePrivate().set(ext.getIncludePrivate());
      task.getSkipEmptyDocs().set(ext.getSkipEmptyDocs());
      task.getAdditionalArgs().set(ext.getAdditionalArgs());
    });

    // When the java plugin is also present, a wire source sets and classpath automatically.
    project.getPluginManager().withPlugin("java", __ -> {
      JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
      SourceSet main = javaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

      project.getTasks().named(TASK_NAME, DocletMDTask.class, task -> {
        task.getSourceDirs().from(main.getAllJava().getSourceDirectories());
        task.getClasspath().from(main.getCompileClasspath());
      });
    });
  }
}
