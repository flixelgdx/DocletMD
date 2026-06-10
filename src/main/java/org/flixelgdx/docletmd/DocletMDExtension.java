package org.flixelgdx.docletmd;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration block for the DocletMD Gradle plugin.
 *
 * <p>Declare this block in your {@code build.gradle} to customize the plugin:
 *
 * <pre>{@code
 * docletmd {
 *     outputDir = layout.buildDirectory.dir("docs/api")
 *     includePrivate = false
 *     skipEmptyDocs = false
 * }
 * }</pre>
 *
 * <p>All properties are optional. Defaults are applied by the plugin when the
 * extension is registered.
 */
public abstract class DocletMDExtension {

  /** Creates a new extension instance; called by the Gradle object factory. */
  public DocletMDExtension() {}

  /**
   * The directory where generated {@code .md} files are written.
   *
   * <p>Defaults to {@code build/docletmd} inside the project directory.
   *
   * @return the configurable output directory property
   */
  public abstract DirectoryProperty getOutputDir();

  /**
   * Whether to include private and package-private members in the output.
   *
   * <p>Defaults to {@code false}, meaning only {@code public} and {@code protected}
   * members are documented.
   *
   * @return the configurable boolean property
   */
  public abstract Property<Boolean> getIncludePrivate();

  /**
   * Whether to skip members that have no Javadoc comment at all.
   *
   * <p>Defaults to {@code false}, meaning undocumented members still appear in the
   * output (with an empty description). Set to {@code true} to keep generated docs
   * lean by hiding undocumented members entirely.
   *
   * @return the configurable boolean property
   */
  public abstract Property<Boolean> getSkipEmptyDocs();

  /**
   * Extra flags appended verbatim to the documentation tool invocation.
   *
   * <p>Use this for javadoc-level flags that cannot be expressed through the standard
   * extension properties. For example, {@code --patch-module} merges a split package
   * in a JPMS build. These are passed directly after the DocletMD options, so they can
   * override or augment the tool's module resolution.
   *
   * <p>Example for a project where {@code gdx-jnigen-loader} splits the {@code com.badlogic.gdx}
   * package with {@code gdx}:
   * <pre>{@code
   * docletmd {
   *     additionalArgs = ['--patch-module', 'gdx=/path/to/gdx-jnigen-loader.jar']
   * }
   * }</pre>
   *
   * <p>Defaults to an empty list.
   *
   * @return the configurable list property
   */
  public abstract ListProperty<String> getAdditionalArgs();
}
