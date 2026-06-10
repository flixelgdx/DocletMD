# DocletMD

A Gradle plugin that converts your Javadoc comments into Docusaurus-ready Markdown files.

Each public class, interface, enum, and record gets its own `.md` file, organized by package. Fields, constructors, and methods are all documented, including their modifiers, parameter tables, return values, throws tables, and deprecation notices. Inline tags like `{@link}` and `{@code}` are converted to Markdown links and code spans. HTML in your Javadoc (lists, tables, bold, `<pre>` blocks) is converted as well.

## Requirements

- A full JDK 17 or later running your Gradle build (not a JRE). The plugin invokes the Java documentation tool, which only ships with a JDK.
- Gradle 8 or later

## Installation

DocletMD is published to [Maven Central](https://central.sonatype.com/artifact/org.flixelgdx/docletmd). Add `mavenCentral()` to the plugin repositories in your `settings.gradle` (or `settings.gradle.kts`) so Gradle can resolve the plugin:

**`settings.gradle`**
```groovy
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Then apply the plugin alongside the `java` plugin in your `build.gradle`:

**`build.gradle`**
```groovy
plugins {
    id 'java'
    id 'org.flixelgdx.docletmd' version '0.2.0'
}
```

> Replace `0.2.0` with any released version from the [releases page](https://github.com/flixelgdx/DocletMD/releases).

### Alternative: `buildscript` block

If you prefer the legacy approach, skip the `pluginManagement` block and use a `buildscript` classpath instead:

**`build.gradle`**
```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'org.flixelgdx:docletmd:0.2.0'
    }
}

apply plugin: 'java'
apply plugin: 'org.flixelgdx.docletmd'
```

## Configuration

All settings are optional. Add a `docletmd {}` block to `build.gradle` to override the defaults:

```groovy
docletmd {
    // Where the generated Markdown files are written.
    // Default: build/docletmd
    outputDir = file("docs/api")

    // Set to true to also document private and package-private members.
    // Default: false
    includePrivate = false

    // Set to true to skip members that have no Javadoc comment at all.
    // Default: false
    skipEmptyDocs = false

    // Extra flags appended verbatim to the documentation tool invocation.
    // Use these for javadoc-level flags that the typed properties above do not cover.
    // Default: [] (empty)
    additionalArgs = []
}
```

### Source links

To add a "View source" link to the header of each generated page, pass the `-sourceBase` doclet
option through `additionalArgs`. The value is a base URL that is joined with each class file path
to build the link:

```groovy
docletmd {
    additionalArgs = ['-sourceBase', 'https://github.com/your/repo/blob/main/src/main/java/']
}
```

### Split packages (JPMS)

For a modular project where a dependency splits one of your packages, pass `--patch-module`
through `additionalArgs` so the documentation tool can merge the split:

```groovy
docletmd {
    additionalArgs = ['--patch-module', 'your.module=/path/to/extra.jar']
}
```

## Usage

Run the `generateDocletMD` task:

```bash
./gradlew generateDocletMD
```

The task is also available in the **documentation** group in the Gradle task list.

## Output

Each class produces one `.md` file, placed under `outputDir` in a directory tree that mirrors the package structure. For example, a class `com.example.MyService` produces:

```
build/docletmd/
  com/
    example/
      MyService.md
```

Every file contains:

- A YAML frontmatter block with `title`, `sidebar_label`, `toc_max_heading_level`, and `hide_title` (compatible with Docusaurus).
- An H1 heading with the class name. When source links are enabled, the heading and a "View source" button share a `dm-class-header` flex row.
- A kind label (for example *`class`* or *`record`*).
- The full qualified name as an inline code span.
- A colorized type declaration line, emitted as HTML spans (`dm-kw`, `dm-fn`, `dm-type`, `dm-param`) that a stylesheet can color.
- An optional `:::caution Deprecated` admonition when the class is deprecated.
- The Javadoc description, including inline `{@link}` and `{@code}` tags.
- `@since` and `@see` meta-tags.
- `{@inheritDoc}` expansion: a method that uses `{@inheritDoc}` in its description, a `@param`, the `@return`, or a `@throws` copies the matching text from the method it overrides. The doclet searches the superclass chain first and then implemented interfaces, and resolves the tag across several levels of inheritance.
- Sections for **Constructors**, **Fields**, and **Methods**, each member rendered as an H3 entry with:
  - The full signature (modifiers, return type, parameters, constant value for `final` fields).
  - The Javadoc description.
  - Parameter table (`@param` tags).
  - Return value (`@return` tag).
  - Throws table (`@throws` / `@exception` tags).
- Public and protected nested types, each rendered inline as its own H2 section with the same structure.

## Member-type markers

Every member heading (field, constructor, method) is preceded by an HTML comment that identifies its kind:

```
<!-- docletmd:field -->
<!-- docletmd:field:static -->
<!-- docletmd:field:constant -->
<!-- docletmd:constructor -->
<!-- docletmd:method -->
<!-- docletmd:method:static -->
```

`field:constant` is emitted for `static final` fields that have a compile-time constant value (primitives and `String`).

These comments are **invisible** in all standard Markdown renderers and Docusaurus itself, so generated pages look correct with or without any additional tooling. A Docusaurus remark plugin can read the markers and apply styling (for example, color-coding headings by member kind) without modifying the plugin or the generated files.

### Using the markers in a remark plugin

The markers appear in the raw Markdown AST as `html` nodes immediately before each `heading` node of depth 3. A minimal remark plugin that processes them looks like this:

```js
// remark-docletmd-colors.js
export default function remarkDocletmdColors() {
  return (tree) => {
    const { visit } = require('unist-util-visit');
    visit(tree, 'html', (node, index, parent) => {
      const match = node.value.match(/^<!-- docletmd:(\S+) -->$/);
      if (!match || !parent) return;
      const kind = match[1]; // e.g. "method", "field:static", "constructor"
      const next = parent.children[index + 1];
      if (next?.type === 'heading' && next.depth === 3) {
        next.data ??= {};
        next.data.hProperties ??= {};
        next.data.hProperties.className = `docletmd-${kind.replace(':', '-')}`;
      }
    });
  };
}
```

Then add CSS that targets those classes to apply per-kind colors.

## Docusaurus integration

Point Docusaurus at the output directory by adding it as a docs directory or a plugin source in your `docusaurus.config.js`. Because each file has valid frontmatter, Docusaurus picks up the `title` and `sidebar_label` automatically.

```js
// docusaurus.config.js (example)
const config = {
  // ...
  presets: [
    [
      'classic',
      {
        docs: {
          path: 'docs',
          // If your outputDir points inside docs/, nothing extra is needed.
        },
      },
    ],
  ],
};
```

If your `outputDir` is outside the Docusaurus `docs/` folder, copy or symlink the output after running `generateDocletMD`, or wire the two Gradle tasks together:

```groovy
tasks.named('generateDocletMD').configure {
    finalizedBy(tasks.named('copyApiDocs'))
}

tasks.register('copyApiDocs', Copy) {
    from docletmd.outputDir
    into 'path/to/docusaurus/docs/api'
    dependsOn generateDocletMD
}
```

## License

MIT
